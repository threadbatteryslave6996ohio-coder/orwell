package dev.orwell.keeboarder.server.http;

import dev.orwell.auth.AuthenticationStrategy;
import dev.orwell.keeboarder.server.service.KeeboarderService;
import dev.orwell.env.http.HttpExchangeResponses;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Objects;
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

    static class ClientsHandler implements HttpHandler {
        private final KeeboarderService service;
        private final AuthenticationStrategy authenticator;

        ClientsHandler(KeeboarderService service, AuthenticationStrategy authenticator) {
            this.service = service;
            this.authenticator = authenticator;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!HttpExchangeResponses.requireMethod(exchange, "GET")) {
                return;
            }
            String authorization = exchange.getRequestHeaders().getFirst("Authorization");
            String clientId = exchange.getRequestHeaders().getFirst("X-Client-Id");
            if (!KeeboarderRequestAuth.isAuthenticated(authenticator, clientId, authorization)) {
                HttpExchangeResponses.sendStatus(exchange, 401);
                return;
            }
            String resp = service.connectedClientsJson();
            HttpExchangeResponses.writeUtf8(exchange, 200, resp, "application/json; charset=utf-8");
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
            if (!HttpExchangeResponses.requireMethod(exchange, "POST")) {
                return;
            }

            String authorization = exchange.getRequestHeaders().getFirst("Authorization");
            String clientId = exchange.getRequestHeaders().getFirst("X-Client-Id");
            if (!KeeboarderRequestAuth.isAuthenticated(authenticator, clientId, authorization)) {
                HttpExchangeResponses.sendStatus(exchange, 401);
                return;
            }

            String body;
            try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(exchange.getRequestBody(), java.nio.charset.StandardCharsets.UTF_8))) {
                body = r.lines().collect(Collectors.joining("\n"));
            }

            JsonObject req;
            try {
                req = gson.fromJson(body, JsonObject.class);
            } catch (Exception e) {
                HttpExchangeResponses.sendStatus(exchange, 400);
                return;
            }

            String toClientId = req.has("toClientId") ? req.get("toClientId").getAsString() : null;
            String content = req.has("content") ? req.get("content").getAsString() : null;
            if (toClientId == null || content == null) {
                HttpExchangeResponses.sendStatus(exchange, 400);
                return;
            }

            String fromClientId = resolveSenderClientId(req, clientId);
            boolean ok = service.send(toClientId, fromClientId, content);
            JsonObject res = new JsonObject();
            if (ok) {
                res.addProperty("status", "sent");
                HttpExchangeResponses.writeUtf8(exchange, 200, gson.toJson(res), "application/json; charset=utf-8");
            } else {
                res.addProperty("status", "failed");
                res.addProperty("reason", "target_not_connected");
                HttpExchangeResponses.writeUtf8(exchange, 404, gson.toJson(res), "application/json; charset=utf-8");
            }
        }

        private String resolveSenderClientId(JsonObject request, String authenticatedClientId) {
            if (authenticator != null) {
                return authenticatedClientId;
            }
            if (request.has("fromClientId")) {
                String requestedSender = request.get("fromClientId").getAsString();
                if (requestedSender != null && !requestedSender.isBlank()) {
                    return requestedSender;
                }
            }
            return Objects.requireNonNullElse(authenticatedClientId, "server");
        }
    }
}
