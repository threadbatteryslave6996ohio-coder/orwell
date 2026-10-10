package dev.orwell.keeboarder.server.http;

import dev.orwell.auth.AuthenticationContext;
import dev.orwell.keeboarder.server.service.KeeboarderService;
import org.junit.jupiter.api.Test;
import dev.orwell.testing.ObjectProviders;
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
        KeeboarderController controller = newController(AuthenticationContext.authenticated("caller"));
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
        return ObjectProviders.of(authenticationContext);
    }
}
