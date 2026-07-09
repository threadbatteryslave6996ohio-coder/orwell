package dev.orwell.keeboarder.server;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import jakarta.websocket.ClientEndpoint;
import jakarta.websocket.ContainerProvider;
import jakarta.websocket.OnMessage;
import jakarta.websocket.Session;
import jakarta.websocket.WebSocketContainer;
import org.glassfish.tyrus.server.Server;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class HttpApiNoAuthIntegrationTest {
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
    void unauthenticatedHttpSendPreservesProvidedSenderIdentity() throws Exception {
        int wsPort = 8033;
        int httpPort = 8084;
        HttpApiIntegrationTest.TestRedisClientCache cache = new HttpApiIntegrationTest.TestRedisClientCache();

        ChatEndpoint.initialize(cache, (clientId, token) -> true);

        Server wsServer = new Server("localhost", wsPort, "/ws", null, ChatEndpoint.class);
        HttpApiServer httpApi = new HttpApiServer("localhost", httpPort, new KeeboarderService());

        try {
            wsServer.start();
            httpApi.start();

            LinkedBlockingQueue<String> messages = new LinkedBlockingQueue<>();
            WebSocketContainer container = ContainerProvider.getWebSocketContainer();
            URI uri = new URI("ws://localhost:" + wsPort + "/ws/chat");
            Session session = container.connectToServer(new TestClient(messages), uri);

            String sys = messages.poll(3, TimeUnit.SECONDS);
            assertNotNull(sys, "expected system message on connect");

            JsonObject reg = new JsonObject();
            reg.addProperty("type", "register");
            reg.addProperty("clientId", "test-client");
            reg.addProperty("name", "test-client");
            reg.addProperty("token", "ignored");
            session.getBasicRemote().sendText(GSON.toJson(reg));

            String registered = messages.poll(3, TimeUnit.SECONDS);
            assertNotNull(registered, "expected registered message");

            URL sendUrl = new URL("http://localhost:" + httpPort + "/api/send");
            HttpURLConnection connection = (HttpURLConnection) sendUrl.openConnection();
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");

            JsonObject payload = new JsonObject();
            payload.addProperty("toClientId", "test-client");
            payload.addProperty("content", "hello-via-http");
            payload.addProperty("fromClientId", "test-server");
            byte[] bytes = GSON.toJson(payload).getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream os = connection.getOutputStream()) {
                os.write(bytes);
            }
            assertEquals(200, connection.getResponseCode());

            String personal = messages.poll(3, TimeUnit.SECONDS);
            assertNotNull(personal, "expected personal message delivered by HTTP API");
            JsonObject personalObj = GSON.fromJson(personal, JsonObject.class);
            assertEquals("personal", personalObj.get("type").getAsString());
            assertEquals("test-server", personalObj.get("fromClientId").getAsString());
            assertEquals("hello-via-http", personalObj.get("content").getAsString());

            session.close();
        } finally {
            try {
                httpApi.stop();
            } catch (Exception ignored) {
            }
            try {
                wsServer.stop();
            } catch (Exception ignored) {
            }
            cache.close();
        }
    }
}
