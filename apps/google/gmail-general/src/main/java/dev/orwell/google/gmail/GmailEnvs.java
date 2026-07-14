package dev.orwell.google.gmail;

import dev.orwell.env.Env;
import dev.orwell.env.EnvOption;
import dev.orwell.env.EnvSchema;
import dev.orwell.env.EnvType;

import java.util.HashMap;
import java.util.Map;

public final class GmailEnvs {
    public static final EnvOption<String> GMAIL_SERVER_HOST;
    public static final EnvOption<Integer> GMAIL_SERVER_PORT;
    public static final EnvOption<String> AUTH_SERVER_URL;
    public static final EnvOption<String> AUTH_CLIENT_ID;
    public static final EnvOption<String> AUTH_CLIENT_SECRET;
    public static final EnvOption<String> GMAIL_STORE_DIR;
    public static final EnvOption<String> GMAIL_ACCESS_TOKEN;
    public static final EnvOption<String> GMAIL_REFRESH_TOKEN;
    public static final EnvOption<String> GMAIL_CLIENT_ID;
    public static final EnvOption<String> GMAIL_CLIENT_SECRET;
    public static final EnvOption<String> GMAIL_WEBHOOK_CLIENTS;
    public static final EnvOption<String> GMAIL_PUBSUB_TOPIC;
    public static final EnvSchema ENV;

    static {
        var builder = EnvSchema.builder();
        GMAIL_SERVER_HOST = builder.optional("GMAIL_SERVER_HOST", EnvType.string(), "127.0.0.1");
        GMAIL_SERVER_PORT = builder.optional("GMAIL_SERVER_PORT", EnvType.integer(), 9100);
        AUTH_SERVER_URL = builder.optional("AUTH_SERVER_URL", EnvType.string(), "http://127.0.0.1:8081");
        AUTH_CLIENT_ID = builder.optional("AUTH_CLIENT_ID", EnvType.string(), "gmail-general");
        AUTH_CLIENT_SECRET = builder.optional("AUTH_CLIENT_SECRET", EnvType.string(), "");
        GMAIL_STORE_DIR = builder.optional("GMAIL_STORE_DIR", EnvType.string(), "./data/gmail");
        GMAIL_ACCESS_TOKEN = builder.optional("GMAIL_ACCESS_TOKEN", EnvType.string(), "");
        GMAIL_REFRESH_TOKEN = builder.optional("GMAIL_REFRESH_TOKEN", EnvType.string(), "");
        GMAIL_CLIENT_ID = builder.optional("GMAIL_CLIENT_ID", EnvType.string(), "");
        GMAIL_CLIENT_SECRET = builder.optional("GMAIL_CLIENT_SECRET", EnvType.string(), "");
        GMAIL_WEBHOOK_CLIENTS = builder.optional("GMAIL_WEBHOOK_CLIENTS", EnvType.string(), "");
        GMAIL_PUBSUB_TOPIC = builder.optional("GMAIL_PUBSUB_TOPIC", EnvType.string(), "");
        ENV = builder.build();
    }

    private GmailEnvs() {
    }

    public static Env from(Map<String, String> source) {
        return ENV.from(source);
    }

    public static Map<String, Object> springProperties(Env env) {
        Map<String, Object> values = new HashMap<>();
        values.put("server.address", env.get(GMAIL_SERVER_HOST));
        values.put("server.port", env.get(GMAIL_SERVER_PORT));
        values.put("clippy.auth.base-url", env.get(AUTH_SERVER_URL));
        values.put("gmail.auth.client-id", env.get(AUTH_CLIENT_ID));
        values.put("gmail.auth.client-secret", env.get(AUTH_CLIENT_SECRET));
        values.put("gmail.store-dir", env.get(GMAIL_STORE_DIR));
        values.put("gmail.access-token", env.get(GMAIL_ACCESS_TOKEN));
        values.put("gmail.refresh-token", env.get(GMAIL_REFRESH_TOKEN));
        values.put("gmail.client-id", env.get(GMAIL_CLIENT_ID));
        values.put("gmail.client-secret", env.get(GMAIL_CLIENT_SECRET));
        values.put("gmail.webhook-clients", env.get(GMAIL_WEBHOOK_CLIENTS));
        values.put("gmail.pubsub-topic", env.get(GMAIL_PUBSUB_TOPIC));
        return values;
    }
}
