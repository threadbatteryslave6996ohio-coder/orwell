package dev.orwell.bootstrap;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * A single shared, thread-safe {@link ObjectMapper} for the server apps. Deliberately a static
 * holder rather than a Spring bean: registering an {@code ObjectMapper} bean would also re-wire
 * Spring MVC's message converter and change its deserialization defaults
 * (e.g. {@code FAIL_ON_UNKNOWN_PROPERTIES}), which we don't want.
 *
 * <p>Controllers should not parse request bodies with this — use typed {@code @RequestBody}
 * parameters; {@link InvalidJsonBodyAdvice} supplies the shared invalid-JSON 400 contract.
 */
public final class SharedJson {
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    private SharedJson() {
    }

    /** The shared mapper. Safe to use concurrently for reading and writing. */
    public static ObjectMapper mapper() {
        return MAPPER;
    }
}
