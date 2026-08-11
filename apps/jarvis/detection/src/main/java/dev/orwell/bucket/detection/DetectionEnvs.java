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
    public static final EnvOption<Integer> DETECTION_STREAM_QUEUE_DEPTH;
    public static final EnvOption<String> DETECTION_DATASOURCE_URL;
    public static final EnvOption<String> DETECTION_DATASOURCE_USERNAME;
    public static final EnvOption<String> DETECTION_DATASOURCE_PASSWORD;
    public static final EnvOption<String> DETECTION_JPA_HIBERNATE_DDL_AUTO;
    public static final EnvOption<Integer> DETECTION_FRAME_RETENTION_SECONDS;
    public static final EnvOption<Long> DETECTION_FRAME_MAX_BYTES;
    public static final EnvOption<Integer> DETECTION_RETENTION_SWEEP_SECONDS;
    public static final EnvOption<String> DETECTION_STORE_MODE;
    public static final EnvOption<Integer> DETECTION_STORE_QUEUE_DEPTH;

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

        // Frames a client may fall behind before the hub starts dropping its oldest. Small on
        // purpose: this is a live feed, so a viewer that cannot keep up wants the newest frame,
        // not a growing backlog of stale ones.
        DETECTION_STREAM_QUEUE_DEPTH =
                ENV.optional("DETECTION_STREAM_QUEUE_DEPTH", EnvType.integer(), 8);
        // Frames are stored so a reconnecting client can be replayed what it missed, so the
        // datasource is required — a detection deployment that ran without a database now needs
        // one. See db-init/all-services.sql for the `jarvis` role and database.
        DETECTION_DATASOURCE_URL = ENV.required("DETECTION_DATASOURCE_URL", EnvType.string());
        DETECTION_DATASOURCE_USERNAME = ENV.required("DETECTION_DATASOURCE_USERNAME", EnvType.string());
        DETECTION_DATASOURCE_PASSWORD = ENV.required("DETECTION_DATASOURCE_PASSWORD", EnvType.string());
        DETECTION_JPA_HIBERNATE_DDL_AUTO =
                ENV.optional("DETECTION_JPA_HIBERNATE_DDL_AUTO", EnvType.string(), "update");
        // frame_events is bounded by whichever of these two bites first. The byte budget is what
        // protects the disk on a busy stream — it holds the footprint constant and lets the
        // replay window vary — and 2 GiB is chosen to be a modest tenant on the one Postgres the
        // whole repo shares. The age bound is what keeps a quiet camera's frames from living
        // forever; set either to 0 to disable that bound.
        DETECTION_FRAME_MAX_BYTES =
                ENV.optional("DETECTION_FRAME_MAX_BYTES", EnvType.longInteger(), 2L * 1024 * 1024 * 1024);
        DETECTION_FRAME_RETENTION_SECONDS =
                ENV.optional("DETECTION_FRAME_RETENTION_SECONDS", EnvType.integer(), 300);
        DETECTION_RETENTION_SWEEP_SECONDS =
                ENV.optional("DETECTION_RETENTION_SWEEP_SECONDS", EnvType.integer(), 30);

        // `async` writes frames behind the live stream, so a database stall cannot stall the
        // video; a frame may then be delivered live and never stored, which costs only the ability
        // to replay it. `sync` writes on the ingest thread, so a broadcast frame is always
        // replayable, at the price of putting Postgres on the live path.
        DETECTION_STORE_MODE = ENV.optional("DETECTION_STORE_MODE", EnvType.string(), "async");
        DETECTION_STORE_QUEUE_DEPTH =
                ENV.optional("DETECTION_STORE_QUEUE_DEPTH", EnvType.integer(), 512);

        ENV.property("detection.store.mode", DETECTION_STORE_MODE);
        ENV.property("detection.store.queue-depth", DETECTION_STORE_QUEUE_DEPTH);
        ENV.property("detection.stream.queue-depth", DETECTION_STREAM_QUEUE_DEPTH);
        ENV.property("spring.datasource.url", DETECTION_DATASOURCE_URL);
        ENV.property("spring.datasource.username", DETECTION_DATASOURCE_USERNAME);
        ENV.property("spring.datasource.password", DETECTION_DATASOURCE_PASSWORD);
        ENV.property("spring.jpa.hibernate.ddl-auto", DETECTION_JPA_HIBERNATE_DDL_AUTO);
        ENV.property("detection.frame-retention-seconds", DETECTION_FRAME_RETENTION_SECONDS);
        ENV.property("detection.frame-max-bytes", DETECTION_FRAME_MAX_BYTES);
        ENV.property("detection.retention-sweep-seconds", DETECTION_RETENTION_SWEEP_SECONDS);
    }

    private DetectionEnvs() {
    }
}
