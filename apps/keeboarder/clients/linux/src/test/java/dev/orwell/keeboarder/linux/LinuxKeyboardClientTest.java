package dev.orwell.keeboarder.linux;

import com.google.gson.JsonObject;
import dev.orwell.keeboarder.client.KeeboarderClientConfig;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LinuxKeyboardClientTest {
    @Test
    void registerPayloadUsesAuthenticatedClientId() throws Exception {
        KeeboarderClientConfig config = new KeeboarderClientConfig(
                "ws://localhost:8080/chat",
                "http://localhost:8081",
                "linux-client",
                "alias-client",
                "secret"
        );
        LinuxKeyboardClient client = new LinuxKeyboardClient(config, entry -> {
        });

        currentClientId(client).set("canonical-client");
        authTokenField(client).set(client, "token-123");

        JsonObject payload = client.buildRegisterPayload();

        assertEquals("register", payload.get("type").getAsString());
        assertEquals("canonical-client", payload.get("clientId").getAsString());
        assertEquals("linux-client", payload.get("name").getAsString());
        assertEquals("token-123", payload.get("token").getAsString());
    }

    @SuppressWarnings("unchecked")
    private static AtomicReference<String> currentClientId(LinuxKeyboardClient client) throws Exception {
        Field field = LinuxKeyboardClient.class.getDeclaredField("currentClientId");
        field.setAccessible(true);
        return (AtomicReference<String>) field.get(client);
    }

    private static Field authTokenField(LinuxKeyboardClient client) throws Exception {
        Field field = LinuxKeyboardClient.class.getDeclaredField("authToken");
        field.setAccessible(true);
        return field;
    }
}
