package dev.orwell.keeboarder.mac;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.orwell.auth.http.client.HttpAuthenticationStrategy;
import dev.orwell.auth.http.api.LoginHttpResponse;
import dev.orwell.keeboarder.client.KeeboarderClientConfig;
import dev.orwell.keeboarder.client.KeeboarderClientLog;
import dev.orwell.logging.Logger;
import com.sun.jna.Callback;
import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.mac.CoreFoundation;
import jakarta.websocket.ClientEndpoint;
import jakarta.websocket.CloseReason;
import jakarta.websocket.OnClose;
import jakarta.websocket.OnError;
import jakarta.websocket.OnMessage;
import jakarta.websocket.OnOpen;
import jakarta.websocket.Session;
import jakarta.websocket.ContainerProvider;
import jakarta.websocket.WebSocketContainer;

import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@ClientEndpoint
public final class MacKeyboardClient {
    private static final Gson GSON = new Gson();
    private static final String RUN_LOOP_COMMON_MODES = "kCFRunLoopCommonModes";
    private static final int KEY_CODE_FIELD = 9;

    private final KeeboarderClientConfig config;
    private final CountDownLatch shutdownLatch = new CountDownLatch(1);
    private final AtomicBoolean registered = new AtomicBoolean(false);
    private final AtomicBoolean cleanedUp = new AtomicBoolean(false);
    private final AtomicReference<Session> sessionRef = new AtomicReference<>();
    private final AtomicReference<String> currentClientId;
    private final HttpAuthenticationStrategy authClient;
    private final NativeKeyTapCallback tapCallback = this::handleNativeEvent;
    private final Logger logger;
    private final KeeboarderClientLog log;

    private volatile Pointer eventTap;
    private volatile Pointer runLoop;
    private volatile Pointer runLoopSource;
    private volatile CoreFoundation.CFStringRef runLoopMode;
    private volatile Thread tapThread;
    private volatile String authToken;

    public MacKeyboardClient(KeeboarderClientConfig config, Logger logger) {
        this.config = config;
        this.logger = logger;
        this.log = new KeeboarderClientLog(logger);
        this.currentClientId = new AtomicReference<>(config.clientId());
        this.authClient = new HttpAuthenticationStrategy(config.authBaseUrl());
    }

    public void run() throws Exception {
        installShutdownHook();
        try {
            authenticate();
            connect();
            startKeyEventTap();
            shutdownLatch.await();
        } finally {
            cleanup();
        }
    }

    private void connect() throws Exception {
        WebSocketContainer container = ContainerProvider.getWebSocketContainer();
        container.connectToServer(this, URI.create(config.serverUrl()));
    }

    private void startKeyEventTap() {
        tapThread = new Thread(this::runKeyEventTap, "keeboarder-mac-event-tap");
        tapThread.setDaemon(true);
        tapThread.start();
    }

    private void runKeyEventTap() {
        try {
            ApplicationServices app = ApplicationServices.INSTANCE;
            CoreFoundation cf = CoreFoundation.INSTANCE;

            runLoopMode = CoreFoundation.CFStringRef.createCFString(RUN_LOOP_COMMON_MODES);
            eventTap = app.CGEventTapCreate(
                    ApplicationServices.kCGHIDEventTap,
                    ApplicationServices.kCGHeadInsertEventTap,
                    ApplicationServices.kCGEventTapOptionListenOnly,
                    ApplicationServices.eventMaskFor(ApplicationServices.kCGEventKeyDown)
                            | ApplicationServices.eventMaskFor(ApplicationServices.kCGEventKeyUp),
                    tapCallback,
                    null
            );

            if (eventTap == null) {
                logger.error("Failed to create the macOS event tap.");
                shutdownLatch.countDown();
                return;
            }

            runLoopSource = app.CFMachPortCreateRunLoopSource(cf.CFAllocatorGetDefault(), eventTap, 0);
            if (runLoopSource == null) {
                logger.error("Failed to create the run loop source.");
                shutdownLatch.countDown();
                return;
            }

            runLoop = MacCoreFoundation.INSTANCE.CFRunLoopGetCurrent();
            MacCoreFoundation.INSTANCE.CFRunLoopAddSource(runLoop, runLoopSource, runLoopMode);
            app.CGEventTapEnable(eventTap, true);

            logger.info("Keyboard capture enabled. Grant Accessibility permissions if macOS blocks the hook.");
            MacCoreFoundation.INSTANCE.CFRunLoopRun();
        } catch (Throwable throwable) {
            log.keyboardHookFailed(throwable.getMessage());
            shutdownLatch.countDown();
        }
    }

    private void installShutdownHook() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            shutdownLatch.countDown();
            cleanup();
        }, "keeboarder-mac-shutdown"));
    }

    @OnOpen
    public void onOpen(Session session) throws IOException {
        sessionRef.set(session);
        sendRegister(session);
    }

    @OnMessage
    public void onMessage(String message) {
        JsonObject msg = GSON.fromJson(message, JsonObject.class);
        if (msg == null || !msg.has("type")) {
            log.untypedServerMessage(message);
            return;
        }

        String type = msg.get("type").getAsString();
        switch (type) {
            case "registered" -> handleRegistered(msg);
            case "host_joined" -> log.hostJoined(msg.toString());
            case "personal" -> log.personalMessage(msg.toString());
            case "broadcast" -> {
                if (!msg.has("fromClientId") || !msg.get("fromClientId").getAsString().equals(currentClientId.get())) {
                    log.broadcastReceived(msg.toString());
                }
            }
            case "error" -> log.serverError(msg.get("message").getAsString());
            default -> log.unknownServerMessage(msg.toString());
        }
    }

    @OnClose
    public void onClose(Session session, CloseReason reason) {
        log.webSocketClosed(String.valueOf(reason));
        shutdownLatch.countDown();
    }

    @OnError
    public void onError(Session session, Throwable throwable) {
        log.webSocketError(throwable.getMessage());
        shutdownLatch.countDown();
    }

    private void handleRegistered(JsonObject msg) {
        if (msg.has("clientId")) {
            currentClientId.set(msg.get("clientId").getAsString());
        }
        registered.set(true);
        String name = msg.has("name") ? msg.get("name").getAsString() : "unknown";
        log.registered(name, currentClientId.get());
    }

    private void sendRegister(Session session) throws IOException {
        session.getAsyncRemote().sendText(GSON.toJson(buildRegisterPayload()));
    }

    JsonObject buildRegisterPayload() {
        JsonObject payload = new JsonObject();
        payload.addProperty("type", "register");
        payload.addProperty("clientId", currentClientId.get());
        payload.addProperty("name", config.name());
        payload.addProperty("token", authToken);
        return payload;
    }

    private void authenticate() {
        LoginHttpResponse login = authClient.login(config.clientId(), config.clientSecret());
        authToken = login.token();
        currentClientId.set(login.clientId());
        log.authenticated(login.clientId());
    }

    private Pointer handleNativeEvent(Pointer proxy, int type, Pointer event, Pointer refcon) {
        if (type == ApplicationServices.kCGEventTapDisabledByTimeout
                || type == ApplicationServices.kCGEventTapDisabledByUserInput) {
            ApplicationServices.INSTANCE.CGEventTapEnable(eventTap, true);
            return event;
        }

        if (!registered.get()) {
            return event;
        }

        if (type == ApplicationServices.kCGEventKeyDown) {
            sendKeyEvent("press", event);
        } else if (type == ApplicationServices.kCGEventKeyUp) {
            sendKeyEvent("release", event);
        }

        return event;
    }

    private void sendKeyEvent(String eventName, Pointer event) {
        Session session = sessionRef.get();
        if (session == null || !session.isOpen()) {
            return;
        }

        long keyCode = ApplicationServices.INSTANCE.CGEventGetIntegerValueField(event, KEY_CODE_FIELD);
        JsonObject keyPayload = new JsonObject();
        keyPayload.addProperty("platform", "macos");
        keyPayload.addProperty("event", eventName);
        keyPayload.addProperty("keyCode", keyCode);
        keyPayload.addProperty("keyName", describeKeyCode((int) keyCode));
        keyPayload.addProperty("timestamp", Instant.now().toString());
        keyPayload.add("modifiers", modifiersFromFlags(ApplicationServices.INSTANCE.CGEventGetFlags(event)));

        JsonObject payload = new JsonObject();
        payload.addProperty("type", "broadcast");
        payload.addProperty("clientId", currentClientId.get());
        payload.addProperty("content", GSON.toJson(keyPayload));

        session.getAsyncRemote().sendText(GSON.toJson(payload), result -> {
            if (!result.isOK()) {
                log.keyEventSendFailed(String.valueOf(result.getException()));
            }
        });
    }

    private JsonArray modifiersFromFlags(long flags) {
        JsonArray modifiers = new JsonArray();
        if ((flags & ApplicationServices.kCGEventFlagMaskShift) != 0) {
            modifiers.add("shift");
        }
        if ((flags & ApplicationServices.kCGEventFlagMaskControl) != 0) {
            modifiers.add("control");
        }
        if ((flags & ApplicationServices.kCGEventFlagMaskAlternate) != 0) {
            modifiers.add("option");
        }
        if ((flags & ApplicationServices.kCGEventFlagMaskCommand) != 0) {
            modifiers.add("command");
        }
        if ((flags & ApplicationServices.kCGEventFlagMaskSecondaryFn) != 0) {
            modifiers.add("fn");
        }
        return modifiers;
    }

    private String describeKeyCode(int keyCode) {
        return switch (keyCode) {
            case 0 -> "a";
            case 1 -> "s";
            case 2 -> "d";
            case 3 -> "f";
            case 4 -> "h";
            case 5 -> "g";
            case 6 -> "z";
            case 7 -> "x";
            case 8 -> "c";
            case 9 -> "v";
            case 11 -> "b";
            case 12 -> "q";
            case 13 -> "w";
            case 14 -> "e";
            case 15 -> "r";
            case 16 -> "y";
            case 17 -> "t";
            case 18 -> "1";
            case 19 -> "2";
            case 20 -> "3";
            case 21 -> "4";
            case 23 -> "5";
            case 22 -> "6";
            case 26 -> "7";
            case 28 -> "8";
            case 25 -> "9";
            case 29 -> "0";
            case 31 -> "o";
            case 32 -> "u";
            case 34 -> "i";
            case 35 -> "p";
            case 37 -> "l";
            case 38 -> "j";
            case 40 -> "k";
            case 41 -> "semicolon";
            case 45 -> "n";
            case 46 -> "m";
            case 36 -> "return";
            case 48 -> "tab";
            case 49 -> "space";
            case 51 -> "delete";
            case 53 -> "escape";
            case 126 -> "up";
            case 125 -> "down";
            case 123 -> "left";
            case 124 -> "right";
            default -> "keyCode_" + keyCode;
        };
    }

    private void cleanup() {
        if (!cleanedUp.compareAndSet(false, true)) {
            return;
        }

        try {
            if (eventTap != null) {
                ApplicationServices.INSTANCE.CGEventTapEnable(eventTap, false);
            }
        } catch (Throwable ignored) {
        }

        try {
            if (runLoop != null) {
                MacCoreFoundation.INSTANCE.CFRunLoopStop(runLoop);
            }
        } catch (Throwable ignored) {
        }

        if (tapThread != null && tapThread != Thread.currentThread()) {
            try {
                tapThread.join(1000);
            } catch (InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
            }
        }

        Session session = sessionRef.getAndSet(null);
        if (session != null) {
            try {
                if (session.isOpen()) {
                    session.close();
                }
            } catch (IOException ignored) {
            }
        }

        try {
            if (runLoopSource != null) {
                CoreFoundation.INSTANCE.CFRelease(new CoreFoundation.CFTypeRef(runLoopSource));
                runLoopSource = null;
            }
        } catch (Throwable ignored) {
        }

        try {
            if (runLoopMode != null) {
                CoreFoundation.INSTANCE.CFRelease(runLoopMode);
                runLoopMode = null;
            }
        } catch (Throwable ignored) {
        }
    }

    @FunctionalInterface
    private interface NativeKeyTapCallback extends ApplicationServices.CGEventTapCallBack {
    }

    interface MacCoreFoundation extends Library {
        MacCoreFoundation INSTANCE = Native.load("CoreFoundation", MacCoreFoundation.class);

        Pointer CFRunLoopGetCurrent();

        void CFRunLoopAddSource(Pointer runLoop, Pointer source, CoreFoundation.CFStringRef mode);

        void CFRunLoopRun();

        void CFRunLoopStop(Pointer runLoop);
    }

    interface ApplicationServices extends Library {
        ApplicationServices INSTANCE = Native.load("ApplicationServices", ApplicationServices.class);

        int kCGHIDEventTap = 0;
        int kCGHeadInsertEventTap = 0;
        int kCGEventTapOptionListenOnly = 1;
        int kCGEventKeyDown = 10;
        int kCGEventKeyUp = 11;
        int kCGEventTapDisabledByTimeout = -2;
        int kCGEventTapDisabledByUserInput = -3;

        long kCGEventFlagMaskShift = 1L << 17;
        long kCGEventFlagMaskControl = 1L << 18;
        long kCGEventFlagMaskAlternate = 1L << 19;
        long kCGEventFlagMaskCommand = 1L << 20;
        long kCGEventFlagMaskSecondaryFn = 1L << 23;

        interface CGEventTapCallBack extends Callback {
            Pointer callback(Pointer proxy, int type, Pointer event, Pointer refcon);
        }

        Pointer CGEventTapCreate(int tap, int place, int options, long eventsOfInterest, CGEventTapCallBack callback, Pointer userInfo);

        Pointer CFMachPortCreateRunLoopSource(CoreFoundation.CFAllocatorRef allocator, Pointer tap, int order);

        void CGEventTapEnable(Pointer tap, boolean enable);

        long CGEventGetIntegerValueField(Pointer event, int field);

        long CGEventGetFlags(Pointer event);

        static long eventMaskFor(int eventType) {
            return 1L << eventType;
        }
    }
}
