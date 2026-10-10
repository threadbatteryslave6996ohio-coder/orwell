package dev.orwell.objectstorage.proxy;

import dev.orwell.auth.http.client.HttpAuthenticationStrategy;
import dev.orwell.testing.http.JsonStubServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static dev.orwell.testing.http.JsonStubServer.readBody;
import static dev.orwell.testing.http.JsonStubServer.respond;
import static org.assertj.core.api.Assertions.assertThat;

class AdminAuthClientTest {
    private JsonStubServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.close();
        }
    }

    @Test
    void loginExchangesCredentialsForAnAuthServerToken() throws IOException {
        AtomicReference<String> body = new AtomicReference<>();
        server = JsonStubServer.start();
        server.on("/def/login", exchange -> {
            body.set(readBody(exchange));
            respond(exchange, 200, "{\"clientId\":\"admin-a\",\"token\":\"token-123\"}");
        });

        var result = newClient().login("admin-a", "secret");

        assertThat(result.success()).isTrue();
        assertThat(result.token()).isEqualTo("token-123");
        assertThat(body.get()).contains("admin-a");
    }

    @Test
    void loginSurfacesTheAuthServerStatusRatherThanACatchAllFailure() throws IOException {
        server = JsonStubServer.start();
        server.on("/def/login", exchange -> respond(exchange, 401, "{}"));

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
        server = JsonStubServer.start();
        server.on("/def/tokens/check", exchange -> {
            checkBody.set(readBody(exchange));
            respond(exchange, 200, "{\"valid\":true,\"clientId\":\"admin-a\"}");
        });

        String session = AdminAuthClient.session("admin-a", "token-123");

        assertThat(newClient().signedInAdmin(session)).contains("admin-a");
        // The token half survives verbatim, so a token containing the separator still checks out.
        assertThat(checkBody.get()).contains("token-123");
    }

    @Test
    void aTokenContainingTheSeparatorIsNotTruncated() throws IOException {
        AtomicReference<String> checkBody = new AtomicReference<>();
        server = JsonStubServer.start();
        server.on("/def/tokens/check", exchange -> {
            checkBody.set(readBody(exchange));
            respond(exchange, 200, "{\"valid\":true,\"clientId\":\"admin-a\"}");
        });

        newClient().signedInAdmin(AdminAuthClient.session("admin-a", "aaa.bbb.ccc"));

        assertThat(checkBody.get()).contains("aaa.bbb.ccc");
    }

    @Test
    void aRejectedOrMalformedSessionIsNotSignedIn() throws IOException {
        server = JsonStubServer.start();
        server.on("/def/tokens/check", exchange -> respond(exchange, 200, "{\"valid\":false,\"clientId\":null}"));
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
        return new AdminAuthClient(new HttpAuthenticationStrategy(server.baseUrl()), auditLogger());
    }


    private static FileAuditLogger auditLogger() {
        try {
            return new FileAuditLogger(new ProxyProperties(
                    new ProxyProperties.Storage("aws", 1L),
                    new ProxyProperties.S3("bucket", "us-east-1", null, false),
                    new ProxyProperties.Azure(null, null, null, null),
                    new ProxyProperties.AuthServer("http://localhost:8081", "def", "ops-admin", "admin-secret"),
                    new ProxyProperties.AdminAuth("http://localhost:8082", "def"),
                    new ProxyProperties.Cors(List.of()),
                    new ProxyProperties.Server("http://localhost"),
                    new ProxyProperties.Logging(Files.createTempFile("admin-audit-", ".log").toString())
            ));
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }


}
