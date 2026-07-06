package dev.orwell.keeboarder.server;

import dev.orwell.auth.AuthenticationStrategy;
import dev.orwell.auth.BearerToken;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

public class HttpApiServer {
    private final HttpServer server;
    private static final Gson gson = new Gson();

    public HttpApiServer(String host, int port) throws IOException {
        this(host, port, new KeeboarderService(), null);
    }

    public HttpApiServer(String host, int port, KeeboarderService service) throws IOException {
        this(host, port, service, null);
    }

    public HttpApiServer(String host, int port, KeeboarderService service, AuthenticationStrategy authenticator) throws IOException {
        server = HttpServer.create(new InetSocketAddress(host, port), 0);
        server.createContext("/api/clients", new ClientsHandler(service, authenticator));
        server.createContext("/api/send", new SendHandler(service, authenticator));
        server.setExecutor(null);
    }

    public void start() {
        server.start();
    }

    public void stop() {
        server.stop(0);
    }

    private static boolean isAuthenticated(String clientId, String authorization, AuthenticationStrategy authenticator) {
        if (authenticator == null) {
            return true;
        }
        if (clientId == null || clientId.isBlank()) {
            return false;
        }
        String token = BearerToken.extract(authorization);
        return token != null && authenticator.isTokenValidForClient(clientId, token);
    }

    static class ClientsHandler implements HttpHandler {
        private final KeeboarderService service;
        private final AuthenticationStrategy authenticator;

        ClientsHandler(KeeboarderService service, AuthenticationStrategy authenticator) {
            this.service = service;
            this.authenticator = authenticator;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            String authorization = exchange.getRequestHeaders().getFirst("Authorization");
            String clientId = exchange.getRequestHeaders().getFirst("X-Client-Id");
            if (!isAuthenticated(clientId, authorization, authenticator)) {
                exchange.sendResponseHeaders(401, -1);
                return;
            }
            String resp = service.connectedClientsJson();
            byte[] bytes = resp.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }
    }

    static class SendHandler implements HttpHandler {
        private final KeeboarderService service;
        private final AuthenticationStrategy authenticator;

        SendHandler(KeeboarderService service, AuthenticationStrategy authenticator) {
            this.service = service;
            this.authenticator = authenticator;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }

            String authorization = exchange.getRequestHeaders().getFirst("Authorization");
            String clientId = exchange.getRequestHeaders().getFirst("X-Client-Id");
            if (!isAuthenticated(clientId, authorization, authenticator)) {
                exchange.sendResponseHeaders(401, -1);
                return;
            }

            String body;
            try (BufferedReader r = new BufferedReader(new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8))) {
                body = r.lines().collect(Collectors.joining("\n"));
            }

            JsonObject req;
            try {
                req = gson.fromJson(body, JsonObject.class);
            } catch (Exception e) {
                exchange.sendResponseHeaders(400, -1);
                return;
            }

            String toClientId = req.has("toClientId") ? req.get("toClientId").getAsString() : null;
            String content = req.has("content") ? req.get("content").getAsString() : null;
            String fromClientId = req.has("fromClientId") ? req.get("fromClientId").getAsString() : "server";

            if (toClientId == null || content == null) {
                exchange.sendResponseHeaders(400, -1);
                return;
            }

            boolean ok = service.send(toClientId, fromClientId, content);
            JsonObject res = new JsonObject();
            if (ok) {
                res.addProperty("status", "sent");
                byte[] bytes = gson.toJson(res).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
            } else {
                res.addProperty("status", "failed");
                res.addProperty("reason", "target_not_connected");
                byte[] bytes = gson.toJson(res).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
                exchange.sendResponseHeaders(404, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
            }
        }
    }
}
