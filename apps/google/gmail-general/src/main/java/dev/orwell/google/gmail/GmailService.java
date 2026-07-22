package dev.orwell.google.gmail;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.orwell.auth.http.api.LoginHttpResponse;
import dev.orwell.auth.http.client.HttpAuthenticationStrategy;
import dev.orwell.bootstrap.web.SharedJson;
import dev.orwell.logging.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Persists each received mailbox message to the local store and fans it out to the configured
 * webhook clients, authenticating every delivery with a bearer token from the auth server. The
 * ingestion side (reading the mailbox) lives in {@link ImapMailListener}; this service owns only
 * the store-and-forward half.
 */
@Service
public class GmailService {
    private final ObjectMapper json = SharedJson.mapper();
    private final HttpClient http = HttpClient.newHttpClient();
    private final HttpAuthenticationStrategy auth;
    private final String authClientId;
    private final String authClientSecret;
    private final Path store;
    private final List<String> clients;
    private final Logger logger;
    // One login is cached and reused across deliveries; a 401 clears it so the next call re-logs in.
    private volatile LoginHttpResponse login;

    public GmailService(
            @Value("${orwell.auth.base-url}") String authBaseUrl,
            @Value("${gmail.auth.client-id}") String authClientId,
            @Value("${gmail.auth.client-secret}") String authClientSecret,
            @Value("${gmail.store-dir}") String storeDir,
            @Value("${gmail.webhook-clients}") String webhookClients,
            Logger logger
    ) throws IOException {
        this.logger = Objects.requireNonNull(logger, "logger");
        this.auth = new HttpAuthenticationStrategy(authBaseUrl);
        this.authClientId = authClientId;
        this.authClientSecret = authClientSecret;
        this.store = Path.of(storeDir);
        this.clients = Arrays.stream(webhookClients.split(","))
                .map(String::trim).filter(value -> !value.isBlank()).toList();
        Files.createDirectories(store);
    }

    /**
     * Saves the message as {@code <id>.json} and, if it was not already stored, forwards it to every
     * webhook client. The stored-file check is the dedup key, so a redelivered message is written
     * once and fanned out once.
     */
    public void deliver(GmailMessage message) throws IOException {
        Path file = store.resolve(fileName(message.id()));
        boolean newMessage = Files.notExists(file);
        json.writeValue(file.toFile(), message);
        if (!newMessage) {
            return;
        }
        String payload = json.writeValueAsString(message);
        for (String client : clients) {
            try {
                HttpResponse<Void> response = postWebhook(client, payload);
                if (response.statusCode() == 401) {
                    // The cached token may have expired: refresh once and retry.
                    invalidateLogin();
                    response = postWebhook(client, payload);
                }
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    logger.error("Webhook rejected Gmail message.", Map.of(
                            "client", client,
                            "messageId", message.id(),
                            "statusCode", response.statusCode()));
                }
            } catch (Exception exception) {
                // getMessage() is null for plenty of exception types, so this map must accept nulls.
                Map<String, Object> metadata = new LinkedHashMap<>();
                metadata.put("client", client);
                metadata.put("messageId", message.id());
                metadata.put("error", exception.getMessage());
                logger.error("Could not notify webhook client of Gmail message.", metadata);
            }
        }
    }

    private HttpResponse<Void> postWebhook(String client, String payload) throws Exception {
        LoginHttpResponse authenticated = login();
        return http.send(HttpRequest.newBuilder(URI.create(client))
                        .header("Content-Type", "application/json")
                        .header("X-Client-Id", authenticated.clientId())
                        .header("Authorization", "Bearer " + authenticated.token())
                        .POST(HttpRequest.BodyPublishers.ofString(payload)).build(),
                HttpResponse.BodyHandlers.discarding());
    }

    private synchronized LoginHttpResponse login() throws Exception {
        if (login == null) {
            login = auth.login(authClientId, authClientSecret);
        }
        return login;
    }

    private synchronized void invalidateLogin() {
        login = null;
    }

    // Message-IDs contain characters that are not safe in a file name (<, >, @); map anything
    // outside a conservative set to '_' so the store path is always valid. The id inside the JSON
    // body is left untouched — that is the value webhook clients receive.
    private static String fileName(String id) {
        String cleaned = id.replaceAll("[^A-Za-z0-9._-]", "_");
        return (cleaned.isBlank() ? "message" : cleaned) + ".json";
    }
}
