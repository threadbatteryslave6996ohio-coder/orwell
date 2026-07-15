package dev.orwell.bucket.proxy;

import dev.orwell.bootstrap.AppServer;
import dev.orwell.bootstrap.HealthDetailsProvider;
import dev.orwell.bucket.proxy.storage.BucketStorage;
import dev.orwell.bucket.proxy.streaming.AnalysisWorker;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;

import java.util.Arrays;
import java.util.Map;

@SpringBootApplication
@ConfigurationPropertiesScan
public class BucketProxyApplication {
    static final String STREAM_WORKER_MODE = "--mode=stream-worker";

    /**
     * Server descriptor: how the environment is fetched stays with whoever calls
     * {@code SERVER.start(...)} / {@code runOrExit}; the core never reads {@code .env} files itself.
     */
    public static final AppServer SERVER =
            new AppServer(BucketProxyApplication.class, "bucket-proxy", JarvisProxyEnvs.ENV);

    /**
     * By default boots the Spring web server; passing {@code --mode=stream-worker} runs the bundled
     * stream analysis worker (an stdin MJPEG pipe filter, see deployment/analyze_stream.sh) from the
     * same jar instead.
     */
    public static void main(String[] args) throws Exception {
        if (isStreamWorkerMode(args)) {
            AnalysisWorker.main(withoutModeFlag(args));
            return;
        }
        SERVER.runOrExit(args);
    }

    private static boolean isStreamWorkerMode(String[] args) {
        return Arrays.asList(args).contains(STREAM_WORKER_MODE);
    }

    private static String[] withoutModeFlag(String[] args) {
        return Arrays.stream(args)
                .filter(arg -> !STREAM_WORKER_MODE.equals(arg))
                .toArray(String[]::new);
    }

    /** Extra payload for the shared {@code /health} endpoint. */
    @Bean
    public HealthDetailsProvider proxyHealthDetailsProvider(ProxyProperties properties, BucketStorage storage) {
        return () -> Map.of(
                "storageProvider", storage.provider(),
                "bucket", storage.containerName(),
                "region", storage.location(),
                "auth", "external-auth-server",
                "authServer", properties.authServer().baseUrl()
        );
    }
}
