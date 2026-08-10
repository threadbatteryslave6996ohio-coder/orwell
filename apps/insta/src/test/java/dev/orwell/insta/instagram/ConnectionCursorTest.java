package dev.orwell.insta.instagram;

import dev.orwell.insta.InstaJson;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reading the actor's continuation token out of a run's {@code OUTPUT} record.
 *
 * <p>This is the one part of the actor contract that cannot be verified without a paid run, so the
 * reader accepts both documented shapes and treats anything else as "no more pages" rather than
 * failing a lookup that already succeeded.
 */
class ConnectionCursorTest {

    @Test
    void readsTheTokenOutOfTheDocumentedContinuationsArray() {
        assertThat(nextTokenIn("""
                {"continuations":[{"account":"nasa","dataToScrape":"Followers",
                  "nextContinuationToken":"TOKEN-2","expiresAtUtc":"2026-08-11T00:00:00Z"}]}"""))
                .isEqualTo("TOKEN-2");
    }

    @Test
    void alsoAcceptsABareTopLevelToken() {
        assertThat(nextTokenIn("{\"nextContinuationToken\":\"TOKEN-2\"}")).isEqualTo("TOKEN-2");
        assertThat(nextTokenIn("{\"continuationToken\":\"TOKEN-2\"}")).isEqualTo("TOKEN-2");
    }

    /** No token is the ordinary end of a list, so every unrecognised shape has to read as one. */
    @Test
    void reportsNoTokenForAnOutputThatCarriesNone() {
        assertThat(nextTokenIn("{}")).isNull();
        assertThat(nextTokenIn("{\"continuations\":[]}")).isNull();
        assertThat(nextTokenIn("{\"continuations\":[{\"account\":\"nasa\"}]}")).isNull();
        assertThat(nextTokenIn("{\"continuations\":\"unexpected\"}")).isNull();
        assertThat(nextTokenIn("[]")).isNull();
        assertThat(ConnectionCursor.nextTokenIn(null)).isNull();
    }

    @Test
    void roundTripsAccountAndDirectionThroughTheEncodedForm() {
        String cursor = new ConnectionCursor("nasa", ConnectionType.FOLLOWERS, "TOKEN-2").encode();

        assertThat(ConnectionCursor.tokenFor(cursor, "nasa", ConnectionType.FOLLOWERS))
                .isEqualTo("TOKEN-2");
    }

    /** It travels in a URL, so it has to survive one without escaping. */
    @Test
    void encodesToAUrlSafeString() {
        String cursor = new ConnectionCursor(
                "nasa", ConnectionType.FOLLOWERS, "a/b+c=d?e&f").encode();

        assertThat(cursor).matches("[A-Za-z0-9_-]+");
    }

    private static String nextTokenIn(String outputJson) {
        try {
            return ConnectionCursor.nextTokenIn(InstaJson.mapper().readTree(outputJson));
        } catch (Exception exception) {
            throw new AssertionError("Test fixture was not JSON.", exception);
        }
    }
}
