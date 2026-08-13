package dev.orwell.bucket.hub;

import dev.orwell.bootstrap.health.HealthDetailsProvider;
import dev.orwell.bootstrap.launch.AppServer;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.util.LinkedHashMap;

/**
 * The frame hub: producers push frames to {@code POST /frames}, and every client holding
 * {@code GET /frames/stream} open receives them. A client that was away reconnects and is replayed
 * what it missed from {@code frame_events} before rejoining the live stream. {@code GET /frames}
 * reads that same table by capture time, for the caller who wants a window that has already passed
 * rather than a feed to follow.
 *
 * <p>Spring only, and so it has a single main class rather than the neutral launcher its siblings
 * use: SSE is Spring MVC async plumbing with no Undertow equivalent here. Under the old shared
 * process {@code /frames} answered 501 on the Undertow engine to say so; there is no engine to
 * choose wrongly now, so that branch is gone along with the 501.
 *
 * <p>Note what it no longer does: trimming {@code frame_events} is
 * {@code jarvis-retention-worker}'s job. The hub is a pipe with a buffer, and something else
 * decides how large the buffer may get.
 */
@SpringBootApplication(proxyBeanMethods = false)
public class HubApplication {
    public static final AppServer SERVER =
            new AppServer(HubApplication.class, "jarvis-hub", HubEnvs.ENV);

    public static void main(String[] args) {
        SERVER.runOrExit(args);
    }

    @Bean
    HubEndpoint hubEndpoint(FrameIngestService ingestService, FrameQueryService queryService,
            FrameHub hub, FrameStoreWriter store) {
        return new HubEndpoint(ingestService, queryService, hub, store);
    }

    @Bean
    HealthDetailsProvider hubHealthDetailsProvider(HubEndpoint endpoint) {
        return () -> new LinkedHashMap<>(endpoint.healthDetails());
    }
}
