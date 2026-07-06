package dev.orwell.keeboarder.server;

import dev.orwell.auth.AuthenticationStrategy;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import jakarta.websocket.OnClose;
import jakarta.websocket.OnError;
import jakarta.websocket.OnMessage;
import jakarta.websocket.OnOpen;
import jakarta.websocket.Session;
import jakarta.websocket.server.ServerEndpoint;

import java.io.IOException;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@ServerEndpoint(value = "/chat")
public class ChatEndpoint {
    private static final Gson gson = new Gson();
    private static final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private static final Map<String, String> sessionClientIds = new ConcurrentHashMap<>();
    private static RedisClientCache redisCache;
    private static AuthenticationStrategy clientAuthenticator;

    public static void initialize(RedisClientCache cache, AuthenticationStrategy authenticator) {
        redisCache = cache;
        clientAuthenticator = authenticator;
    }

    @OnOpen
    public void onOpen(Session session) throws IOException {
        sendJson(session, createSystemMessage("register_required", "Send a registration payload with type=register, clientId, name and token."));
    }

    @OnMessage
    public void onMessage(Session session, String message) throws IOException {
        if (redisCache == null) {
            sendJson(session, createSystemMessage("error", "Server cache is not initialized."));
            return;
        }
        if (clientAuthenticator == null) {
            sendJson(session, createSystemMessage("error", "Server authenticator is not initialized."));
            return;
        }

        Message msg = Message.fromJson(message);
        if (msg == null || msg.type == null) {
            sendJson(session, createSystemMessage("error", "Invalid message format."));
            return;
        }

        switch (msg.type) {
            case "register" -> handleRegister(session, msg);
            case "personal" -> handlePersonalMessage(session, msg);
            case "broadcast" -> handleBroadcastMessage(session, msg);
            default -> sendJson(session, createSystemMessage("error", "Unknown message type: " + msg.type));
        }
    }

    @OnClose
    public void onClose(Session session) {
        cleanupSession(session);
    }

    @OnError
    public void onError(Session session, Throwable throwable) {
        cleanupSession(session);
    }

    private void handleRegister(Session session, Message msg) throws IOException {
        if (msg.clientId == null || msg.clientId.isBlank()) {
            sendJson(session, createSystemMessage("error", "Register message must include a non-empty clientId."));
            return;
        }
        if (msg.name == null || msg.name.isBlank()) {
            sendJson(session, createSystemMessage("error", "Register message must include a non-empty name."));
            return;
        }
        if (msg.token == null || msg.token.isBlank()) {
            sendJson(session, createSystemMessage("error", "Register message must include a non-empty token."));
            closeUnauthorizedSession(session, "missing_token");
            return;
        }

        boolean tokenValid;
        try {
            tokenValid = clientAuthenticator.isTokenValidForClient(msg.clientId, msg.token);
        } catch (RuntimeException exception) {
            sendJson(session, createSystemMessage("error", "Authentication service is unavailable."));
            closeUnauthorizedSession(session, "auth_unavailable");
            return;
        }
        if (!tokenValid) {
            sendJson(session, createSystemMessage("error", "Authentication failed."));
            closeUnauthorizedSession(session, "invalid_token");
            return;
        }

        String clientId = msg.clientId;
        sessions.put(clientId, session);
        sessionClientIds.put(session.getId(), clientId);
        redisCache.registerClient(clientId, msg.name, Instant.now().toString());

        JsonObject registerAck = new JsonObject();
        registerAck.addProperty("type", "registered");
        registerAck.addProperty("clientId", clientId);
        registerAck.addProperty("name", msg.name);
        sendJson(session, registerAck);

        announceHostJoin(clientId, msg.name);
    }

    private void handlePersonalMessage(Session session, Message msg) throws IOException {
        String authenticatedClientId = sessionClientIds.get(session.getId());
        if (authenticatedClientId == null) {
            sendJson(session, createSystemMessage("error", "You must register before sending messages."));
            return;
        }
        if (msg.toClientId == null || msg.toClientId.isBlank()) {
            sendJson(session, createSystemMessage("error", "personal messages require toClientId."));
            return;
        }
        Session target = sessions.get(msg.toClientId);
        if (target == null || !target.isOpen()) {
            sendJson(session, createSystemMessage("error", "Target client is not connected: " + msg.toClientId));
            return;
        }

        JsonObject delivered = new JsonObject();
        delivered.addProperty("type", "personal");
        delivered.addProperty("fromClientId", authenticatedClientId);
        delivered.addProperty("content", msg.content);
        sendJson(target, delivered);
    }

    private void handleBroadcastMessage(Session session, Message msg) throws IOException {
        String authenticatedClientId = sessionClientIds.get(session.getId());
        if (authenticatedClientId == null) {
            sendJson(session, createSystemMessage("error", "You must register before sending messages."));
            return;
        }
        JsonObject broadcast = new JsonObject();
        broadcast.addProperty("type", "broadcast");
        broadcast.addProperty("fromClientId", authenticatedClientId);
        broadcast.addProperty("content", msg.content);
        broadcastToAll(broadcast, Collections.emptySet());
    }

    private void announceHostJoin(String clientId, String name) {
        JsonObject announcement = new JsonObject();
        announcement.addProperty("type", "host_joined");
        announcement.addProperty("clientId", clientId);
        announcement.addProperty("name", name);
        announcement.addProperty("connectedAt", Instant.now().toString());
        broadcastToAll(announcement, Set.of(clientId));
    }

    private void broadcastToAll(JsonObject payload, Set<String> excludeClientIds) {
        sessions.forEach((clientId, session) -> {
            if (excludeClientIds.contains(clientId)) {
                return;
            }
            if (session.isOpen()) {
                try {
                    sendJson(session, payload);
                } catch (IOException ioException) {
                    // ignore send failure for one client
                }
            }
        });
    }

    private void cleanupSession(Session session) {
        String removedClientId = sessionClientIds.remove(session.getId());
        if (removedClientId == null) {
            for (Map.Entry<String, Session> entry : sessions.entrySet()) {
                if (entry.getValue().equals(session)) {
                    removedClientId = entry.getKey();
                    break;
                }
            }
        }
        if (removedClientId != null) {
            sessions.remove(removedClientId);
            if (redisCache != null) {
                redisCache.unregisterClient(removedClientId);
            }
        }
    }

    private void closeUnauthorizedSession(Session session, String reason) throws IOException {
        JsonObject payload = new JsonObject();
        payload.addProperty("type", "auth_failed");
        payload.addProperty("reason", reason);
        sendJson(session, payload);
        session.close();
    }

    private void sendJson(Session session, JsonObject payload) throws IOException {
        session.getBasicRemote().sendText(gson.toJson(payload));
    }

    private JsonObject createSystemMessage(String type, String message) {
        JsonObject system = new JsonObject();
        system.addProperty("type", type);
        system.addProperty("message", message);
        return system;
    }

    // ---------- HTTP API helpers (used by an embedded HTTP server) ----------
    public static String getConnectedClientsJson() {
        JsonArray arr = new JsonArray();
        if (redisCache == null) {
            return gson.toJson(arr);
        }
        try {
            for (String clientId : redisCache.getAllClientIds()) {
                JsonObject obj = new JsonObject();
                obj.addProperty("clientId", clientId);
                RedisClientCache.ClientInfo info = null;
                try {
                    var opt = redisCache.getClientInfo(clientId);
                    if (opt.isPresent()) info = opt.get();
                } catch (Exception ignored) {
                }
                if (info != null) {
                    obj.addProperty("name", info.name);
                    obj.addProperty("connectedAt", info.connectedAt);
                }
                // attach whether there's an active WS session
                Session session = sessions.get(clientId);
                obj.addProperty("connected", session != null && session.isOpen());
                arr.add(obj);
            }
        } catch (Exception e) {
            // on any redis error return what we have
        }
        return gson.toJson(arr);
    }

    public static boolean sendServerPersonalMessage(String toClientId, String fromClientId, String content) {
        Session target = sessions.get(toClientId);
        if (target == null || !target.isOpen()) {
            return false;
        }
        JsonObject delivered = new JsonObject();
        delivered.addProperty("type", "personal");
        delivered.addProperty("fromClientId", fromClientId);
        delivered.addProperty("content", content);
        try {
            target.getBasicRemote().sendText(gson.toJson(delivered));
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
