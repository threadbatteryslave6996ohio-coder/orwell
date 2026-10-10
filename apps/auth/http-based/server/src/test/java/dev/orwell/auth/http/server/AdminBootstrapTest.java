package dev.orwell.auth.http.server;

import dev.orwell.auth.http.api.AuthWorkspaces;
import dev.orwell.auth.http.server.entity.ClientIdentity;
import dev.orwell.auth.http.server.repository.ClientIdentityRepository;
import dev.orwell.auth.http.server.security.CredentialHasher;
import dev.orwell.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminBootstrapTest {
    @Mock
    private ClientIdentityRepository identities;

    private final CredentialHasher credentialHasher = new CredentialHasher();
    private final Logger logger = entry -> {
    };

    private AdminBootstrap bootstrap(String clientId, String secret) {
        return new AdminBootstrap(identities, credentialHasher, logger, clientId, secret);
    }

    @Test
    void createsTheConfiguredAdminInTheAdminWorkspace() {
        when(identities.existsByWorkspaceAndClientId(AuthWorkspaces.ADMIN, "ops-admin")).thenReturn(false);

        bootstrap("ops-admin", "super-secret").run(null);

        ArgumentCaptor<ClientIdentity> captor = ArgumentCaptor.forClass(ClientIdentity.class);
        verify(identities).save(captor.capture());
        assertEquals(AuthWorkspaces.ADMIN, captor.getValue().getWorkspace());
        assertEquals("ops-admin", captor.getValue().getClientId());
        assertTrue(credentialHasher.matches("super-secret", captor.getValue().getSecretHash()));
    }

    /** Idempotent: a restart must not resurrect a rotated secret. */
    @Test
    void leavesAnExistingAdminAlone() {
        when(identities.existsByWorkspaceAndClientId(AuthWorkspaces.ADMIN, "ops-admin")).thenReturn(true);

        bootstrap("ops-admin", "super-secret").run(null);

        verify(identities, never()).save(any());
    }

    @Test
    void doesNothingWhenNoAdminIsConfigured() {
        bootstrap("", "super-secret").run(null);

        verify(identities, never()).save(any());
    }

    @Test
    void failsStartupWhenTheConfiguredSecretIsTooShort() {
        assertThrows(IllegalStateException.class, () -> bootstrap("ops-admin", "short").run(null));

        verify(identities, never()).save(any());
    }
}
