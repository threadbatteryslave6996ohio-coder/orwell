package dev.orwell.clients.dummy;

import dev.orwell.clients.core.ClipboardApiClient;
import dev.orwell.auth.http.client.ClientAuthSession;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.net.URI;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class DummyClientAppTest {
    @Test
    void joinsCommandLineArguments() throws Exception {
        assertEquals("hello world from klippy", invokeStaticString("joinArgs", new String[] {"hello", "world", "from", "klippy"}));
    }

    @Test
    void authFailureDuringSendIsReportedInsteadOfCrashingTheClient() {
        // No token and no secret: ClipboardApiClient.create -> authSession.token() throws
        // IllegalStateException. sendCommand must swallow it and report a normal failure.
        URI endpoint = URI.create("http://127.0.0.1:1/clipboard");
        ClientAuthSession noCredentials = new ClientAuthSession(null, "dummy", null, null);
        ClipboardApiClient apiClient = new ClipboardApiClient(endpoint, noCredentials, Duration.ofMillis(100));
        DummyClientApp app = new DummyClientApp(apiClient, endpoint, "dummy", entry -> {
        });

        assertFalse(app.sendCommand("hello"));
    }

    private static String invokeStaticString(String methodName, String[] arguments) throws Exception {
        Method method = DummyClientApp.class.getDeclaredMethod(methodName, String[].class);
        method.setAccessible(true);
        return (String) method.invoke(null, (Object) arguments);
    }

}
