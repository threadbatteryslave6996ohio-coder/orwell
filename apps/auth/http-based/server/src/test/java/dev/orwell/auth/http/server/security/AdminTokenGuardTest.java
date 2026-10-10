package dev.orwell.auth.http.server.security;

import dev.orwell.auth.http.api.AuthWorkspaces;
import dev.orwell.auth.http.server.entity.ClientIdentity;
import dev.orwell.auth.http.server.entity.ClientToken;
import dev.orwell.auth.http.server.repository.ClientTokenRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminTokenGuardTest {
    @Mock
    private ClientTokenRepository tokens;

    @Mock
    private TokenUsage tokenUsage;

    private final CredentialHasher credentialHasher = new CredentialHasher();

    private AdminTokenGuard guard() {
        return new AdminTokenGuard(tokens, credentialHasher, tokenUsage);
    }

    @Test
    void acceptsATokenIssuedToAnIdentityInTheAdminWorkspace() {
        storedToken(AuthWorkspaces.ADMIN, "ops-admin", "admin-token");

        assertEquals("ops-admin", guard().requireAdmin("ops-admin", "Bearer admin-token"));
    }

    /** Provisioning counts as use, or an admin that never checks a token would look abandoned. */
    @Test
    void recordsThatTheAdminTokenWasUsed() {
        storedToken(AuthWorkspaces.ADMIN, "ops-admin", "admin-token");

        guard().requireAdmin("ops-admin", "Bearer admin-token");

        verify(tokenUsage).record(any(ClientToken.class), any(Instant.class));
    }

    @Test
    void doesNotRecordAUseForATokenItRefused() {
        storedToken(AuthWorkspaces.DEFAULT, "client-a", "ordinary-token");

        refusal("client-a", "Bearer ordinary-token");

        verify(tokenUsage, never()).record(any(), any());
    }

    @Test
    void rejectsATokenFromAnyOtherWorkspace() {
        storedToken(AuthWorkspaces.DEFAULT, "client-a", "ordinary-token");

        assertEquals(401, refusal("client-a", "Bearer ordinary-token").getStatusCode().value());
    }

    @Test
    void rejectsATokenPresentedUnderSomeoneElsesClientId() {
        storedToken(AuthWorkspaces.ADMIN, "ops-admin", "admin-token");

        assertEquals(401, refusal("other-admin", "Bearer admin-token").getStatusCode().value());
    }

    @Test
    void rejectsAnUnknownToken() {
        when(tokens.findWithIdentityByTokenHash(credentialHasher.hashToken("stolen"))).thenReturn(Optional.empty());

        assertEquals(401, refusal("ops-admin", "Bearer stolen").getStatusCode().value());
    }

    /** No repository call at all when a header is missing: an anonymous caller costs no query. */
    @Test
    void rejectsMissingCredentialsWithoutTouchingTheDatabase() {
        assertEquals(401, refusal(null, "Bearer admin-token").getStatusCode().value());
        assertEquals(401, refusal("ops-admin", null).getStatusCode().value());
        assertEquals(401, refusal("ops-admin", "Basic dXNlcjpwYXNz").getStatusCode().value());
    }

    private void storedToken(String workspace, String clientId, String token) {
        String tokenHash = credentialHasher.hashToken(token);
        ClientIdentity identity = new ClientIdentity(
                workspace, clientId, credentialHasher.hashSecret("super-secret"), Instant.now());
        when(tokens.findWithIdentityByTokenHash(tokenHash))
                .thenReturn(Optional.of(new ClientToken(identity, tokenHash, Instant.now())));
    }

    private ResponseStatusException refusal(String clientId, String authorization) {
        return assertThrows(ResponseStatusException.class, () -> guard().requireAdmin(clientId, authorization));
    }
}
