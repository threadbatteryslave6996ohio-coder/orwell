package dev.orwell.bucket.retention.worker;

import dev.orwell.env.Env;
import dev.orwell.logging.Logger;
import dev.orwell.logging.LoggerSetup;
import org.postgresql.ds.PGSimpleDataSource;

/**
 * Bounds {@code frame_events}, and nothing else.
 *
 * <p>This is what makes storing video bytes in Postgres survivable. It runs unconditionally and
 * ignores delivery state: a client away longer than the retained history loses the frames that
 * aged out, because bounded storage is chosen over guaranteed catch-up. The alternative is a table
 * that grows at the ingest rate until the disk fills, on an instance shared with every other app
 * in the repo.
 *
 * <p><strong>It holds no port.</strong> Nothing calls it, so there is nothing to serve, and a
 * process that serves nothing should not run a web server to prove it is alive. That has one real
 * cost: {@code lastSweepError} used to be visible on the hub's {@code /health}, and now it is only
 * in the log. That is the trade — grep Loki for {@code "Frame retention sweep failed"} rather than
 * polling an endpoint. It also means container health cannot be an HTTP probe; the compose entry
 * checks the process instead.
 *
 * <p>Splitting it out of the hub buys two things beyond the port. The sweep no longer competes for
 * heap and CPU with the frames being relayed, and the hub can be restarted, scaled or taken down
 * without the table going unbounded while it is gone.
 */
public final class RetentionWorker {
    /** The logger name and the Loki {@code app} label; matches the artifactId. */
    private static final String APP_NAME = "jarvis-retention-worker";

    public static void main(String[] args) {
        Env env;
        try {
            env = RetentionWorkerEnvs.load();
        } catch (Exception exception) {
            // Before the logger exists there is nowhere else for this to go. AppServer and the
            // Undertow ServerRuntime print here for the same reason.
            System.err.println("retention worker: " + exception.getMessage());
            System.exit(1);
            return;
        }

        Logger logger = loggerFrom(env);
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setUrl(env.get(RetentionWorkerEnvs.RETENTION_DATASOURCE_URL));
        dataSource.setUser(env.get(RetentionWorkerEnvs.RETENTION_DATASOURCE_USERNAME));
        dataSource.setPassword(env.get(RetentionWorkerEnvs.RETENTION_DATASOURCE_PASSWORD));

        FrameRetentionSchedule schedule = new FrameRetentionSchedule(
                dataSource,
                logger,
                env.get(RetentionWorkerEnvs.RETENTION_FRAME_MAX_BYTES),
                env.get(RetentionWorkerEnvs.RETENTION_FRAME_MAX_AGE_SECONDS),
                env.get(RetentionWorkerEnvs.RETENTION_SWEEP_SECONDS));
        Runtime.getRuntime().addShutdownHook(new Thread(schedule::close, "retention-shutdown"));
        // The scheduler thread is non-daemon, so main returning here leaves the worker running.
        schedule.start();
    }

    /**
     * The sinks {@code LOGGER} asks for, assembled by the same {@code LoggerSetup} the Spring
     * servers use — so this process answers to the same configuration as the rest of the repo
     * even though there is no Spring context here to build the bean.
     *
     * <p>Sweep results are the only record this process leaves, so {@code loki-with-fallback} is
     * worth considering here: a Loki outage would otherwise take the evidence with it.
     */
    private static Logger loggerFrom(Env env) {
        return LoggerSetup.fromConfiguration(
                APP_NAME,
                env.get(RetentionWorkerEnvs.LOGGER),
                env.get(RetentionWorkerEnvs.LOKI_URL),
                env.get(RetentionWorkerEnvs.LOKI_TENANT_ID));
    }

    private RetentionWorker() {
    }
}
