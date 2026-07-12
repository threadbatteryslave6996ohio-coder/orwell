package dev.orwell.keeboarder.server.http;

import dev.orwell.auth.AuthenticationContext;
import dev.orwell.keeboarder.server.service.KeeboarderService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.junit.jupiter.api.Assertions.assertEquals;

class KeeboarderControllerTest {
    @Test
    void rejectsClientListingWithoutCredentials() {
        KeeboarderController controller = newController(AuthenticationContext.unauthenticated());

        assertEquals(401, controller.clients().getStatusCode().value());
    }

    @Test
    void checksClientListingCredentialsWithAuthenticator() {
        KeeboarderController controller = newController(AuthenticationContext.authenticated("caller", 7L));
        var response = controller.clients();

        assertEquals(200, response.getStatusCode().value());
        assertEquals("[]", response.getBody());
    }

    @Test
    void rejectsSendWithInvalidToken() {
        KeeboarderController controller = newController(AuthenticationContext.unauthenticated());
        var response = controller.send(new KeeboarderController.SendRequest("target", "message"));

        assertEquals(401, response.getStatusCode().value());
    }

    private static KeeboarderController newController(AuthenticationContext authenticationContext) {
        return new KeeboarderController(new KeeboarderService(), provider(authenticationContext));
    }

    private static ObjectProvider<AuthenticationContext> provider(AuthenticationContext authenticationContext) {
        return new ObjectProvider<>() {
            @Override
            public AuthenticationContext getObject(Object... args) {
                return authenticationContext;
            }

            @Override
            public AuthenticationContext getObject() {
                return authenticationContext;
            }

            @Override
            public AuthenticationContext getIfAvailable() {
                return authenticationContext;
            }

            @Override
            public AuthenticationContext getIfUnique() {
                return authenticationContext;
            }

            @Override
            public java.util.Iterator<AuthenticationContext> iterator() {
                return java.util.List.of(authenticationContext).iterator();
            }
        };
    }
}
