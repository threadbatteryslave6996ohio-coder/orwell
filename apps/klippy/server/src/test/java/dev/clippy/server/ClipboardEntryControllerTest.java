package dev.clippy.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ClipboardEntryControllerTest {
    @TempDir
    Path tempDir;

    @Test
    void logsClipboardEntryCreationForClient() throws IOException {
        String originalLoggerDir = System.getProperty("custom.logger.dir");
        System.setProperty("custom.logger.dir", tempDir.toString());

        try {
            ClipboardEntryRepository repository = clipboardEntryRepository();
            AuthTokenVerifier authTokenVerifier = (clientId, token) -> "android-pixel-8".equals(clientId)
                    && "valid-token".equals(token);

            ClipboardEntryController controller = new ClipboardEntryController(repository, authTokenVerifier);
            ClipboardEntryResponse response = controller.create(
                    new ClipboardEntryRequest("android-pixel-8", "clipboard text", Instant.parse("2026-06-23T12:00:00Z")),
                    "Bearer valid-token"
            );

            assertThat(response.clientId()).isEqualTo("android-pixel-8");
            assertThat(response.id()).isEqualTo(42L);

            String content = Files.readString(tempDir.resolve("clippy-server.txt"));
            assertThat(content).contains("Added clipboard entry for clientId=android-pixel-8");
            assertThat(content).contains("entryId=42");
            assertThat(content).doesNotContain("clipboard text");
        } finally {
            if (originalLoggerDir == null) {
                System.clearProperty("custom.logger.dir");
            } else {
                System.setProperty("custom.logger.dir", originalLoggerDir);
            }
        }
    }

    @Test
    void clipboardWriteStillSucceedsWhenAuditLoggingFails() throws IOException {
        Path loggerTarget = Files.createTempFile(tempDir, "logger-target", ".txt");
        String originalLoggerDir = System.getProperty("custom.logger.dir");
        System.setProperty("custom.logger.dir", loggerTarget.toString());

        try {
            ClipboardEntryRepository repository = clipboardEntryRepository();
            AuthTokenVerifier authTokenVerifier = (clientId, token) -> "android-pixel-8".equals(clientId)
                    && "valid-token".equals(token);

            ClipboardEntryController controller = new ClipboardEntryController(repository, authTokenVerifier);
            ClipboardEntryResponse response = controller.create(
                    new ClipboardEntryRequest("android-pixel-8", "clipboard text", Instant.parse("2026-06-23T12:00:00Z")),
                    "Bearer valid-token"
            );

            assertThat(response.clientId()).isEqualTo("android-pixel-8");
            assertThat(response.id()).isEqualTo(42L);
        } finally {
            if (originalLoggerDir == null) {
                System.clearProperty("custom.logger.dir");
            } else {
                System.setProperty("custom.logger.dir", originalLoggerDir);
            }
        }
    }

    @Test
    void doesNotSaveWhenContentAndTimestampBothMatch() {
        ClipboardEntry existing = new ClipboardEntry(
                "android-pixel-8",
                "clipboard text",
                Instant.parse("2026-06-23T12:00:00Z")
        );
        setId(existing, 41L);
        AtomicInteger saves = new AtomicInteger();
        ClipboardEntryRepository repository = clipboardEntryRepository(List.of(existing), saves);
        AuthTokenVerifier authTokenVerifier = (clientId, token) -> "android-pixel-8".equals(clientId)
                && "valid-token".equals(token);

        ClipboardEntryResponse response = new ClipboardEntryController(repository, authTokenVerifier).create(
                new ClipboardEntryRequest(
                        "android-pixel-8",
                        "clipboard text",
                        Instant.parse("2026-06-23T12:00:00Z")
                ),
                "Bearer valid-token"
        );

        assertThat(response.id()).isEqualTo(41L);
        assertThat(response.timestamp()).isEqualTo(Instant.parse("2026-06-23T12:00:00Z"));
        assertThat(saves).hasValue(0);
    }

    @Test
    void savesWhenContentDiffers() {
        ClipboardEntry latest = new ClipboardEntry(
                "android-pixel-8",
                "old text",
                Instant.parse("2026-06-23T12:00:00Z")
        );
        AtomicInteger saves = new AtomicInteger();
        ClipboardEntryRepository repository = clipboardEntryRepository(List.of(latest), saves);
        AuthTokenVerifier authTokenVerifier = (clientId, token) -> "android-pixel-8".equals(clientId)
                && "valid-token".equals(token);

        ClipboardEntryResponse response = new ClipboardEntryController(repository, authTokenVerifier).create(
                new ClipboardEntryRequest(
                        "android-pixel-8",
                        "new text",
                        Instant.parse("2026-06-23T12:01:00Z")
                ),
                "Bearer valid-token"
        );

        assertThat(response.id()).isEqualTo(42L);
        assertThat(saves).hasValue(1);
    }

    @Test
    void savesOlderOfflineEntryWhenLatestEntryHasSameContent() {
        ClipboardEntry latest = new ClipboardEntry(
                "android-pixel-8",
                "clipboard text",
                Instant.parse("2026-06-23T12:01:00Z")
        );
        setId(latest, 41L);
        AtomicInteger saves = new AtomicInteger();
        ClipboardEntryRepository repository = clipboardEntryRepository(List.of(latest), saves);

        ClipboardEntryResponse response = new ClipboardEntryController(
                repository, (clientId, token) -> true).create(
                new ClipboardEntryRequest(
                        "android-pixel-8",
                        "clipboard text",
                        Instant.parse("2026-06-23T12:00:00Z")
                ),
                "Bearer valid-token"
        );

        assertThat(response.id()).isEqualTo(42L);
        assertThat(response.timestamp()).isEqualTo(Instant.parse("2026-06-23T12:00:00Z"));
        assertThat(saves).hasValue(1);
    }

    @Test
    void returnsClipboardEntriesWithinAuthenticatedClientTimeframe() {
        Instant from = Instant.parse("2026-06-23T12:00:00Z");
        Instant to = Instant.parse("2026-06-23T13:00:00Z");
        ClipboardEntryRepository repository = clipboardEntryRepository(List.of(
                new ClipboardEntry("client-a", "first", from),
                new ClipboardEntry("client-a", "second", to)
        ));
        AuthTokenVerifier authTokenVerifier = (clientId, token) -> "client-a".equals(clientId)
                && "valid-token".equals(token);

        ClipboardEntryController controller = new ClipboardEntryController(repository, authTokenVerifier);
        List<ClipboardEntryDetailsResponse> response = controller.findWithinTimeframe(
                "client-a", from, to, null, null, null, "Bearer valid-token"
        );

        assertThat(response).extracting(ClipboardEntryDetailsResponse::content)
                .containsExactly("first", "second");
    }

    private static ClipboardEntryRepository clipboardEntryRepository() {
        return clipboardEntryRepository(List.of());
    }

    private static ClipboardEntryRepository clipboardEntryRepository(List<ClipboardEntry> entries) {
        return clipboardEntryRepository(entries, new AtomicInteger());
    }

    private static ClipboardEntryRepository clipboardEntryRepository(
            List<ClipboardEntry> entries,
            AtomicInteger saves
    ) {
        InvocationHandler handler = (proxy, method, args) -> handleRepositoryCall(method, args, entries, saves);
        return (ClipboardEntryRepository) Proxy.newProxyInstance(
                ClipboardEntryRepository.class.getClassLoader(),
                new Class<?>[]{ClipboardEntryRepository.class},
                handler
        );
    }

    private static Object handleRepositoryCall(
            Method method,
            Object[] args,
            List<ClipboardEntry> entries,
            AtomicInteger saves
    ) {
        if ("save".equals(method.getName()) && args != null && args.length == 1 && args[0] instanceof ClipboardEntry entry) {
            saves.incrementAndGet();
            setId(entry, 42L);
            return entry;
        }

        if ("findFirstByClientIdAndTimestampAndContentOrderByIdAsc".equals(method.getName())) {
            String clientId = (String) args[0];
            Instant timestamp = (Instant) args[1];
            String content = (String) args[2];
            return entries.stream()
                    .filter(entry -> entry.getClientId().equals(clientId))
                    .filter(entry -> entry.getTimestamp().equals(timestamp))
                    .filter(entry -> entry.getContent().equals(content))
                    .findFirst();
        }

        if ("findByClientIdAndTimestampBetweenOrderByTimestampAscIdAsc".equals(method.getName())) {
            return entries;
        }

        if ("findTimeframePage".equals(method.getName())) {
            return entries;
        }

        throw new UnsupportedOperationException("Unexpected repository call: " + method.getName());
    }

    private static void setId(ClipboardEntry entry, long id) {
        try {
            java.lang.reflect.Field idField = ClipboardEntry.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(entry, id);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
