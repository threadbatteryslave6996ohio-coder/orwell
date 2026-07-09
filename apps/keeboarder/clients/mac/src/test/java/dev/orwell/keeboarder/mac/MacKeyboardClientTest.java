package dev.orwell.keeboarder.mac;

import dev.orwell.keeboarder.client.KeeboarderClientConfig;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MacKeyboardClientTest {
    @Test
    void registerPayloadUsesAuthenticatedClientId() throws Exception {
        KeeboarderClientConfig config = new KeeboarderClientConfig(
                "ws://localhost:8025/ws/chat",
                "http://localhost:8081",
                "mac-client",
                "configured-client",
                "secret"
        );
        MacKeyboardClient client = new MacKeyboardClient(config);

        currentClientId(client).set("authenticated-client");
        authTokenField(client).set(client, "token-123");

        var payload = client.buildRegisterPayload();

        assertEquals("authenticated-client", payload.get("clientId").getAsString());
        assertEquals("mac-client", payload.get("name").getAsString());
        assertEquals("token-123", payload.get("token").getAsString());
    }

    @SuppressWarnings("unchecked")
    private static AtomicReference<String> currentClientId(MacKeyboardClient client) throws Exception {
        Field field = MacKeyboardClient.class.getDeclaredField("currentClientId");
        field.setAccessible(true);
        return (AtomicReference<String>) field.get(client);
    }

    private static Field authTokenField(MacKeyboardClient client) throws Exception {
        Field field = MacKeyboardClient.class.getDeclaredField("authToken");
        field.setAccessible(true);
        return field;
    }
}
