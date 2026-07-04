package dev.orwell.clients.core;

import dev.orwell.clients.core.env.ClientEnvs;
import dev.orwell.env.Env;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DesktopClientRunnerTest {
    @Test
    void rendersMacStyleBannerWithTokenSourceLast() {
        Env env = ClientEnvs.from(Map.of(
                "REMOTE_SERVER_URL", "http://localhost:8080",
                "CLIENT_ID", "mac-host",
                "CLIENT_TOKEN", "token"
        ));
        ClientConfig config = ClientConfig.load(env, "fallback");

        String banner = DesktopClientRunner.startupBanner("Clippy client started.", config, 1L, Map.of());

        assertEquals(
                "Clippy client started. clientId=mac-host endpoint=http://localhost:8080/clipboard "
                        + "authServer=unset pollIntervalMs=1 tokenSource=CLIENT_TOKEN",
                banner);
    }

    @Test
    void insertsExtraFieldsBeforeTokenSourceAndReportsRefreshSource() {
        Env env = ClientEnvs.from(Map.of(
                "REMOTE_SERVER_URL", "http://localhost:8080",
                "CLIENT_ID", "linux-host",
                "AUTH_SERVER_URL", "http://localhost:9090",
                "CLIENT_SECRET", "secret"
        ));
        ClientConfig config = ClientConfig.load(env, "fallback");

        String banner = DesktopClientRunner.startupBanner(
                "Clippy Linux client started.", config, 1000L, Map.of("clipboardBackend", "awt"));

        assertEquals(
                "Clippy Linux client started. clientId=linux-host endpoint=http://localhost:8080/clipboard "
                        + "authServer=http://localhost:9090 pollIntervalMs=1000 clipboardBackend=awt "
                        + "tokenSource=CLIENT_SECRET",
                banner);
    }
}
