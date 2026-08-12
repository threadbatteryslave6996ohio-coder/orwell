package dev.orwell.bucket.hub;

import dev.orwell.bootstrap.launch.AppServerEnv;
import dev.orwell.env.EnvOption;
import dev.orwell.env.EnvType;

/**
 * Everything the hub reads from the environment.
 *
 * <p>These were all {@code DETECTION_*} when the hub shared a process with person detection and
 * motion. They are {@code HUB_*} now because the prefix is the only thing that says which process
 * a setting reaches, and a hub-only knob named for detection is a setting an operator changes in
 * the wrong service's env file. {@code apps/jarvis/README.md} carries the full rename table.
 */
public final class HubEnvs {
    public static final AppServerEnv ENV = new AppServerEnv(false, false);
    public static final EnvOption<Integer> HUB_STREAM_QUEUE_DEPTH;
    public static final EnvOption<String> HUB_DATASOURCE_URL;
    public static final EnvOption<String> HUB_DATASOURCE_USERNAME;
    public static final EnvOption<String> HUB_DATASOURCE_PASSWORD;
    public static final EnvOption<String> HUB_JPA_HIBERNATE_DDL_AUTO;
    public static final EnvOption<String> HUB_STORE_MODE;
    public static final EnvOption<Integer> HUB_STORE_QUEUE_DEPTH;

    static {
        // Frames a client may fall behind before the hub starts dropping its oldest. Small on
        // purpose: this is a live feed, so a viewer that cannot keep up wants the newest frame,
        // not a growing backlog of stale ones.
        HUB_STREAM_QUEUE_DEPTH = ENV.optional("HUB_STREAM_QUEUE_DEPTH", EnvType.integer(), 8);
        // Frames are stored so a reconnecting client can be replayed what it missed, so the
        // datasource is required. See db-init/all-services.sql for the `jarvis` role and database.
        HUB_DATASOURCE_URL = ENV.required("HUB_DATASOURCE_URL", EnvType.string());
        HUB_DATASOURCE_USERNAME = ENV.required("HUB_DATASOURCE_USERNAME", EnvType.string());
        HUB_DATASOURCE_PASSWORD = ENV.required("HUB_DATASOURCE_PASSWORD", EnvType.string());
        HUB_JPA_HIBERNATE_DDL_AUTO =
                ENV.optional("HUB_JPA_HIBERNATE_DDL_AUTO", EnvType.string(), "update");

        // `async` writes frames behind the live stream, so a database stall cannot stall the
        // video; a frame may then be delivered live and never stored, which costs only the ability
        // to replay it. `sync` writes on the ingest thread, so a broadcast frame is always
        // replayable, at the price of putting Postgres on the live path.
        HUB_STORE_MODE = ENV.optional("HUB_STORE_MODE", EnvType.string(), "async");
        HUB_STORE_QUEUE_DEPTH = ENV.optional("HUB_STORE_QUEUE_DEPTH", EnvType.integer(), 512);

        ENV.property("hub.stream.queue-depth", HUB_STREAM_QUEUE_DEPTH);
        ENV.property("hub.store.mode", HUB_STORE_MODE);
        ENV.property("hub.store.queue-depth", HUB_STORE_QUEUE_DEPTH);
        ENV.property("spring.datasource.url", HUB_DATASOURCE_URL);
        ENV.property("spring.datasource.username", HUB_DATASOURCE_USERNAME);
        ENV.property("spring.datasource.password", HUB_DATASOURCE_PASSWORD);
        ENV.property("spring.jpa.hibernate.ddl-auto", HUB_JPA_HIBERNATE_DDL_AUTO);
    }

    private HubEnvs() {
    }
}
