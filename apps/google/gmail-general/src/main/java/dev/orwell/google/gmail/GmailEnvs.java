package dev.orwell.google.gmail;

import dev.orwell.bootstrap.launch.AppServerEnv;
import dev.orwell.env.EnvOption;
import dev.orwell.env.EnvType;

public final class GmailEnvs {
    public static final AppServerEnv ENV = new AppServerEnv(false, true);

    public static final EnvOption<String> AUTH_CLIENT_ID;
    public static final EnvOption<String> AUTH_CLIENT_SECRET;
    public static final EnvOption<String> GMAIL_STORE_DIR;
    public static final EnvOption<String> GMAIL_ACCESS_TOKEN;
    public static final EnvOption<String> GMAIL_REFRESH_TOKEN;
    public static final EnvOption<String> GMAIL_CLIENT_ID;
    public static final EnvOption<String> GMAIL_CLIENT_SECRET;
    public static final EnvOption<String> GMAIL_WEBHOOK_CLIENTS;
    public static final EnvOption<String> GMAIL_PUBSUB_TOPIC;

    static {
        AUTH_CLIENT_ID = ENV.optional("AUTH_CLIENT_ID", EnvType.string(), "gmail-general");
        AUTH_CLIENT_SECRET = ENV.optional("AUTH_CLIENT_SECRET", EnvType.string(), "");
        GMAIL_STORE_DIR = ENV.optional("GMAIL_STORE_DIR", EnvType.string(), "./data/gmail");
        GMAIL_ACCESS_TOKEN = ENV.optional("GMAIL_ACCESS_TOKEN", EnvType.string(), "");
        GMAIL_REFRESH_TOKEN = ENV.optional("GMAIL_REFRESH_TOKEN", EnvType.string(), "");
        GMAIL_CLIENT_ID = ENV.optional("GMAIL_CLIENT_ID", EnvType.string(), "");
        GMAIL_CLIENT_SECRET = ENV.optional("GMAIL_CLIENT_SECRET", EnvType.string(), "");
        GMAIL_WEBHOOK_CLIENTS = ENV.optional("GMAIL_WEBHOOK_CLIENTS", EnvType.string(), "");
        GMAIL_PUBSUB_TOPIC = ENV.optional("GMAIL_PUBSUB_TOPIC", EnvType.string(), "");

        ENV.property("gmail.auth.client-id", AUTH_CLIENT_ID);
        ENV.property("gmail.auth.client-secret", AUTH_CLIENT_SECRET);
        ENV.property("gmail.store-dir", GMAIL_STORE_DIR);
        ENV.property("gmail.access-token", GMAIL_ACCESS_TOKEN);
        ENV.property("gmail.refresh-token", GMAIL_REFRESH_TOKEN);
        ENV.property("gmail.client-id", GMAIL_CLIENT_ID);
        ENV.property("gmail.client-secret", GMAIL_CLIENT_SECRET);
        ENV.property("gmail.webhook-clients", GMAIL_WEBHOOK_CLIENTS);
        ENV.property("gmail.pubsub-topic", GMAIL_PUBSUB_TOPIC);
    }

    private GmailEnvs() {
    }
}
