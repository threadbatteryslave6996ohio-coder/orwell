package dev.orwell.keeboarder.linux;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.orwell.auth.http.api.LoginHttpResponse;
import dev.orwell.auth.http.client.HttpAuthenticationStrategy;
import dev.orwell.keeboarder.client.KeeboarderClientConfig;
import dev.orwell.keeboarder.client.KeeboarderClientLog;
import dev.orwell.logging.Logger;
import jakarta.websocket.ClientEndpoint;
import jakarta.websocket.CloseReason;
import jakarta.websocket.ContainerProvider;
import jakarta.websocket.OnClose;
import jakarta.websocket.OnError;
import jakarta.websocket.OnMessage;
import jakarta.websocket.OnOpen;
import jakarta.websocket.Session;
import jakarta.websocket.WebSocketContainer;

import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@ClientEndpoint
public final class LinuxKeyboardClient {
    private static final Gson GSON = new Gson();

    private final KeeboarderClientConfig config;
    private final CountDownLatch shutdownLatch = new CountDownLatch(1);
    private final AtomicBoolean registered = new AtomicBoolean(false);
    private final AtomicBoolean cleanedUp = new AtomicBoolean(false);
    private final AtomicReference<Session> sessionRef = new AtomicReference<>();
    private final AtomicReference<String> currentClientId;
    private final HttpAuthenticationStrategy authClient;
    private final KeyboardMonitor keyboardMonitor;
    private final KeeboarderClientLog log;

    private volatile String authToken;

    public LinuxKeyboardClient(KeeboarderClientConfig config, Logger logger) {
        this.config = config;
        this.log = new KeeboarderClientLog(logger);
        this.currentClientId = new AtomicReference<>(config.clientId());
        this.authClient = new HttpAuthenticationStrategy(config.authBaseUrl());
        this.keyboardMonitor = KeyboardMonitors.create(this::sendKeyEvent, this::handleKeyboardFailure);
    }

    public void run() throws Exception {
        installShutdownHook();
        try {
            authenticate();
            connect();
            keyboardMonitor.start();
            shutdownLatch.await();
        } finally {
            cleanup();
        }
    }

    private void authenticate() {
        LoginHttpResponse login = authClient.login(config.clientId(), config.clientSecret());
        authToken = login.token();
        currentClientId.set(login.clientId());
        log.authenticated(login.clientId());
    }

    private void connect() throws Exception {
        WebSocketContainer container = ContainerProvider.getWebSocketContainer();
        container.connectToServer(this, URI.create(config.serverUrl()));
    }

    private void installShutdownHook() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            shutdownLatch.countDown();
            cleanup();
        }, "keeboarder-linux-shutdown"));
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

    private void sendKeyEvent(String eventName, int keyCode, String keyName, List<String> modifiers) {
        Session session = sessionRef.get();
        if (!registered.get() || session == null || !session.isOpen()) {
            return;
        }

        JsonObject keyPayload = new JsonObject();
        keyPayload.addProperty("platform", "linux");
        keyPayload.addProperty("event", eventName);
        keyPayload.addProperty("keyCode", keyCode);
        keyPayload.addProperty("keyName", keyName);
        keyPayload.addProperty("timestamp", Instant.now().toString());

        JsonArray modifierArray = new JsonArray();
        modifiers.forEach(modifierArray::add);
        keyPayload.add("modifiers", modifierArray);

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

    private void handleKeyboardFailure(RuntimeException exception) {
        log.keyboardHookFailed(exception.getMessage());
        shutdownLatch.countDown();
    }

    private void cleanup() {
        if (!cleanedUp.compareAndSet(false, true)) {
            return;
        }

        keyboardMonitor.stop();

        Session session = sessionRef.getAndSet(null);
        if (session != null) {
            try {
                if (session.isOpen()) {
                    session.close();
                }
            } catch (IOException ignored) {
            }
        }
    }
}
