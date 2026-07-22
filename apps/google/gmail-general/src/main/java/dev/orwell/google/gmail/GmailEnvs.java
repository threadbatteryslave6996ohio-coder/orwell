package dev.orwell.google.gmail;

import dev.orwell.bootstrap.launch.AppServerEnv;
import dev.orwell.env.EnvOption;
import dev.orwell.env.EnvType;

public final class GmailEnvs {
    public static final AppServerEnv ENV = new AppServerEnv(false, true);

    public static final EnvOption<String> AUTH_CLIENT_ID;
    public static final EnvOption<String> AUTH_CLIENT_SECRET;
    public static final EnvOption<String> GMAIL_STORE_DIR;
    public static final EnvOption<String> GMAIL_WEBHOOK_CLIENTS;
    public static final EnvOption<String> IMAP_HOST;
    public static final EnvOption<Integer> IMAP_PORT;
    public static final EnvOption<Boolean> IMAP_SSL;
    public static final EnvOption<String> IMAP_USERNAME;
    public static final EnvOption<String> IMAP_PASSWORD;
    public static final EnvOption<String> IMAP_FOLDER;

    static {
        AUTH_CLIENT_ID = ENV.optional("AUTH_CLIENT_ID", EnvType.string(), "gmail-general");
        AUTH_CLIENT_SECRET = ENV.optional("AUTH_CLIENT_SECRET", EnvType.string(), "");
        GMAIL_STORE_DIR = ENV.optional("GMAIL_STORE_DIR", EnvType.string(), "./data/gmail");
        GMAIL_WEBHOOK_CLIENTS = ENV.optional("GMAIL_WEBHOOK_CLIENTS", EnvType.string(), "");
        IMAP_HOST = ENV.optional("IMAP_HOST", EnvType.string(), "imap.gmail.com");
        IMAP_PORT = ENV.optional("IMAP_PORT", EnvType.integer(), 993);
        IMAP_SSL = ENV.optional("IMAP_SSL", EnvType.bool(), true);
        IMAP_USERNAME = ENV.required("IMAP_USERNAME", EnvType.string());
        IMAP_PASSWORD = ENV.required("IMAP_PASSWORD", EnvType.string());
        IMAP_FOLDER = ENV.optional("IMAP_FOLDER", EnvType.string(), "INBOX");

        ENV.property("gmail.auth.client-id", AUTH_CLIENT_ID);
        ENV.property("gmail.auth.client-secret", AUTH_CLIENT_SECRET);
        ENV.property("gmail.store-dir", GMAIL_STORE_DIR);
        ENV.property("gmail.webhook-clients", GMAIL_WEBHOOK_CLIENTS);
        ENV.property("gmail.imap.host", IMAP_HOST);
        ENV.property("gmail.imap.port", IMAP_PORT);
        ENV.property("gmail.imap.ssl", IMAP_SSL);
        ENV.property("gmail.imap.username", IMAP_USERNAME);
        ENV.property("gmail.imap.password", IMAP_PASSWORD);
        ENV.property("gmail.imap.folder", IMAP_FOLDER);
    }

    private GmailEnvs() {
    }
}
