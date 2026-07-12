package dev.orwell.analyzer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.orwell.google.gmail.GmailMessage;
import dev.orwell.auth.http.client.HttpAuthenticationStrategy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.Executors;

public final class AnalyzerApplication {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpAuthenticationStrategy AUTH = new HttpAuthenticationStrategy(System.getenv().getOrDefault("AUTH_SERVER_URL", "http://127.0.0.1:8081"));
    public static void main(String[] args) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(System.getenv().getOrDefault("ANALYZER_HOST", "127.0.0.1"), Integer.parseInt(System.getenv().getOrDefault("ANALYZER_PORT", "9200"))), 0);
        server.createContext("/health", x -> respond(x, 200, "{\"status\":\"ok\"}"));
        server.createContext("/analyzer/email", AnalyzerApplication::email);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor()); server.start();
        System.out.println("analyzer listening on " + server.getAddress());
    }
    private static void email(HttpExchange x) throws IOException {
        if (!authorized(x)) { respond(x, 401, "{\"error\":\"authentication required\"}"); return; }
        GmailMessage mail = JSON.readValue(x.getRequestBody(), GmailMessage.class);
        boolean match = mail.subject().toLowerCase(Locale.ROOT).contains("login");
        System.out.println((match ? "LOGIN MATCH: " : "email: ") + mail.subject() + " <" + mail.from() + ">");
        respond(x, 200, JSON.writeValueAsString(new Result(match, mail.id(), mail.subject())));
    }
    private record Result(boolean containsLogin, String id, String subject) {}
    private static boolean authorized(HttpExchange exchange) {
        String clientId = exchange.getRequestHeaders().getFirst("X-Client-Id");
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        String token = authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7)
                ? authorization.substring(7).trim() : "";
        if (clientId == null || clientId.isBlank() || token.isBlank()) return false;
        try { return AUTH.isTokenValidForClient(clientId, token); } catch (RuntimeException e) { return false; }
    }
    private static void respond(HttpExchange x, int status, String body) throws IOException { byte[] b = body.getBytes(StandardCharsets.UTF_8); x.sendResponseHeaders(status, b.length); try (var out = x.getResponseBody()) { out.write(b); } }
}
