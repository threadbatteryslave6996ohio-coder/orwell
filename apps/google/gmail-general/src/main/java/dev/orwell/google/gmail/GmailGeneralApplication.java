package dev.orwell.google.gmail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.orwell.auth.http.api.LoginHttpResponse;
import dev.orwell.auth.http.client.HttpAuthenticationStrategy;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.Executors;

/** Gmail mailbox receiver. Gmail notifications arrive at /gmail/pubsub. */
public final class GmailGeneralApplication {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final HttpAuthenticationStrategy AUTH = new HttpAuthenticationStrategy(env("AUTH_SERVER_URL", "http://127.0.0.1:8081"));
    private static final String AUTH_CLIENT_ID = env("AUTH_CLIENT_ID", "gmail-general");
    private static final String AUTH_CLIENT_SECRET = env("AUTH_CLIENT_SECRET", "");
    private static final Path STORE = Path.of(env("GMAIL_STORE_DIR", "./data/gmail"));
    private static String currentAccessToken = env("GMAIL_ACCESS_TOKEN", "");
    private static final String REFRESH_TOKEN = env("GMAIL_REFRESH_TOKEN", "");
    private static final List<String> CLIENTS = clients();

    public static void main(String[] args) throws Exception {
        Files.createDirectories(STORE);
        HttpServer server = HttpServer.create(new InetSocketAddress(env("GMAIL_SERVER_HOST", "127.0.0.1"),
                Integer.parseInt(env("GMAIL_SERVER_PORT", "9100"))), 0);
        server.createContext("/health", GmailGeneralApplication::health);
        server.createContext("/gmail/pubsub", GmailGeneralApplication::pubsub);
        server.createContext("/gmail/watch", GmailGeneralApplication::watch);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        System.out.println("gmail-general listening on " + server.getAddress());
    }

    private static void health(HttpExchange x) throws IOException { respond(x, 200, "{\"status\":\"ok\"}"); }

    private static void pubsub(HttpExchange x) throws IOException {
        if (!"POST".equalsIgnoreCase(x.getRequestMethod())) { respond(x, 405, ""); return; }
        try {
            JsonNode envelope = JSON.readTree(x.getRequestBody());
            String encoded = envelope.path("message").path("data").asText("");
            if (!encoded.isBlank()) {
                JsonNode notice = JSON.readTree(Base64.getUrlDecoder().decode(encoded));
                sync(notice.path("historyId").asLong(0));
            }
            respond(x, 200, "");
        } catch (Exception e) {
            e.printStackTrace();
            respond(x, 500, "{\"error\":\"processing failed\"}");
        }
    }

    private static void sync(long historyId) throws Exception {
        long previous = Files.exists(STORE.resolve(".history"))
                ? Long.parseLong(Files.readString(STORE.resolve(".history")).trim()) : historyId - 1;
        String page = "";
        do {
            String url = "https://gmail.googleapis.com/gmail/v1/users/me/history?startHistoryId=" + previous;
            if (!page.isBlank()) url += "&pageToken=" + page;
            JsonNode history = gmail(url);
            for (JsonNode item : history.path("history")) {
                for (JsonNode added : item.path("messagesAdded")) {
                    String id = added.path("message").path("id").asText();
                    if (!id.isBlank()) save(gmail("https://gmail.googleapis.com/gmail/v1/users/me/messages/" + id + "?format=full"));
                }
            }
            page = history.path("nextPageToken").asText("");
        } while (!page.isBlank());
        Files.writeString(STORE.resolve(".history"), Long.toString(historyId));
    }

    private static void save(JsonNode raw) throws Exception {
        GmailMessage message = new GmailMessage(raw.path("id").asText(), raw.path("threadId").asText(),
                header(raw, "Subject"), header(raw, "From"), header(raw, "To"),
                raw.path("internalDate").asLong(Instant.now().toEpochMilli()), textPart(raw.path("payload")));
        Path file = STORE.resolve(message.id() + ".json");
        boolean newMessage = Files.notExists(file);
        JSON.writeValue(file.toFile(), message);
        if (!newMessage) return;
        ObjectNode event = JSON.valueToTree(message);
        for (String client : CLIENTS) {
            try {
                LoginHttpResponse auth = AUTH.login(AUTH_CLIENT_ID, AUTH_CLIENT_SECRET);
                HTTP.send(HttpRequest.newBuilder(URI.create(client)).header("Content-Type", "application/json")
                        .header("X-Client-Id", auth.clientId()).header("Authorization", "Bearer " + auth.token())
                        .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(event))).build(),
                        HttpResponse.BodyHandlers.discarding());
            } catch (Exception e) { System.err.println("Could not notify " + client + ": " + e.getMessage()); }
        }
    }

    private static void watch(HttpExchange x) throws IOException {
        if (!authorized(x)) { respond(x, 401, "{\"error\":\"authentication required\"}"); return; }
        try {
            String topic = env("GMAIL_PUBSUB_TOPIC", "");
            if (topic.isBlank()) { respond(x, 400, "{\"error\":\"GMAIL_PUBSUB_TOPIC is required\"}"); return; }
            ObjectNode body = JSON.createObjectNode().put("topicName", topic);
            body.putArray("labelIds").add("INBOX");
            JsonNode result = gmail("https://gmail.googleapis.com/gmail/v1/users/me/watch", body);
            if (result.has("historyId")) Files.writeString(STORE.resolve(".history"), result.path("historyId").asText());
            respond(x, 200, JSON.writeValueAsString(result));
        } catch (Exception e) { respond(x, 500, "{\"error\":\"watch failed\"}"); }
    }

    private static JsonNode gmail(String url) throws Exception { return gmail(url, null); }
    private static JsonNode gmail(String url, JsonNode body) throws Exception {
        if (currentAccessToken.isBlank() && REFRESH_TOKEN.isBlank()) throw new IllegalStateException("Set GMAIL_ACCESS_TOKEN or GMAIL_REFRESH_TOKEN");
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).header("Authorization", "Bearer " + accessToken())
                .header("Content-Type", "application/json");
        if (body != null) b.POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))); else b.GET();
        HttpResponse<String> r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() == 401 && !REFRESH_TOKEN.isBlank()) {
            currentAccessToken = refreshAccessToken();
            b = HttpRequest.newBuilder(URI.create(url)).header("Authorization", "Bearer " + currentAccessToken)
                    .header("Content-Type", "application/json");
            if (body != null) b.POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))); else b.GET();
            r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
        }
        if (r.statusCode() >= 300) throw new IOException("Gmail API " + r.statusCode() + ": " + r.body());
        return JSON.readTree(r.body());
    }

    private static synchronized String accessToken() throws Exception {
        if (!currentAccessToken.isBlank()) return currentAccessToken;
        currentAccessToken = refreshAccessToken();
        return currentAccessToken;
    }

    private static String refreshAccessToken() throws Exception {
        String form = "client_id=" + enc(env("GMAIL_CLIENT_ID", ""))
                + "&client_secret=" + enc(env("GMAIL_CLIENT_SECRET", ""))
                + "&refresh_token=" + enc(REFRESH_TOKEN) + "&grant_type=refresh_token";
        HttpRequest request = HttpRequest.newBuilder(URI.create("https://oauth2.googleapis.com/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)).build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 300) throw new IOException("OAuth refresh failed: " + response.body());
        String token = JSON.readTree(response.body()).path("access_token").asText("");
        if (token.isBlank()) throw new IOException("OAuth refresh response did not contain access_token");
        return token;
    }

    private static String enc(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }

    private static boolean authorized(HttpExchange exchange) {
        String clientId = exchange.getRequestHeaders().getFirst("X-Client-Id");
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        String token = authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7)
                ? authorization.substring(7).trim() : "";
        if (clientId == null || clientId.isBlank() || token.isBlank()) return false;
        try { return AUTH.isTokenValidForClient(clientId, token); } catch (RuntimeException e) { return false; }
    }

    private static String header(JsonNode raw, String name) { for (JsonNode h : raw.path("payload").path("headers")) if (name.equalsIgnoreCase(h.path("name").asText())) return h.path("value").asText(); return ""; }
    private static String textPart(JsonNode node) { if (node.path("mimeType").asText().equals("text/plain") && node.has("body")) return decode(node.path("body").path("data").asText()); for (JsonNode p : node.path("parts")) { String s = textPart(p); if (!s.isBlank()) return s; } return ""; }
    private static String decode(String value) { try { return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8); } catch (Exception e) { return ""; } }
    private static List<String> clients() { return new ArrayList<>(List.of(env("GMAIL_WEBHOOK_CLIENTS", "").split(","))).stream().map(String::trim).filter(s -> !s.isBlank()).toList(); }
    private static String env(String key, String fallback) { return System.getenv().getOrDefault(key, fallback); }
    private static void respond(HttpExchange x, int status, String body) throws IOException { byte[] bytes = body.getBytes(StandardCharsets.UTF_8); x.sendResponseHeaders(status, bytes.length); try (OutputStream out = x.getResponseBody()) { out.write(bytes); } }
}
