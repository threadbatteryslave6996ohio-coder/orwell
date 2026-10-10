package dev.orwell.objectstorage.proxy;

import dev.orwell.auth.http.api.AuthWorkspaces;
import dev.orwell.auth.http.client.ClientAuthSession;
import dev.orwell.auth.http.client.HttpAuthenticationStrategy;
import dev.orwell.testing.http.JsonStubServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static dev.orwell.testing.http.JsonStubServer.respond;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The panel's provisioning path, and above all <em>whose</em> credentials a failure is about.
 *
 * <p>Two different things can answer 401 here: the proxy's own admin login, and the identities
 * endpoint refusing the proxy's token. Neither is anything the person using the panel typed, so
 * both have to reach them as a 503 pointing at the deployment's configuration. Only a failure of
 * the identity they asked for — a name that already exists — is theirs to fix.
 */
class AuthServerClientTest {
    private JsonStubServer authServer;

    @AfterEach
    void stopServer() {
        if (authServer != null) {
            authServer.close();
        }
    }

    @Test
    void adminCredentialsTheServerRefusesReadAsAConfigurationProblemNotAWrongPassword() throws IOException {
        authServer = JsonStubServer.start();
        // The proxy's own admin login is what fails; the identities endpoint is never reached.
        authServer.on("/admin/login", exchange -> respond(exchange, 401, "{}"));

        var result = clientWithAdminSecret("wrong-secret").createIdentity("uploader", "s3cr3t-value");

        assertThat(result.success()).isFalse();
        assertThat(result.statusCode())
                .as("401 here would tell the panel operator their own password was rejected")
                .isEqualTo(503);
    }

    @Test
    void anAdminIdentityWithoutProvisioningRightsAlsoReadsAsAConfigurationProblem() throws IOException {
        AtomicInteger creates = new AtomicInteger();
        authServer = JsonStubServer.start();
        authServer.on("/admin/login", exchange ->
                respond(exchange, 200, "{\"clientId\":\"ops-admin\",\"token\":\"token-123\"}"));
        // Logs in fine, but the identities endpoint refuses the token it was given — twice, so the
        // one retry is spent and the second refusal is what the caller sees.
        authServer.on("/def/identities", exchange -> {
            creates.incrementAndGet();
            respond(exchange, 401, "{}");
        });

        var result = clientWithAdminSecret("admin-secret").createIdentity("uploader", "s3cr3t-value");

        assertThat(result.statusCode()).isEqualTo(503);
        assertThat(creates.get()).as("exactly one retry, never a login loop").isEqualTo(2);
    }

    /** The one failure that <em>is</em> about what the operator typed keeps the server's answer. */
    @Test
    void aClientIdThatAlreadyExistsKeepsItsOwnStatus() throws IOException {
        authServer = JsonStubServer.start();
        authServer.on("/admin/login", exchange ->
                respond(exchange, 200, "{\"clientId\":\"ops-admin\",\"token\":\"token-123\"}"));
        authServer.on("/def/identities", exchange -> respond(exchange, 409, "{}"));

        var result = clientWithAdminSecret("admin-secret").createIdentity("uploader", "s3cr3t-value");

        assertThat(result.success()).isFalse();
        assertThat(result.statusCode()).isEqualTo(409);
    }

    @Test
    void noAdminCredentialsAtAllIsAlreadyAConfigurationProblem() throws IOException {
        authServer = JsonStubServer.start();

        var result = clientWithAdminSecret(null).createIdentity("uploader", "s3cr3t-value");

        assertThat(result.statusCode()).isEqualTo(503);
    }

    private AuthServerClient clientWithAdminSecret(String adminSecret) {
        return new AuthServerClient(
                new HttpAuthenticationStrategy(authServer.baseUrl(), AuthWorkspaces.DEFAULT),
                new ClientAuthSession(
                        authServer.baseUrl(), AuthWorkspaces.ADMIN, "ops-admin", adminSecret, null),
                auditLogger());
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
                    new ProxyProperties.Logging(Files.createTempFile("authserver-audit-", ".log").toString())
            ));
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
