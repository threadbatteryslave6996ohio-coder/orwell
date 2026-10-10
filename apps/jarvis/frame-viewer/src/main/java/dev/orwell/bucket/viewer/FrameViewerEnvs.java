package dev.orwell.bucket.viewer;

import dev.orwell.env.Env;
import dev.orwell.env.EnvClassBuilder;
import dev.orwell.env.EnvFiles;
import dev.orwell.env.EnvOption;
import dev.orwell.env.EnvSchema;
import dev.orwell.env.EnvType;

import java.io.IOException;

/**
 * Everything the frame viewer reads from the environment.
 *
 * <p>Built on {@link EnvSchema} directly rather than on {@code AppServerEnv}, as
 * {@code jarvis-person-detection} is: this serves a handful of routes on the Undertow runtime and
 * has no Spring context to feed properties into, so the two keys it wants from that class are
 * cheaper to declare than the framework that owns them.
 *
 * <p>The prefix is {@code FRAME_VIEWER_} because a prefix is the only thing that says which process
 * a setting reaches — the same reason the hub's keys are {@code HUB_*} and the retention worker's
 * are {@code RETENTION_*}. These three processes share one database and nothing else; a key named
 * for the database rather than the reader is a key an operator changes in the wrong env file.
 */
public final class FrameViewerEnvs {
    private static final EnvClassBuilder BUILDER = EnvSchema.builder();

    public static final EnvOption<String> SERVER_ADDRESS;
    public static final EnvOption<Integer> SERVER_PORT;
    public static final EnvOption<String> FRAME_VIEWER_DATASOURCE_URL;
    public static final EnvOption<String> FRAME_VIEWER_DATASOURCE_USERNAME;
    public static final EnvOption<String> FRAME_VIEWER_DATASOURCE_PASSWORD;
    public static final EnvOption<Integer> FRAME_VIEWER_PAGE_SIZE;
    public static final EnvOption<String> FRAME_VIEWER_HUB_URL;
    public static final EnvOption<Long> FRAME_VIEWER_UPLOAD_MAX_BYTES;
    public static final EnvOption<Double> FRAME_VIEWER_UPLOAD_FPS;
    public static final EnvOption<Integer> FRAME_VIEWER_UPLOAD_PUSHES_PER_SECOND;
    public static final EnvOption<String> FRAME_VIEWER_FFMPEG;
    public static final EnvOption<String> FRAME_VIEWER_AUTH_BASE_URL;
    public static final EnvOption<String> FRAME_VIEWER_AUTH_WORKSPACE;
    public static final EnvOption<Integer> FRAME_VIEWER_AUTH_CACHE_SECONDS;
    public static final EnvOption<Boolean> FRAME_VIEWER_SECURE_COOKIES;
    public static final EnvOption<String> LOGGER;
    public static final EnvOption<String> LOKI_URL;
    public static final EnvOption<String> LOKI_TENANT_ID;

    private static final EnvSchema SCHEMA;

    static {
        EnvType<Double> doubleType = EnvType.of(Double.class, "double", Double::parseDouble);
        // Loopback by default even when FRAME_VIEWER_AUTH_BASE_URL requires a sign-in: what this
        // serves is camera footage, so the bind address stays as the backstop behind the login. With
        // no auth server configured it is the only guard, and the startup warning says so. A
        // container overrides this to 0.0.0.0 because it has to, and publishes on 127.0.0.1 instead.
        SERVER_ADDRESS = BUILDER.optional("SERVER_ADDRESS", EnvType.string(), "127.0.0.1");
        SERVER_PORT = BUILDER.optional("SERVER_PORT", EnvType.integer(), 9003);
        // Required: a viewer that cannot reach the table has nothing to show, and should fail at
        // startup rather than answer every request with a 503.
        FRAME_VIEWER_DATASOURCE_URL =
                BUILDER.required("FRAME_VIEWER_DATASOURCE_URL", EnvType.string());
        FRAME_VIEWER_DATASOURCE_USERNAME =
                BUILDER.required("FRAME_VIEWER_DATASOURCE_USERNAME", EnvType.string());
        FRAME_VIEWER_DATASOURCE_PASSWORD =
                BUILDER.required("FRAME_VIEWER_DATASOURCE_PASSWORD", EnvType.string());
        // Rows per page of the frame *list*, which carries no image bytes — so this bounds a JSON
        // response of a few hundred bytes per frame, not the hub's tens of kilobytes.
        FRAME_VIEWER_PAGE_SIZE = BUILDER.optional("FRAME_VIEWER_PAGE_SIZE", EnvType.integer(), 200);

        // --- Backfill: POST /api/uploads ------------------------------------------------------
        // Where the hub is. Optional, and blank on purpose: browsing stored frames needs only the
        // database, so a viewer with no hub configured is a perfectly good read-only viewer. It is
        // uploading that needs one, and the upload route answers 503 saying so rather than this
        // being a required key that stops the app from starting for a feature nobody is using.
        FRAME_VIEWER_HUB_URL = BUILDER.optional("FRAME_VIEWER_HUB_URL", EnvType.string(), "");
        // The largest video this will accept. Streamed to disk, never held in memory, so the limit
        // protects the container's disk and the time a caller waits — not the heap. 512 MiB is about
        // an hour of 1080p screen capture from apps/jarvis/clients.
        FRAME_VIEWER_UPLOAD_MAX_BYTES = BUILDER.optional(
                "FRAME_VIEWER_UPLOAD_MAX_BYTES", EnvType.longInteger(), 512L * 1024 * 1024);
        // Frames taken per second of video when the caller does not say. One is deliberately low:
        // frame_events holds the bytes and is capped at 2 GiB by jarvis-retention-worker, so a
        // generous default here would have an upload evict the live cameras' history.
        FRAME_VIEWER_UPLOAD_FPS = BUILDER.optional("FRAME_VIEWER_UPLOAD_FPS", doubleType, 1.0);
        // How fast frames are pushed to the hub. The ceiling exists because the hub stores frames
        // behind its live stream through a 512-deep queue: overrun it and a frame is delivered live
        // and never stored, which for a backfill means it does not exist. See BackfillService.
        FRAME_VIEWER_UPLOAD_PUSHES_PER_SECOND = BUILDER.optional(
                "FRAME_VIEWER_UPLOAD_PUSHES_PER_SECOND", EnvType.integer(), 25);
        // The ffmpeg to cut videos with. A name is resolved on PATH; a path is used as given.
        FRAME_VIEWER_FFMPEG = BUILDER.optional("FRAME_VIEWER_FFMPEG", EnvType.string(), "ffmpeg");

        // --- Authentication ---------------------------------------------------------------
        // The auth server that decides who may watch. Blank leaves every route open, which is the
        // local-development case and is said loudly at startup — the compose service sets it, so a
        // deployed stack is guarded by default.
        //
        // WHICH server this points at IS the access-control decision: whoever it accepts can watch
        // every recording. The stack points it at `admin-auth-server`, not at the client-facing one
        // that mints identities for cameras and upload clients — pointing it there would make every
        // camera a viewer of every camera. secrets-manager and object-storage-proxy split theirs the
        // same way, for the same reason.
        FRAME_VIEWER_AUTH_BASE_URL =
                BUILDER.optional("FRAME_VIEWER_AUTH_BASE_URL", EnvType.string(), "");
        // The workspace within that server. Blank is AuthWorkspaces.DEFAULT ("def").
        FRAME_VIEWER_AUTH_WORKSPACE =
                BUILDER.optional("FRAME_VIEWER_AUTH_WORKSPACE", EnvType.string(), "");
        // How long a token that checked out stays accepted without asking the auth server again.
        // Not a micro-optimisation: one step through the history is three requests (the frame and
        // the two neighbouring images it prefetches), so an uncached check makes holding an arrow
        // key down a burst of blocking POSTs to auth. The cost is stated plainly — a revoked token
        // keeps working here for up to this long. 0 checks every time.
        FRAME_VIEWER_AUTH_CACHE_SECONDS =
                BUILDER.optional("FRAME_VIEWER_AUTH_CACHE_SECONDS", EnvType.integer(), 30);
        // `Secure` on the session cookie. Off by default because the default deployment is
        // http://127.0.0.1 and a Secure cookie is simply never sent over that — turn it on wherever
        // this is reached over TLS, which is anywhere it is reached from another machine.
        FRAME_VIEWER_SECURE_COOKIES =
                BUILDER.optional("FRAME_VIEWER_SECURE_COOKIES", EnvType.bool(), false);

        // Which sinks the logger gets; blank keeps the repo default (Loki when LOKI_URL is set,
        // console otherwise). LoggerSetup parses it — see LoggerMode for the values.
        LOGGER = BUILDER.optional("LOGGER", EnvType.string(), "");
        LOKI_URL = BUILDER.optional("LOKI_URL", EnvType.string(), "");
        LOKI_TENANT_ID = BUILDER.optional("LOKI_TENANT_ID", EnvType.string(), "");
        SCHEMA = BUILDER.build();
    }

    /**
     * Values come from a {@code .env} file found upwards from the working directory, with real
     * environment variables taking precedence — the same rule the rest of the repo follows.
     */
    public static Env load() throws IOException {
        return SCHEMA.from(EnvFiles.load());
    }

    private FrameViewerEnvs() {
    }
}
