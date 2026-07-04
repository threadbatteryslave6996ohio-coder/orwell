package dev.orwell.keeboarder.server;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class KeeboarderControllerTest {
    private final KeeboarderController controller = new KeeboarderController(
            new KeeboarderService(),
            (clientId, token) -> "caller".equals(clientId) && "valid-token".equals(token)
    );

    @Test
    void rejectsClientListingWithoutCredentials() {
        assertEquals(401, controller.clients(null, null).getStatusCode().value());
    }

    @Test
    void checksClientListingCredentialsWithAuthenticator() {
        var response = controller.clients("Bearer valid-token", "caller");

        assertEquals(200, response.getStatusCode().value());
        assertEquals("[]", response.getBody());
    }

    @Test
    void rejectsSendWithInvalidToken() {
        var response = controller.send(
                new KeeboarderController.SendRequest("target", "message", "spoofed-sender"),
                "Bearer wrong-token",
                "caller"
        );

        assertEquals(401, response.getStatusCode().value());
    }
}
