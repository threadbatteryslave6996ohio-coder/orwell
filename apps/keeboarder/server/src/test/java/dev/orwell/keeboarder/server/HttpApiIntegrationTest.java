package dev.orwell.keeboarder.server;

import dev.orwell.auth.AuthenticationStrategy;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.glassfish.tyrus.server.Server;
import org.junit.jupiter.api.Test;

import jakarta.websocket.ClientEndpoint;
import jakarta.websocket.ContainerProvider;
import jakarta.websocket.OnMessage;
import jakarta.websocket.Session;
import jakarta.websocket.WebSocketContainer;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

public class HttpApiIntegrationTest {
    private static final Gson gson = new Gson();

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
    public void clientConnectsListedAndReceivesHttpSentMessage() throws Exception {
        int wsPort = 8031;
        int httpPort = 8083;
        TestRedisClientCache cache = new TestRedisClientCache();

        AuthenticationStrategy authenticator = (clientId, token) -> "test-client".equals(clientId) && "valid-token".equals(token);

        // initialize ChatEndpoint with our in-memory cache
        ChatEndpoint.initialize(cache, authenticator);

        Server wsServer = new Server("localhost", wsPort, "/ws", null, ChatEndpoint.class);
        HttpApiServer httpApi = new HttpApiServer("localhost", httpPort, new KeeboarderService(), authenticator);

        try {
            wsServer.start();
            httpApi.start();

            // WebSocket client
            LinkedBlockingQueue<String> messages = new LinkedBlockingQueue<>();

            WebSocketContainer container = ContainerProvider.getWebSocketContainer();
            URI uri = new URI("ws://localhost:" + wsPort + "/ws/chat");
            Session session = container.connectToServer(new TestClient(messages), uri);

            // wait for register_required prompt
            String sys = messages.poll(3, TimeUnit.SECONDS);
            assertNotNull(sys, "expected system message on connect");

            // send register
            JsonObject reg = new JsonObject();
            reg.addProperty("type", "register");
            reg.addProperty("clientId", "test-client");
            reg.addProperty("name", "test-client");
            reg.addProperty("token", "valid-token");
            session.getBasicRemote().sendText(gson.toJson(reg));

            // wait for registered ack
            String registered = messages.poll(3, TimeUnit.SECONDS);
            assertNotNull(registered, "expected registered message");
            JsonObject regObj = gson.fromJson(registered, JsonObject.class);
            assertEquals("registered", regObj.get("type").getAsString());
            String clientId = regObj.get("clientId").getAsString();
            assertNotNull(clientId);

            // call HTTP GET /api/clients with auth headers
            URL clientsUrl = new URL("http://localhost:" + httpPort + "/api/clients");
            HttpURLConnection conn = (HttpURLConnection) clientsUrl.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Authorization", "Bearer valid-token");
            conn.setRequestProperty("X-Client-Id", "test-client");
            assertEquals(200, conn.getResponseCode());
            try (BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                String body = r.lines().reduce((a,b) -> a+b).orElse("");
                JsonArray arr = gson.fromJson(body, JsonArray.class);
                boolean found = false;
                for (int i=0;i<arr.size();i++) {
                    JsonObject o = arr.get(i).getAsJsonObject();
                    if (clientId.equals(o.get("clientId").getAsString())) {
                        found = true;
                        assertTrue(o.get("connected").getAsBoolean());
                    }
                }
                assertTrue(found, "client should be listed by HTTP API");
            }

            // POST /api/send to this client with auth headers
            URL sendUrl = new URL("http://localhost:" + httpPort + "/api/send");
            HttpURLConnection pconn = (HttpURLConnection) sendUrl.openConnection();
            pconn.setRequestMethod("POST");
            pconn.setDoOutput(true);
            pconn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            pconn.setRequestProperty("Authorization", "Bearer valid-token");
            pconn.setRequestProperty("X-Client-Id", "test-client");
            JsonObject payload = new JsonObject();
            payload.addProperty("toClientId", clientId);
            payload.addProperty("content", "hello-via-http");
            payload.addProperty("fromClientId", "test-server");
            byte[] bytes = gson.toJson(payload).getBytes(StandardCharsets.UTF_8);
            pconn.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream os = pconn.getOutputStream()) { os.write(bytes); }
            int code = pconn.getResponseCode();
            assertTrue(code == 200 || code == 404);

            // client should receive the personal message
            String personal = messages.poll(3, TimeUnit.SECONDS);
            assertNotNull(personal, "expected personal message delivered by HTTP API");
            JsonObject personalObj = gson.fromJson(personal, JsonObject.class);
            assertEquals("personal", personalObj.get("type").getAsString());
            assertEquals("hello-via-http", personalObj.get("content").getAsString());

            session.close();
        } finally {
            try { httpApi.stop(); } catch (Exception ignored) {}
            try { wsServer.stop(); } catch (Exception ignored) {}
        }
    }

    // Simple in-memory replacement for RedisClientCache used in tests
    static class TestRedisClientCache extends RedisClientCache {
        private final Set<String> clients = Collections.synchronizedSet(new HashSet<>());
        private final Map<String, RedisClientCache.ClientInfo> infos = Collections.synchronizedMap(new HashMap<>());

        public TestRedisClientCache() {
            super("localhost", 6379); // won't be used
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
            for (var e : infos.entrySet()) {
                if (e.getValue().name.equals(name)) return java.util.Optional.of(e.getKey());
            }
            return java.util.Optional.empty();
        }

        @Override
        public void close() {
            clients.clear();
            infos.clear();
        }
    }
}
