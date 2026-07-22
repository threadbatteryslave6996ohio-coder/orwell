package dev.orwell.clients.dummy;

import dev.orwell.clients.core.ClipboardApiClient;
import dev.orwell.clients.core.ClipboardEntry;
import dev.orwell.clients.core.ClientAuthInitializer;
import dev.orwell.clients.core.ClientConfig;
import dev.orwell.clients.core.ClientIdentity;
import dev.orwell.clients.core.ExceptionMessages;
import dev.orwell.auth.http.client.ClientAuthSession;
import dev.orwell.clients.core.env.ClientEnvs;
import dev.orwell.env.Env;
import dev.orwell.logging.ConsoleLogger;
import dev.orwell.logging.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.StringJoiner;

public final class DummyClientApp {
    private final ClipboardApiClient apiClient;
    private final URI endpoint;
    private final String clientId;
    private final Logger logger;

    private DummyClientApp(URI endpoint, String clientId, ClientAuthSession authSession, Logger logger) {
        this(new ClipboardApiClient(endpoint, authSession, Duration.ofSeconds(10)), endpoint, clientId, logger);
    }

    DummyClientApp(ClipboardApiClient apiClient, URI endpoint, String clientId, Logger logger) {
        this.apiClient = apiClient;
        this.endpoint = endpoint;
        this.clientId = clientId;
        this.logger = logger;
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        Logger logger = new ConsoleLogger("klippy-dummy-client");
        Env env = ClientEnvs.load();
        ClientConfig config = ClientConfig.load(env, "dummy-" + ClientIdentity.hostnameOrRandom(""));
        ClientAuthInitializer.initialize(config.authSession(), config, logger);

        DummyClientApp app =
                new DummyClientApp(config.endpoint(), config.clientId(), config.authSession(), logger);

        if (args.length > 0) {
            if (!app.sendCommand(joinArgs(args))) {
                System.exit(1);
            }
            return;
        }

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String command;
            while ((command = reader.readLine()) != null) {
                if (!command.isBlank()) {
                    app.sendCommand(command);
                }
            }
        }
    }

    boolean sendCommand(String command) {
        try {
            int statusCode = apiClient.create(new ClipboardEntry(clientId, command, Instant.now())).statusCode();
            if (statusCode < 200 || statusCode >= 300) {
                logger.error("Remote server rejected command.", Map.of(
                        "endpoint", String.valueOf(endpoint),
                        "httpStatus", statusCode));
                return false;
            }

            logger.info("Sent command.", Map.of("clientId", clientId, "chars", command.length()));
            return true;
        } catch (IOException exception) {
            logger.error("Cannot reach remote server.", failure(ExceptionMessages.message(exception)));
            return false;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            logger.error("Interrupted while sending command.", Map.of("endpoint", String.valueOf(endpoint)));
            return false;
        } catch (RuntimeException exception) {
            // A missing/expired token or a failed auth refresh surfaces here (e.g.
            // HttpAuthenticationException or IllegalStateException from ClipboardApiClient). Report it
            // like any other send failure instead of terminating the interactive client.
            logger.error("Cannot authenticate command.", failure(ExceptionMessages.messageWithCause(exception)));
            return false;
        }
    }

    private Map<String, Object> failure(String message) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("endpoint", String.valueOf(endpoint));
        metadata.put("error", message);
        return metadata;
    }

    private static String joinArgs(String[] args) {
        StringJoiner joiner = new StringJoiner(" ");
        for (String arg : args) {
            joiner.add(arg);
        }
        return joiner.toString();
    }

}
