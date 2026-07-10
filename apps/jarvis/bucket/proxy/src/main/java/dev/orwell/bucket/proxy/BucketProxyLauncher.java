package dev.orwell.bucket.proxy;

import dev.orwell.bootstrap.AppServer;
import dev.orwell.bucket.proxy.streaming.AnalysisWorker;

import java.util.Arrays;

/**
 * Owns process startup for the bucket proxy jar. By default it boots the Spring
 * web server; passing {@code --mode=stream-worker} runs the bundled stream
 * analysis worker (an stdin MJPEG pipe filter) from the same jar instead.
 */
public final class BucketProxyLauncher {
    static final String STREAM_WORKER_MODE = "--mode=stream-worker";

    private BucketProxyLauncher() {
    }

    public static void main(String[] args) throws Exception {
        if (isStreamWorkerMode(args)) {
            AnalysisWorker.main(withoutModeFlag(args));
            return;
        }
        new AppServer(JarvisProxyEnvs.ENV, BucketProxyApplication::start)
                .runOrExit(args);
    }

    private static boolean isStreamWorkerMode(String[] args) {
        return Arrays.asList(args).contains(STREAM_WORKER_MODE);
    }

    private static String[] withoutModeFlag(String[] args) {
        return Arrays.stream(args)
                .filter(arg -> !STREAM_WORKER_MODE.equals(arg))
                .toArray(String[]::new);
    }
}
