package dev.orwell.bucket.viewer;

import dev.orwell.auth.http.api.AuthWorkspaces;
import dev.orwell.auth.http.client.HttpAuthenticationStrategy;
import dev.orwell.env.Env;
import dev.orwell.logging.Logger;
import dev.orwell.logging.LoggerSetup;
import org.postgresql.ds.PGSimpleDataSource;

import java.io.PrintStream;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Map;

/**
 * The frame viewer: browse what the hub has stored, newest first, one step back at a time.
 *
 * <p>It is the fourth jarvis deployable and the only one that neither produces nor consumes frames.
 * It reads {@code frame_events} — the table the hub writes so a reconnecting client can be replayed
 * — and puts a page in front of it: choose a device, see its latest frame, walk backwards through the
 * retained window.
 *
 * <p><strong>It does not watch the stream, and that is deliberate.</strong> The repo's rule is that
 * anything wanting frames watches the hub through {@code jarvis-frame-client} rather than taking an
 * ingest route of its own — but that rule is about frames arriving, and this app is not waiting for
 * frames. It answers "what did camera 1 look like ninety seconds ago", which is a question about
 * history, and history is a table. Subscribing would mean receiving every frame of every camera in
 * order to display one of them on demand.
 *
 * <p><strong>Nor does it call the hub.</strong> The hub owns the live path — the process that must
 * stay up for the recording to continue, in a 512 MB container sized for frames in flight. Serving a
 * browser scrubbing through history from there would put that traffic on the same heap. Reading the
 * table directly costs the hub nothing, and {@code jarvis-retention-worker} already established that
 * a second process on this table is how jarvis does this.
 *
 * <p>A plain program on the Undertow runtime, like {@code jarvis-person-detection}: a page, four
 * JSON routes and an image route do not need a container.
 */
public final class FrameViewerApplication {
    /** The logger name and the Loki {@code app} label; matches the artifactId. */
    private static final String APP_NAME = "jarvis-frame-viewer";

    public static void main(String[] args) {
        Env env;
        try {
            env = FrameViewerEnvs.load();
        } catch (Exception exception) {
            // Before the logger exists there is nowhere else for this to go. AppServer and the
            // Undertow ServerRuntime print here for the same reason.
            System.err.println("frame-viewer: " + exception.getMessage());
            System.exit(1);
            return;
        }

        Logger logger = loggerFrom(env);
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setUrl(env.get(FrameViewerEnvs.FRAME_VIEWER_DATASOURCE_URL));
        dataSource.setUser(env.get(FrameViewerEnvs.FRAME_VIEWER_DATASOURCE_USERNAME));
        dataSource.setPassword(env.get(FrameViewerEnvs.FRAME_VIEWER_DATASOURCE_PASSWORD));
        // Read-only on the connection as well as in the SQL: every query in FrameQueries is a
        // SELECT, and this makes an accidental write a driver error rather than a silent change to
        // the table the hub is relaying from. Belt and braces, but the belt is free.
        dataSource.setReadOnly(true);

        if (!reachable(dataSource, logger, System.err)) {
            System.exit(4);
            return;
        }

        VideoFrames videos = new VideoFrames(env.get(FrameViewerEnvs.FRAME_VIEWER_FFMPEG), logger);
        BackfillService backfill = new BackfillService(
                videos,
                env.get(FrameViewerEnvs.FRAME_VIEWER_HUB_URL),
                env.get(FrameViewerEnvs.FRAME_VIEWER_UPLOAD_FPS),
                env.get(FrameViewerEnvs.FRAME_VIEWER_UPLOAD_PUSHES_PER_SECOND),
                logger);
        warnAboutUploads(backfill, videos, env, logger);

        ViewerServer server = new ViewerServer(
                dataSource::getConnection,
                backfill,
                authFrom(env, logger),
                env.get(FrameViewerEnvs.FRAME_VIEWER_PAGE_SIZE),
                env.get(FrameViewerEnvs.FRAME_VIEWER_UPLOAD_MAX_BYTES),
                env.get(FrameViewerEnvs.FRAME_VIEWER_SECURE_COOKIES),
                logger);
        try {
            server.startAndWait(
                    env.get(FrameViewerEnvs.SERVER_ADDRESS),
                    env.get(FrameViewerEnvs.SERVER_PORT));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * The auth server this viewer answers to, or an open viewer when none is configured.
     *
     * <p>Nothing is verified here — a wrong URL surfaces as sign-ins failing, not as a refusal to
     * start. That is deliberate: the auth server and this app come up in the same stack, and a
     * viewer that exits because auth was not ready yet is a viewer that needs restarting by hand.
     */
    private static ViewerAuth authFrom(Env env, Logger logger) {
        String baseUrl = env.get(FrameViewerEnvs.FRAME_VIEWER_AUTH_BASE_URL);
        if (baseUrl == null || baseUrl.isBlank()) {
            return new ViewerAuth(null, Duration.ZERO, logger);
        }
        String workspace = AuthWorkspaces.orDefault(
                env.get(FrameViewerEnvs.FRAME_VIEWER_AUTH_WORKSPACE));
        logger.info("The frame viewer requires a sign-in.", Map.of(
                "authServer", baseUrl, "workspace", workspace));
        return new ViewerAuth(
                new HttpAuthenticationStrategy(baseUrl.trim(), workspace, logger),
                Duration.ofSeconds(env.get(FrameViewerEnvs.FRAME_VIEWER_AUTH_CACHE_SECONDS)),
                logger);
    }

    /**
     * Says at startup whether uploads will work, rather than letting the first attempt find out.
     *
     * <p>Deliberately not fatal in either case. A viewer with no {@code FRAME_VIEWER_HUB_URL} is a
     * perfectly good read-only viewer, and a missing ffmpeg only costs the upload route — refusing to
     * start over a feature nobody may be using would be the wrong call. But both failures are
     * invisible until somebody uploads a video and waits for it, so both are said here.
     *
     * <p>The ffmpeg probe runs a subprocess, which is why it is behind the hub-url check: there is
     * nothing to report about a decoder for a feature that is switched off.
     */
    private static void warnAboutUploads(BackfillService backfill, VideoFrames videos, Env env,
            Logger logger) {
        if (!backfill.configured()) {
            logger.info("Video upload is off; set FRAME_VIEWER_HUB_URL to turn it on.", Map.of());
            return;
        }
        if (!videos.available()) {
            logger.warn("Video upload is configured but ffmpeg could not be run, so uploads will "
                            + "fail. Install it, or point FRAME_VIEWER_FFMPEG at it.",
                    Map.of("ffmpeg", env.get(FrameViewerEnvs.FRAME_VIEWER_FFMPEG)));
            return;
        }
        logger.info("Video upload is on.", Map.of(
                "hub", backfill.hubUrl(),
                "defaultFps", backfill.defaultFramesPerSecond()));
    }

    /**
     * Checks the database once before the port opens.
     *
     * <p>Everything this process does is read that table, so a viewer that starts anyway and answers
     * every request with a 503 is worse than one that refuses to start: the first looks healthy to
     * whoever launched it. It costs one connection at startup and turns a wrong
     * {@code FRAME_VIEWER_DATASOURCE_URL} into a message rather than a mystery.
     */
    private static boolean reachable(PGSimpleDataSource dataSource, Logger logger, PrintStream err) {
        try (Connection connection = dataSource.getConnection()) {
            long frames = FrameQueries.storedFrames(connection);
            logger.info("Frame viewer connected to the frame store.",
                    Map.of("storedFrames", frames));
            return true;
        } catch (SQLException exception) {
            err.println("frame-viewer: could not reach frame_events: " + exception.getMessage());
            logger.error("The frame viewer could not reach the frame store at startup.",
                    Map.of("error", exception.toString()));
            return false;
        }
    }

    /**
     * The sinks {@code LOGGER} asks for, assembled by the same {@code LoggerSetup} the Spring
     * servers use — so this process answers to the same configuration as the rest of the repo even
     * though there is no Spring context here to build the bean.
     */
    private static Logger loggerFrom(Env env) {
        return LoggerSetup.fromConfiguration(
                APP_NAME,
                env.get(FrameViewerEnvs.LOGGER),
                env.get(FrameViewerEnvs.LOKI_URL),
                env.get(FrameViewerEnvs.LOKI_TENANT_ID));
    }

    private FrameViewerApplication() {
    }
}
