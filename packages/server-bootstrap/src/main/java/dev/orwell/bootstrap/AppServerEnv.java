package dev.orwell.bootstrap;

import dev.orwell.env.Env;
import dev.orwell.env.EnvClassBuilder;
import dev.orwell.env.EnvOption;
import dev.orwell.env.EnvSchema;
import dev.orwell.env.EnvType;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Environment descriptor shared by standalone Spring server applications.
 * Common server options are registered first; applications add their own options
 * and Spring property mappings to this same descriptor.
 */
public final class AppServerEnv {
    public static final String SERVER_ADDRESS_PROPERTY = "server.address";
    public static final String SERVER_PORT_PROPERTY = "server.port";
    public static final String LOGGING_FILE_PROPERTY = "logging.file.name";
    public static final String AUTH_BASE_URL_PROPERTY = "orwell.auth.base-url";

    private final EnvClassBuilder builder = EnvSchema.builder();
    private final Map<String, EnvOption<?>> springProperties = new LinkedHashMap<>();

    public final EnvOption<String> SERVER_ADDRESS;
    public final EnvOption<Integer> SERVER_PORT;
    public final EnvOption<String> LOGGING_FILE_NAME;
    public final EnvOption<String> AUTH_BASE_URL;

    public AppServerEnv(
        boolean loggingFileRequired,
        boolean authBaseUrlRequired
    ) {
        SERVER_ADDRESS = builder.required("SERVER_ADDRESS", EnvType.string());
        SERVER_PORT = builder.required("SERVER_PORT", EnvType.integer());
        LOGGING_FILE_NAME = loggingFileRequired
                ? builder.required("LOGGING_FILE_NAME", EnvType.string())
                : builder.optional("LOGGING_FILE_NAME", EnvType.string());
        AUTH_BASE_URL = authBaseUrlRequired
                ? builder.required("AUTH_BASE_URL", EnvType.string())
                : builder.optional("AUTH_BASE_URL", EnvType.string());

        property(SERVER_ADDRESS_PROPERTY, SERVER_ADDRESS);
        property(SERVER_PORT_PROPERTY, SERVER_PORT);
        property(LOGGING_FILE_PROPERTY, LOGGING_FILE_NAME);
        property(AUTH_BASE_URL_PROPERTY, AUTH_BASE_URL);
    }

    public <T> EnvOption<T> required(String name, EnvType<T> type) {
        return builder.required(name, type);
    }

    public <T> EnvOption<T> optional(String name, EnvType<T> type) {
        return builder.optional(name, type);
    }

    public <T> EnvOption<T> optional(String name, EnvType<T> type, T defaultValue) {
        return builder.optional(name, type, defaultValue);
    }

    public <T> void property(String name, EnvOption<T> option) {
        springProperties.put(name, option);
    }

    public EnvSchema schema() {
        return builder.build();
    }

    public Map<String, Object> springProperties(Env env) {
        Map<String, Object> values = new LinkedHashMap<>();
        springProperties.forEach((name, option) -> {
            if (env.has(option)) {
                values.put(name, env.get(option));
            }
        });
        return values;
    }
}
