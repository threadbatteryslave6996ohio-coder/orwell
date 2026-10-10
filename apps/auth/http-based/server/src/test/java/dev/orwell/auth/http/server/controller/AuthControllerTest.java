package dev.orwell.auth.http.server.controller;

import dev.orwell.auth.http.api.AuthWorkspaces;
import dev.orwell.auth.http.api.CheckTokenHttpRequest;
import dev.orwell.auth.http.api.CheckTokenHttpResponse;
import dev.orwell.auth.http.api.CreateIdentityHttpRequest;
import dev.orwell.auth.http.api.CreateIdentityHttpResponse;
import dev.orwell.auth.http.api.LoginHttpRequest;
import dev.orwell.auth.http.api.LoginHttpResponse;
import dev.orwell.auth.http.server.entity.ClientIdentity;
import dev.orwell.auth.http.server.entity.ClientToken;
import dev.orwell.auth.http.server.repository.ClientIdentityRepository;
import dev.orwell.auth.http.server.repository.ClientTokenRepository;
import dev.orwell.auth.http.server.security.AdminTokenGuard;
import dev.orwell.auth.http.server.security.CredentialHasher;
import dev.orwell.auth.http.server.security.TokenGenerator;
import dev.orwell.auth.http.server.security.TokenUsage;
import dev.orwell.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthControllerTest {
    private static final String ADMIN_HEADER = "ops-admin";
    private static final String ADMIN_AUTHORIZATION = "Bearer admin-token";

    @Mock
    private ClientIdentityRepository identities;

    @Mock
    private ClientTokenRepository tokens;

    @Mock
    private TokenGenerator tokenGenerator;

    @Mock
    private AdminTokenGuard adminTokenGuard;

    @Mock
    private TokenUsage tokenUsage;

    /** Logger is a functional interface, so the double is a no-op lambda; no test asserts on it. */
    private final Logger logger = entry -> {
    };

    private final CredentialHasher credentialHasher = new CredentialHasher();

    private AuthController controller() {
        return new AuthController(
                identities, tokens, credentialHasher, tokenGenerator, adminTokenGuard, tokenUsage, logger);
    }

    @Test
    void createIdentityStoresAHashedSecretInTheRequestedWorkspace() {
        when(adminTokenGuard.requireAdmin(ADMIN_HEADER, ADMIN_AUTHORIZATION)).thenReturn(ADMIN_HEADER);
        when(identities.existsByWorkspaceAndClientId("team-a", "client-a")).thenReturn(false);
        when(identities.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        CreateIdentityHttpResponse response = controller().createIdentity(
                "team-a", ADMIN_HEADER, ADMIN_AUTHORIZATION, new CreateIdentityHttpRequest("client-a", "super-secret"));

        ArgumentCaptor<ClientIdentity> identityCaptor = ArgumentCaptor.forClass(ClientIdentity.class);
        verify(identities).save(identityCaptor.capture());

        assertEquals("team-a", identityCaptor.getValue().getWorkspace());
        assertEquals("team-a", response.workspace());
        assertEquals("client-a", response.clientId());
        assertEquals(identityCaptor.getValue().getCreatedAt(), response.createdAt());
        assertTrue(credentialHasher.matches("super-secret", identityCaptor.getValue().getSecretHash()));
    }

    @Test
    void createIdentityRefusesACallerTheGuardRejects() {
        when(adminTokenGuard.requireAdmin(null, null))
                .thenThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "no"));

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> controller().createIdentity(
                        AuthWorkspaces.DEFAULT, null, null, new CreateIdentityHttpRequest("client-a", "super-secret")));

        assertEquals(401, exception.getStatusCode().value());
        verify(identities, never()).save(any());
    }

    /** The workspace is checked before the credential, so a bad name cannot be probed with a stolen token. */
    @Test
    void createIdentityRejectsAnUnusableWorkspaceNameBeforeCheckingTheToken() {
        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> controller().createIdentity(
                        "Team A", ADMIN_HEADER, ADMIN_AUTHORIZATION, new CreateIdentityHttpRequest("client-a", "super-secret")));

        assertEquals(400, exception.getStatusCode().value());
        verify(adminTokenGuard, never()).requireAdmin(any(), any());
    }

    @Test
    void createIdentityRejectsDuplicatesWithinOneWorkspace() {
        when(adminTokenGuard.requireAdmin(ADMIN_HEADER, ADMIN_AUTHORIZATION)).thenReturn(ADMIN_HEADER);
        when(identities.existsByWorkspaceAndClientId(AuthWorkspaces.DEFAULT, "client-a")).thenReturn(true);

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> controller().createIdentity(
                        AuthWorkspaces.DEFAULT, ADMIN_HEADER, ADMIN_AUTHORIZATION,
                        new CreateIdentityHttpRequest("client-a", "super-secret")));

        assertEquals(409, exception.getStatusCode().value());
    }

    @Test
    void loginIssuesAndPersistsTokenForMatchingCredentials() {
        String secretHash = credentialHasher.hashSecret("super-secret");
        ClientIdentity identity = new ClientIdentity("team-a", "client-a", secretHash, Instant.parse("2026-06-26T00:00:00Z"));
        when(identities.findByWorkspaceAndClientId("team-a", "client-a")).thenReturn(Optional.of(identity));
        when(tokenGenerator.newToken()).thenReturn("issued-token");
        when(tokens.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        LoginHttpResponse response = controller().login("team-a", new LoginHttpRequest("client-a", "super-secret"));

        ArgumentCaptor<ClientToken> tokenCaptor = ArgumentCaptor.forClass(ClientToken.class);
        verify(tokens).save(tokenCaptor.capture());

        assertEquals(new LoginHttpResponse("client-a", "issued-token"), response);
        assertEquals(credentialHasher.hashToken("issued-token"), tokenCaptor.getValue().getTokenHash());
        assertEquals(identity, tokenCaptor.getValue().getIdentity());
    }

    /** Same clientId, same secret, wrong workspace: the lookup never finds it, so it is a 401 like any other. */
    @Test
    void loginRejectsCredentialsFromAnotherWorkspace() {
        when(identities.findByWorkspaceAndClientId("team-b", "client-a")).thenReturn(Optional.empty());

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> controller().login("team-b", new LoginHttpRequest("client-a", "super-secret")));

        assertEquals(401, exception.getStatusCode().value());
        verify(tokens, never()).save(any());
    }

    @Test
    void loginRejectsInvalidCredentials() {
        ClientIdentity identity = new ClientIdentity(
                AuthWorkspaces.DEFAULT, "client-a", credentialHasher.hashSecret("super-secret"), Instant.now());
        when(identities.findByWorkspaceAndClientId(AuthWorkspaces.DEFAULT, "client-a")).thenReturn(Optional.of(identity));

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> controller().login(AuthWorkspaces.DEFAULT, new LoginHttpRequest("client-a", "wrong-secret")));

        assertEquals(401, exception.getStatusCode().value());
    }

    @Test
    void checkTokenReturnsTrueOnlyForMatchingActiveIdentity() {
        String token = "issued-token";
        String tokenHash = credentialHasher.hashToken(token);
        when(tokens.findWithIdentityByTokenHash(tokenHash)).thenReturn(Optional.of(tokenFor("team-a", "client-a", tokenHash)));

        CheckTokenHttpResponse response = controller().checkToken("team-a", new CheckTokenHttpRequest("client-a", token));

        assertTrue(response.valid());
        assertEquals("client-a", response.clientId());
    }

    @Test
    void checkTokenReturnsFalseForATokenIssuedInAnotherWorkspace() {
        String token = "issued-token";
        String tokenHash = credentialHasher.hashToken(token);
        when(tokens.findWithIdentityByTokenHash(tokenHash)).thenReturn(Optional.of(tokenFor("team-a", "client-a", tokenHash)));

        CheckTokenHttpResponse response = controller().checkToken("team-b", new CheckTokenHttpRequest("client-a", token));

        assertFalse(response.valid());
        assertEquals("client-a", response.clientId());
    }

    @Test
    void checkTokenReturnsFalseForMismatchedClientId() {
        String token = "issued-token";
        String tokenHash = credentialHasher.hashToken(token);
        when(tokens.findWithIdentityByTokenHash(tokenHash))
                .thenReturn(Optional.of(tokenFor(AuthWorkspaces.DEFAULT, "client-a", tokenHash)));

        CheckTokenHttpResponse response = controller()
                .checkToken(AuthWorkspaces.DEFAULT, new CheckTokenHttpRequest("client-b", token));

        assertFalse(response.valid());
        assertEquals("client-b", response.clientId());
    }

    /** What keeps a token that is genuinely in use out of {@code TokenCleanupJob}'s reach. */
    @Test
    void checkTokenRecordsThatAnAcceptedTokenWasUsed() {
        String tokenHash = credentialHasher.hashToken("issued-token");
        when(tokens.findWithIdentityByTokenHash(tokenHash)).thenReturn(Optional.of(tokenFor("team-a", "client-a", tokenHash)));

        controller().checkToken("team-a", new CheckTokenHttpRequest("client-a", "issued-token"));

        verify(tokenUsage).record(any(ClientToken.class), any(Instant.class));
    }

    /**
     * A rejected token has not been used, it has been refused. Recording it would let anyone
     * holding a dead credential keep it out of the sweep by presenting it at the wrong workspace.
     */
    @Test
    void checkTokenRecordsNoUseForATokenItRejects() {
        String tokenHash = credentialHasher.hashToken("issued-token");
        when(tokens.findWithIdentityByTokenHash(tokenHash)).thenReturn(Optional.of(tokenFor("team-a", "client-a", tokenHash)));

        controller().checkToken("team-b", new CheckTokenHttpRequest("client-a", "issued-token"));

        verify(tokenUsage, never()).record(any(), any());
    }

    private ClientToken tokenFor(String workspace, String clientId, String tokenHash) {
        ClientIdentity identity = new ClientIdentity(
                workspace, clientId, credentialHasher.hashSecret("super-secret"), Instant.now());
        return new ClientToken(identity, tokenHash, Instant.now());
    }
}
