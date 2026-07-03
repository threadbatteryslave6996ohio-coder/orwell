package dev.clippy.auth;

import dev.clippy.auth.api.CheckTokenRequest;
import dev.clippy.auth.api.CheckTokenResponse;
import dev.clippy.auth.api.LoginRequest;
import dev.clippy.auth.api.LoginResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthControllerTest {
    @Mock
    private ClientIdentityRepository identities;

    @Mock
    private ClientTokenRepository tokens;

    @Mock
    private TokenGenerator tokenGenerator;

    private final CredentialHasher credentialHasher = new CredentialHasher();

    @Test
    void createIdentityStoresAHashedSecret() {
        when(identities.existsByClientId("client-a")).thenReturn(false);
        when(identities.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        AuthController controller = new AuthController(identities, tokens, credentialHasher, tokenGenerator);
        IdentityResponse response = controller.createIdentity(new CreateIdentityRequest("client-a", "super-secret"));

        ArgumentCaptor<ClientIdentity> identityCaptor = ArgumentCaptor.forClass(ClientIdentity.class);
        org.mockito.Mockito.verify(identities).save(identityCaptor.capture());

        assertEquals("client-a", response.clientId());
        assertEquals(identityCaptor.getValue().getCreatedAt(), response.createdAt());
        assertTrue(credentialHasher.matches("super-secret", identityCaptor.getValue().getSecretHash()));
    }

    @Test
    void createIdentityRejectsDuplicates() {
        when(identities.existsByClientId("client-a")).thenReturn(true);

        AuthController controller = new AuthController(identities, tokens, credentialHasher, tokenGenerator);

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> controller.createIdentity(new CreateIdentityRequest("client-a", "super-secret")));

        assertEquals(409, exception.getStatusCode().value());
    }

    @Test
    void loginIssuesAndPersistsTokenForMatchingCredentials() {
        String secretHash = credentialHasher.hashSecret("super-secret");
        ClientIdentity identity = new ClientIdentity("client-a", secretHash, Instant.parse("2026-06-26T00:00:00Z"));
        when(identities.findByClientId("client-a")).thenReturn(Optional.of(identity));
        when(tokenGenerator.newToken()).thenReturn("issued-token");
        when(tokens.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        AuthController controller = new AuthController(identities, tokens, credentialHasher, tokenGenerator);
        LoginResponse response = controller.login(new LoginRequest("client-a", "super-secret"));

        ArgumentCaptor<ClientToken> tokenCaptor = ArgumentCaptor.forClass(ClientToken.class);
        org.mockito.Mockito.verify(tokens).save(tokenCaptor.capture());

        assertEquals(new LoginResponse("client-a", "issued-token"), response);
        assertEquals(credentialHasher.hashToken("issued-token"), tokenCaptor.getValue().getTokenHash());
        assertEquals(identity, tokenCaptor.getValue().getIdentity());
    }

    @Test
    void loginRejectsInvalidCredentials() {
        ClientIdentity identity = new ClientIdentity("client-a", credentialHasher.hashSecret("super-secret"), Instant.now());
        when(identities.findByClientId("client-a")).thenReturn(Optional.of(identity));

        AuthController controller = new AuthController(identities, tokens, credentialHasher, tokenGenerator);

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> controller.login(new LoginRequest("client-a", "wrong-secret")));

        assertEquals(401, exception.getStatusCode().value());
    }

    @Test
    void checkTokenReturnsTrueOnlyForMatchingActiveIdentity() {
        ClientIdentity identity = new ClientIdentity("client-a", credentialHasher.hashSecret("super-secret"), Instant.now());
        String token = "issued-token";
        String tokenHash = credentialHasher.hashToken(token);
        when(tokens.findWithIdentityByTokenHash(tokenHash)).thenReturn(Optional.of(new ClientToken(identity, tokenHash, Instant.now())));

        AuthController controller = new AuthController(identities, tokens, credentialHasher, tokenGenerator);

        CheckTokenResponse response = controller.checkToken(new CheckTokenRequest("client-a", token));

        assertTrue(response.valid());
        assertEquals("client-a", response.clientId());
    }

    @Test
    void checkTokenReturnsFalseForMismatchedClientId() {
        ClientIdentity identity = new ClientIdentity("client-a", credentialHasher.hashSecret("super-secret"), Instant.now());
        String token = "issued-token";
        String tokenHash = credentialHasher.hashToken(token);
        when(tokens.findWithIdentityByTokenHash(tokenHash)).thenReturn(Optional.of(new ClientToken(identity, tokenHash, Instant.now())));

        AuthController controller = new AuthController(identities, tokens, credentialHasher, tokenGenerator);

        CheckTokenResponse response = controller.checkToken(new CheckTokenRequest("client-b", token));

        assertFalse(response.valid());
        assertEquals("client-b", response.clientId());
    }
}
