package dev.orwell.bucket.detection;

import dev.orwell.bootstrap.launch.AppServerEnv;
import dev.orwell.env.EnvOption;
import dev.orwell.env.EnvType;

public final class DetectionEnvs {
    public static final AppServerEnv ENV = new AppServerEnv(false, false);
    public static final EnvOption<String> DETECTION_ALERT_URL;
    public static final EnvOption<Integer> DETECTION_ALERT_COOLDOWN_SECONDS;
    public static final EnvOption<Double> DETECTION_MIN_CONFIDENCE;
    public static final EnvOption<Integer> DETECTION_MOTION_CELL_THRESHOLD;
    public static final EnvOption<Double> DETECTION_MOTION_MIN_CHANGED_FRACTION;
    public static final EnvOption<String> DETECTION_DATASOURCE_URL;
    public static final EnvOption<String> DETECTION_DATASOURCE_USERNAME;
    public static final EnvOption<String> DETECTION_DATASOURCE_PASSWORD;
    public static final EnvOption<String> DETECTION_JPA_HIBERNATE_DDL_AUTO;
    public static final EnvOption<String> DETECTION_FANOUT_MODE;
    public static final EnvOption<Integer> DETECTION_FANOUT_INTERVAL_SECONDS;
    public static final EnvOption<Integer> DETECTION_FRAME_RETENTION_SECONDS;
    public static final EnvOption<Integer> DETECTION_RETENTION_SWEEP_SECONDS;
    public static final EnvOption<String> DETECTION_AUTH_CLIENT_ID;
    public static final EnvOption<String> DETECTION_AUTH_CLIENT_SECRET;

    static {
        EnvType<Double> doubleType = EnvType.of(Double.class, "double", Double::parseDouble);
        DETECTION_ALERT_URL = ENV.optional("DETECTION_ALERT_URL", EnvType.string(), "http://127.0.0.1:9000/alerts");
        DETECTION_ALERT_COOLDOWN_SECONDS = ENV.optional("DETECTION_ALERT_COOLDOWN_SECONDS", EnvType.integer(), 60);
        DETECTION_MIN_CONFIDENCE = ENV.optional("DETECTION_MIN_CONFIDENCE", doubleType, 0.35);
        // A cell counts as changed when its average luminance moves by more than this (0-255);
        // 12 sits above JPEG/sensor noise on a static scene without hiding real movement.
        DETECTION_MOTION_CELL_THRESHOLD = ENV.optional("DETECTION_MOTION_CELL_THRESHOLD", EnvType.integer(), 12);
        // ... and the frame counts as changed once that many cells of 256 move: 0.02 is ~5 cells,
        // enough to ignore a flickering timestamp overlay but to catch a person entering frame.
        DETECTION_MOTION_MIN_CHANGED_FRACTION =
                ENV.optional("DETECTION_MOTION_MIN_CHANGED_FRACTION", doubleType, 0.02);
        ENV.property("detection.alert-url", DETECTION_ALERT_URL);
        ENV.property("detection.cooldown-seconds", DETECTION_ALERT_COOLDOWN_SECONDS);
        ENV.property("detection.min-confidence", DETECTION_MIN_CONFIDENCE);
        ENV.property("detection.motion.cell-threshold", DETECTION_MOTION_CELL_THRESHOLD);
        ENV.property("detection.motion.min-changed-fraction", DETECTION_MOTION_MIN_CHANGED_FRACTION);

        // The frame bastion is Postgres-backed, so the datasource is required — this is the one
        // breaking change in the fan-out work: a detection deployment that ran without a database
        // now needs one. See db-init/all-services.sql for the `jarvis` role and database.
        DETECTION_DATASOURCE_URL = ENV.required("DETECTION_DATASOURCE_URL", EnvType.string());
        DETECTION_DATASOURCE_USERNAME = ENV.required("DETECTION_DATASOURCE_USERNAME", EnvType.string());
        DETECTION_DATASOURCE_PASSWORD = ENV.required("DETECTION_DATASOURCE_PASSWORD", EnvType.string());
        DETECTION_JPA_HIBERNATE_DDL_AUTO =
                ENV.optional("DETECTION_JPA_HIBERNATE_DDL_AUTO", EnvType.string(), "update");
        // `changed` stores and fans out only frames that differ from the previous one for their
        // source (plus each source's first frame). `all` keeps every frame — faithful to "fan out
        // everything pushed", and the setting that makes frame_events grow at the full ingest rate.
        DETECTION_FANOUT_MODE = ENV.optional("DETECTION_FANOUT_MODE", EnvType.string(), "changed");
        DETECTION_FANOUT_INTERVAL_SECONDS =
                ENV.optional("DETECTION_FANOUT_INTERVAL_SECONDS", EnvType.integer(), 2);
        // The ceiling on how far behind a subscriber may fall and still catch up, and with it the
        // ceiling on table size: retention_seconds x ingest_rate x frame_size.
        DETECTION_FRAME_RETENTION_SECONDS =
                ENV.optional("DETECTION_FRAME_RETENTION_SECONDS", EnvType.integer(), 300);
        DETECTION_RETENTION_SWEEP_SECONDS =
                ENV.optional("DETECTION_RETENTION_SWEEP_SECONDS", EnvType.integer(), 30);
        // Optional: blank means frames are delivered without auth headers, which is how detection
        // has always run. Set both to authenticate outbound deliveries as this service.
        DETECTION_AUTH_CLIENT_ID = ENV.optional("DETECTION_AUTH_CLIENT_ID", EnvType.string(), "");
        DETECTION_AUTH_CLIENT_SECRET =
                ENV.optional("DETECTION_AUTH_CLIENT_SECRET", EnvType.string(), "");

        ENV.property("spring.datasource.url", DETECTION_DATASOURCE_URL);
        ENV.property("spring.datasource.username", DETECTION_DATASOURCE_USERNAME);
        ENV.property("spring.datasource.password", DETECTION_DATASOURCE_PASSWORD);
        ENV.property("spring.jpa.hibernate.ddl-auto", DETECTION_JPA_HIBERNATE_DDL_AUTO);
        ENV.property("detection.fanout.mode", DETECTION_FANOUT_MODE);
        ENV.property("detection.fanout.interval-seconds", DETECTION_FANOUT_INTERVAL_SECONDS);
        ENV.property("detection.frame-retention-seconds", DETECTION_FRAME_RETENTION_SECONDS);
        ENV.property("detection.retention-sweep-seconds", DETECTION_RETENTION_SWEEP_SECONDS);
        ENV.property("detection.auth.client-id", DETECTION_AUTH_CLIENT_ID);
        ENV.property("detection.auth.client-secret", DETECTION_AUTH_CLIENT_SECRET);
    }

    private DetectionEnvs() {
    }
}
