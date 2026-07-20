package dev.orwell.keeboarder.client;

import dev.orwell.logging.Logger;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The websocket-protocol messages every keeboarder desktop client emits, in one place.
 *
 * <p>The Linux and macOS clients speak the same protocol and reported it with the same dozen
 * messages duplicated in both files. The platform-specific parts (evdev/X11 hooks, the macOS event
 * tap) stay in their own client and log through the {@link Logger} directly; only the shared
 * vocabulary lives here.
 *
 * <p>The sink is whatever {@link Logger} the caller injects — this class never picks one.
 */
public final class KeeboarderClientLog {
    private final Logger logger;

    public KeeboarderClientLog(Logger logger) {
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    public void authenticated(String clientId) {
        logger.info("Authenticated with the auth service.", metadata("clientId", clientId));
    }

    public void registered(String name, String clientId) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("name", name);
        metadata.put("clientId", clientId);
        logger.info("Registered with the keeboarder server.", metadata);
    }

    /** A frame the server sent that carries no {@code type} field, so it could not be dispatched. */
    public void untypedServerMessage(String message) {
        logger.warn("Received an untyped message from the server.", metadata("message", message));
    }

    public void hostJoined(String message) {
        logger.info("Host joined.", metadata("message", message));
    }

    public void personalMessage(String message) {
        logger.info("Personal message received.", metadata("message", message));
    }

    public void broadcastReceived(String message) {
        logger.info("Broadcast received.", metadata("message", message));
    }

    /** An error the server reported over the socket — a genuine failure, not routine output. */
    public void serverError(String message) {
        logger.error("Server reported an error.", metadata("message", message));
    }

    public void unknownServerMessage(String message) {
        logger.info("Unrecognised message from the server.", metadata("message", message));
    }

    public void webSocketClosed(String reason) {
        logger.info("WebSocket closed.", metadata("reason", reason));
    }

    public void webSocketError(String message) {
        logger.error("WebSocket error.", metadata("error", message));
    }

    /** One dropped key frame: the socket is still up, so the client keeps going. */
    public void keyEventSendFailed(String cause) {
        logger.warn("Failed to send a key event.", metadata("cause", cause));
    }

    /** The keyboard hook died — the client cannot capture anything after this. */
    public void keyboardHookFailed(String message) {
        logger.error("Keyboard hook error.", metadata("error", message));
    }

    /** {@code Map.of} rejects nulls, and several of these values come from nullable sources. */
    private static Map<String, Object> metadata(String key, String value) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(key, value);
        return metadata;
    }
}
