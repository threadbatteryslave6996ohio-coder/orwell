package dev.orwell.server;

import dev.orwell.auth.AuthenticationContext;
import dev.orwell.server.controller.ClipboardEntryController;
import dev.orwell.server.dto.ClipboardEntryDetailsResponse;
import dev.orwell.server.dto.ClipboardEntryRequest;
import dev.orwell.server.dto.ClipboardEntryResponse;
import dev.orwell.server.model.ClipboardEntry;
import dev.orwell.server.repository.ClipboardEntryRepository;
import dev.orwell.logging.LogEntry;
import dev.orwell.logging.Logger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ClipboardEntryControllerTest {

    @Test
    void logsClipboardEntryCreationForClient() {
        ClipboardEntryRepository repository = clipboardEntryRepository();
        List<LogEntry> logged = new ArrayList<>();

        ClipboardEntryController controller = new ClipboardEntryController(
                repository,
                provider(AuthenticationContext.authenticated("android-pixel-8", 1L)),
                logged::add
        );
        ClipboardEntryResponse response = controller.create(
                new ClipboardEntryRequest("android-pixel-8", "clipboard text", Instant.parse("2026-06-23T12:00:00Z"))
        );

        assertThat(response.clientId()).isEqualTo("android-pixel-8");
        assertThat(response.id()).isEqualTo(42L);

        assertThat(logged).singleElement().satisfies(entry -> {
            assertThat(entry.message()).isEqualTo("Added clipboard entry.");
            assertThat(entry.metadata())
                    .containsEntry("clientId", "android-pixel-8")
                    .containsEntry("entryId", 42L);
            // Clipboard content is deliberately never logged.
            assertThat(entry.metadata().values()).doesNotContain("clipboard text");
        });
    }

    @Test
    void clipboardWriteStillSucceedsWhenAuditLoggingFails() {
        ClipboardEntryRepository repository = clipboardEntryRepository();
        Logger failingLogger = entry -> {
            throw new IllegalStateException("logger sink unavailable");
        };

        ClipboardEntryController controller = new ClipboardEntryController(
                repository,
                provider(AuthenticationContext.authenticated("android-pixel-8", 1L)),
                failingLogger
        );
        ClipboardEntryResponse response = controller.create(
                new ClipboardEntryRequest("android-pixel-8", "clipboard text", Instant.parse("2026-06-23T12:00:00Z"))
        );

        assertThat(response.clientId()).isEqualTo("android-pixel-8");
        assertThat(response.id()).isEqualTo(42L);
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
        ClipboardEntryResponse response = new ClipboardEntryController(
                repository,
                provider(AuthenticationContext.authenticated("android-pixel-8", 1L)),
                entry -> {
                }
        ).create(
                new ClipboardEntryRequest(
                        "android-pixel-8",
                        "clipboard text",
                        Instant.parse("2026-06-23T12:00:00Z")
                )
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
        ClipboardEntryResponse response = new ClipboardEntryController(
                repository,
                provider(AuthenticationContext.authenticated("android-pixel-8", 1L)),
                entry -> {
                }
        ).create(
                new ClipboardEntryRequest(
                        "android-pixel-8",
                        "new text",
                        Instant.parse("2026-06-23T12:01:00Z")
                )
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
                repository,
                provider(AuthenticationContext.authenticated("android-pixel-8", 1L)),
                entry -> {
                }
        ).create(
                new ClipboardEntryRequest(
                        "android-pixel-8",
                        "clipboard text",
                        Instant.parse("2026-06-23T12:00:00Z")
                )
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
        ClipboardEntryController controller = new ClipboardEntryController(
                repository,
                provider(AuthenticationContext.authenticated("client-a", 1L)),
                entry -> {
                }
        );
        List<ClipboardEntryDetailsResponse> response = controller.findWithinTimeframe(
                "client-a", from, to, null, null, null
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
