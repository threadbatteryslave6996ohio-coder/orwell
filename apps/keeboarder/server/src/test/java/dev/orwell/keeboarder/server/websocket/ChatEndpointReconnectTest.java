package dev.orwell.keeboarder.server.websocket;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.orwell.auth.AuthenticationStrategy;
import jakarta.websocket.ClientEndpoint;
import jakarta.websocket.ContainerProvider;
import jakarta.websocket.OnMessage;
import jakarta.websocket.Session;
import jakarta.websocket.WebSocketContainer;
import org.glassfish.tyrus.server.Server;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatEndpointReconnectTest {
    private static final Gson GSON = new Gson();

    @ClientEndpoint
    public static class TestClient {
        private final LinkedBlockingQueue<String> queue;

        public TestClient(LinkedBlockingQueue<String> queue) {
            this.queue = queue;
        }

        @OnMessage
        public void onMessage(String msg) {
            queue.add(msg);
        }
    }

    @Test
    void closingStaleSessionDoesNotRemoveReplacementConnection() throws Exception {
        int wsPort = 8032;
        TestRedisClientCache cache = new TestRedisClientCache();
        AuthenticationStrategy authenticator = (clientId, token) ->
                "shared-client".equals(clientId) && "valid-token".equals(token);

        ChatEndpoint.initialize(cache, authenticator);
        Server wsServer = new Server("localhost", wsPort, "/ws", null, ChatEndpoint.class);

        try {
            wsServer.start();
            WebSocketContainer container = ContainerProvider.getWebSocketContainer();
            URI uri = new URI("ws://localhost:" + wsPort + "/ws/chat");

            LinkedBlockingQueue<String> firstMessages = new LinkedBlockingQueue<>();
            Session firstSession = container.connectToServer(new TestClient(firstMessages), uri);
            awaitMessage(firstMessages);
            register(firstSession, "shared-client", "valid-token");
            awaitRegistered(firstMessages);

            LinkedBlockingQueue<String> secondMessages = new LinkedBlockingQueue<>();
            Session secondSession = container.connectToServer(new TestClient(secondMessages), uri);
            awaitMessage(secondMessages);
            register(secondSession, "shared-client", "valid-token");
            awaitRegistered(secondMessages);

            assertTrue(!firstSession.isOpen() || secondSession.isOpen());
            assertTrue(cache.getAllClientIds().contains("shared-client"));

            JsonArray clients = GSON.fromJson(ChatEndpoint.getConnectedClientsJson(), JsonArray.class);
            assertEquals(1, clients.size());
            JsonObject client = clients.get(0).getAsJsonObject();
            assertEquals("shared-client", client.get("clientId").getAsString());
            assertTrue(client.get("connected").getAsBoolean());

            secondSession.close();
        } finally {
            try {
                wsServer.stop();
            } catch (Exception ignored) {
            }
            cache.close();
        }
    }

    @Test
    void failedReconnectLeavesExistingSessionConnected() throws Exception {
        int wsPort = 8034;
        FailingRedisClientCache cache = new FailingRedisClientCache();
        AuthenticationStrategy authenticator = (clientId, token) ->
                "shared-client".equals(clientId) && "valid-token".equals(token);

        ChatEndpoint.initialize(cache, authenticator);
        Server wsServer = new Server("localhost", wsPort, "/ws", null, ChatEndpoint.class);

        try {
            wsServer.start();
            WebSocketContainer container = ContainerProvider.getWebSocketContainer();
            URI uri = new URI("ws://localhost:" + wsPort + "/ws/chat");

            LinkedBlockingQueue<String> firstMessages = new LinkedBlockingQueue<>();
            Session firstSession = container.connectToServer(new TestClient(firstMessages), uri);
            awaitMessage(firstMessages);
            register(firstSession, "shared-client", "valid-token");
            awaitRegistered(firstMessages);

            cache.failNextRegister();

            LinkedBlockingQueue<String> secondMessages = new LinkedBlockingQueue<>();
            Session secondSession = container.connectToServer(new TestClient(secondMessages), uri);
            awaitMessage(secondMessages);
            register(secondSession, "shared-client", "valid-token");

            assertTrue(ChatEndpoint.sendServerPersonalMessage("shared-client", "server", "still-connected"));
            String delivered = firstMessages.poll(3, TimeUnit.SECONDS);
            assertNotNull(delivered);
            JsonObject personal = GSON.fromJson(delivered, JsonObject.class);
            assertEquals("personal", personal.get("type").getAsString());
            assertEquals("still-connected", personal.get("content").getAsString());

            secondSession.close();
            firstSession.close();
        } finally {
            try {
                wsServer.stop();
            } catch (Exception ignored) {
            }
            cache.close();
        }
    }

    @Test
    void closingOldSocketDuringReconnectDoesNotDropReplacementPresence() throws Exception {
        int wsPort = 8036;
        BlockingRedisClientCache cache = new BlockingRedisClientCache();
        AuthenticationStrategy authenticator = (clientId, token) ->
                "shared-client".equals(clientId) && "valid-token".equals(token);

        ChatEndpoint.initialize(cache, authenticator);
        Server wsServer = new Server("localhost", wsPort, "/ws", null, ChatEndpoint.class);

        try {
            wsServer.start();
            WebSocketContainer container = ContainerProvider.getWebSocketContainer();
            URI uri = new URI("ws://localhost:" + wsPort + "/ws/chat");

            LinkedBlockingQueue<String> firstMessages = new LinkedBlockingQueue<>();
            Session firstSession = container.connectToServer(new TestClient(firstMessages), uri);
            awaitMessage(firstMessages);
            register(firstSession, "shared-client", "valid-token");
            awaitRegistered(firstMessages);

            cache.blockNextRegister();

            LinkedBlockingQueue<String> secondMessages = new LinkedBlockingQueue<>();
            Session secondSession = container.connectToServer(new TestClient(secondMessages), uri);
            awaitMessage(secondMessages);
            register(secondSession, "shared-client", "valid-token");

            assertTrue(cache.awaitBlockedRegister());
            firstSession.close();
            cache.releaseRegister();

            awaitRegistered(secondMessages);
            assertTrue(cache.getAllClientIds().contains("shared-client"));

            JsonArray clients = GSON.fromJson(ChatEndpoint.getConnectedClientsJson(), JsonArray.class);
            assertEquals(1, clients.size());
            JsonObject client = clients.get(0).getAsJsonObject();
            assertEquals("shared-client", client.get("clientId").getAsString());
            assertTrue(client.get("connected").getAsBoolean());

            secondSession.close();
        } finally {
            try {
                wsServer.stop();
            } catch (Exception ignored) {
            }
            cache.close();
        }
    }

    private static void register(Session session, String clientId, String token) throws Exception {
        JsonObject reg = new JsonObject();
        reg.addProperty("type", "register");
        reg.addProperty("clientId", clientId);
        reg.addProperty("name", clientId);
        reg.addProperty("token", token);
        session.getBasicRemote().sendText(GSON.toJson(reg));
    }

    private static void awaitRegistered(LinkedBlockingQueue<String> messages) throws Exception {
        String registered = awaitMessage(messages);
        JsonObject regObj = GSON.fromJson(registered, JsonObject.class);
        assertEquals("registered", regObj.get("type").getAsString());
    }

    private static String awaitMessage(LinkedBlockingQueue<String> messages) throws Exception {
        String message = messages.poll(3, TimeUnit.SECONDS);
        assertNotNull(message);
        return message;
    }

    static class TestRedisClientCache extends RedisClientCache {
        private final Set<String> clients = Collections.synchronizedSet(new HashSet<>());
        private final Map<String, RedisClientCache.ClientInfo> infos = Collections.synchronizedMap(new HashMap<>());

        TestRedisClientCache() {
            super("localhost", 6379);
        }

        @Override
        public void registerClient(String clientId, String name, String connectedAt) {
            clients.add(clientId);
            infos.put(clientId, new RedisClientCache.ClientInfo(clientId, name, connectedAt));
        }

        @Override
        public void unregisterClient(String clientId) {
            clients.remove(clientId);
            infos.remove(clientId);
        }

        @Override
        public java.util.Optional<RedisClientCache.ClientInfo> getClientInfo(String clientId) {
            return java.util.Optional.ofNullable(infos.get(clientId));
        }

        @Override
        public Set<String> getAllClientIds() {
            return new HashSet<>(clients);
        }

        @Override
        public java.util.Optional<String> findClientIdByName(String name) {
            for (var entry : infos.entrySet()) {
                if (entry.getValue().name.equals(name)) {
                    return java.util.Optional.of(entry.getKey());
                }
            }
            return java.util.Optional.empty();
        }

        @Override
        public void close() {
            clients.clear();
            infos.clear();
        }
    }

    static final class FailingRedisClientCache extends TestRedisClientCache {
        private volatile boolean failNextRegister;

        void failNextRegister() {
            failNextRegister = true;
        }

        @Override
        public void registerClient(String clientId, String name, String connectedAt) {
            if (failNextRegister) {
                failNextRegister = false;
                throw new IllegalStateException("redis unavailable");
            }
            super.registerClient(clientId, name, connectedAt);
        }
    }

    static final class BlockingRedisClientCache extends TestRedisClientCache {
        private volatile CountDownLatch blockedRegisterEntered = new CountDownLatch(0);
        private volatile CountDownLatch releaseBlockedRegister = new CountDownLatch(0);
        private volatile boolean blockNextRegister;

        void blockNextRegister() {
            blockNextRegister = true;
            blockedRegisterEntered = new CountDownLatch(1);
            releaseBlockedRegister = new CountDownLatch(1);
        }

        boolean awaitBlockedRegister() throws InterruptedException {
            return blockedRegisterEntered.await(3, TimeUnit.SECONDS);
        }

        void releaseRegister() {
            releaseBlockedRegister.countDown();
        }

        @Override
        public void registerClient(String clientId, String name, String connectedAt) {
            if (blockNextRegister) {
                blockNextRegister = false;
                blockedRegisterEntered.countDown();
                try {
                    assertTrue(releaseBlockedRegister.await(3, TimeUnit.SECONDS));
                } catch (InterruptedException interruptedException) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("interrupted while waiting for reconnect release", interruptedException);
                }
            }
            super.registerClient(clientId, name, connectedAt);
        }
    }
}
