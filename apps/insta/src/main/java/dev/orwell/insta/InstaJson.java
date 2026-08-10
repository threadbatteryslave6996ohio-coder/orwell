package dev.orwell.insta;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The one shared, thread-safe {@link ObjectMapper} for this program.
 *
 * <p>A static holder rather than something passed around: there is no container here to own it,
 * and every user of it — the Apify wire format, the cache payloads, the {@code --json} output —
 * wants the same configuration.
 */
public final class InstaJson {
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    private InstaJson() {
    }

    /** Safe to use concurrently for reading and writing. */
    public static ObjectMapper mapper() {
        return MAPPER;
    }
}
