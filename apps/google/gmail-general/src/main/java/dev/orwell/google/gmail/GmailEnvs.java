package dev.orwell.google.gmail;

import dev.orwell.bootstrap.launch.AppServerEnv;
import dev.orwell.env.EnvOption;
import dev.orwell.env.EnvType;

public final class GmailEnvs {
    public static final AppServerEnv ENV = new AppServerEnv(false, true);

    public static final EnvOption<String> AUTH_CLIENT_ID;
    public static final EnvOption<String> AUTH_CLIENT_SECRET;
    public static final EnvOption<String> GMAIL_WEBHOOK_CLIENTS;
    public static final EnvOption<String> GMAIL_ROUTE_PREFIX;
    public static final EnvOption<Integer> GMAIL_POLL_INTERVAL_SECONDS;
    public static final EnvOption<String> GMAIL_DATASOURCE_URL;
    public static final EnvOption<String> GMAIL_DATASOURCE_USERNAME;
    public static final EnvOption<String> GMAIL_DATASOURCE_PASSWORD;
    public static final EnvOption<String> GMAIL_JPA_HIBERNATE_DDL_AUTO;
    public static final EnvOption<String> GMAIL_JPA_JDBC_TIME_ZONE;
    public static final EnvOption<String> IMAP_HOST;
    public static final EnvOption<Integer> IMAP_PORT;
    public static final EnvOption<Boolean> IMAP_SSL;
    public static final EnvOption<String> IMAP_USERNAME;
    public static final EnvOption<String> IMAP_PASSWORD;
    public static final EnvOption<String> IMAP_FOLDER;

    static {
        AUTH_CLIENT_ID = ENV.optional("AUTH_CLIENT_ID", EnvType.string(), "gmail-general");
        AUTH_CLIENT_SECRET = ENV.optional("AUTH_CLIENT_SECRET", EnvType.string(), "");
        GMAIL_WEBHOOK_CLIENTS = ENV.optional("GMAIL_WEBHOOK_CLIENTS", EnvType.string(), "");
        GMAIL_ROUTE_PREFIX = ENV.optional("GMAIL_ROUTE_PREFIX", EnvType.string(), "");
        GMAIL_POLL_INTERVAL_SECONDS = ENV.optional("GMAIL_POLL_INTERVAL_SECONDS", EnvType.integer(), 60);
        GMAIL_DATASOURCE_URL = ENV.required("GMAIL_DATASOURCE_URL", EnvType.string());
        GMAIL_DATASOURCE_USERNAME = ENV.required("GMAIL_DATASOURCE_USERNAME", EnvType.string());
        GMAIL_DATASOURCE_PASSWORD = ENV.required("GMAIL_DATASOURCE_PASSWORD", EnvType.string());
        GMAIL_JPA_HIBERNATE_DDL_AUTO = ENV.required("GMAIL_JPA_HIBERNATE_DDL_AUTO", EnvType.string());
        GMAIL_JPA_JDBC_TIME_ZONE = ENV.required("GMAIL_JPA_JDBC_TIME_ZONE", EnvType.string());
        IMAP_HOST = ENV.optional("IMAP_HOST", EnvType.string(), "imap.gmail.com");
        IMAP_PORT = ENV.optional("IMAP_PORT", EnvType.integer(), 993);
        IMAP_SSL = ENV.optional("IMAP_SSL", EnvType.bool(), true);
        IMAP_USERNAME = ENV.required("IMAP_USERNAME", EnvType.string());
        IMAP_PASSWORD = ENV.required("IMAP_PASSWORD", EnvType.string());
        IMAP_FOLDER = ENV.optional("IMAP_FOLDER", EnvType.string(), "INBOX");

        ENV.property("gmail.auth.client-id", AUTH_CLIENT_ID);
        ENV.property("gmail.auth.client-secret", AUTH_CLIENT_SECRET);
        ENV.property("gmail.webhook-clients", GMAIL_WEBHOOK_CLIENTS);
        ENV.property("gmail.route-prefix", GMAIL_ROUTE_PREFIX);
        ENV.property("gmail.poll-interval-seconds", GMAIL_POLL_INTERVAL_SECONDS);
        ENV.property("spring.datasource.url", GMAIL_DATASOURCE_URL);
        ENV.property("spring.datasource.username", GMAIL_DATASOURCE_USERNAME);
        ENV.property("spring.datasource.password", GMAIL_DATASOURCE_PASSWORD);
        ENV.property("spring.jpa.hibernate.ddl-auto", GMAIL_JPA_HIBERNATE_DDL_AUTO);
        ENV.property("spring.jpa.properties.hibernate.jdbc.time_zone", GMAIL_JPA_JDBC_TIME_ZONE);
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
