package dev.orwell.objectstorage.proxy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.orwell.auth.http.client.HttpAuthenticationStrategy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class AdminAuthClientTest {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void loginExchangesCredentialsForAnAuthServerToken() throws IOException {
        AtomicReference<String> body = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/login", exchange -> {
            body.set(readBody(exchange));
            respond(exchange, 200, "{\"clientId\":\"admin-a\",\"token\":\"token-123\"}");
        });
        server.start();

        var result = newClient().login("admin-a", "secret");

        assertThat(result.success()).isTrue();
        assertThat(result.token()).isEqualTo("token-123");
        assertThat(body.get()).contains("admin-a");
    }

    @Test
    void loginSurfacesTheAuthServerStatusRatherThanACatchAllFailure() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/login", exchange -> respond(exchange, 401, "{}"));
        server.start();

        var result = newClient().login("admin-a", "wrong");

        assertThat(result.success()).isFalse();
        assertThat(result.statusCode()).isEqualTo(401);
    }

    @Test
    void anUnreachableAdminAuthServerIsA503RatherThanAnException() {
        AdminAuthClient client = new AdminAuthClient(
                new HttpAuthenticationStrategy("http://localhost:1"), auditLogger());

        var result = client.login("admin-a", "secret");

        assertThat(result.success()).isFalse();
        assertThat(result.statusCode()).isEqualTo(503);
    }

    @Test
    void sessionRoundTripsTheAdminIdAndIsCheckedAgainstTheAuthServer() throws IOException {
        AtomicReference<String> checkBody = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/tokens/check", exchange -> {
            checkBody.set(readBody(exchange));
            respond(exchange, 200, "{\"valid\":true,\"clientId\":\"admin-a\"}");
        });
        server.start();

        String session = AdminAuthClient.session("admin-a", "token-123");

        assertThat(newClient().signedInAdmin(session)).contains("admin-a");
        // The token half survives verbatim, so a token containing the separator still checks out.
        assertThat(checkBody.get()).contains("token-123");
    }

    @Test
    void aTokenContainingTheSeparatorIsNotTruncated() throws IOException {
        AtomicReference<String> checkBody = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/tokens/check", exchange -> {
            checkBody.set(readBody(exchange));
            respond(exchange, 200, "{\"valid\":true,\"clientId\":\"admin-a\"}");
        });
        server.start();

        newClient().signedInAdmin(AdminAuthClient.session("admin-a", "aaa.bbb.ccc"));

        assertThat(checkBody.get()).contains("aaa.bbb.ccc");
    }

    @Test
    void aRejectedOrMalformedSessionIsNotSignedIn() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/tokens/check", exchange -> respond(exchange, 200, "{\"valid\":false,\"clientId\":null}"));
        server.start();
        AdminAuthClient client = newClient();

        assertThat(client.signedInAdmin(AdminAuthClient.session("admin-a", "stale"))).isEmpty();
        assertThat(client.signedInAdmin(null)).isEmpty();
        assertThat(client.signedInAdmin("")).isEmpty();
        assertThat(client.signedInAdmin("no-separator")).isEmpty();
        assertThat(client.signedInAdmin("!!!not-base64!!!.token")).isEmpty();
        assertThat(client.signedInAdmin("." + "token")).isEmpty();
    }

    @Test
    void anUnreachableAdminAuthServerReadsAsSignedOutRatherThanFailing() {
        AdminAuthClient client = new AdminAuthClient(
                new HttpAuthenticationStrategy("http://localhost:1"), auditLogger());

        assertThat(client.signedInAdmin(AdminAuthClient.session("admin-a", "token-123"))).isEmpty();
    }

    private AdminAuthClient newClient() {
        return new AdminAuthClient(new HttpAuthenticationStrategy(baseUrl()), auditLogger());
    }

    private String baseUrl() {
        return "http://localhost:%d".formatted(server.getAddress().getPort());
    }

    private static FileAuditLogger auditLogger() {
        try {
            return new FileAuditLogger(new ProxyProperties(
                    new ProxyProperties.Storage("aws", 1L),
                    new ProxyProperties.S3("bucket", "us-east-1", null, false),
                    new ProxyProperties.Azure(null, null, null, null),
                    new ProxyProperties.AuthServer("http://localhost:8081", "provisioning-key"),
                    new ProxyProperties.AdminAuth("http://localhost:8082"),
                    new ProxyProperties.Cors(List.of()),
                    new ProxyProperties.Server("http://localhost"),
                    new ProxyProperties.Logging(Files.createTempFile("admin-audit-", ".log").toString())
            ));
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        try (InputStream inputStream = exchange.getRequestBody()) {
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void respond(HttpExchange exchange, int statusCode, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream outputStream = exchange.getResponseBody()) {
            outputStream.write(bytes);
        }
    }
}
