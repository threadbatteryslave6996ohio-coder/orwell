package dev.orwell.bucket.detection;

import dev.orwell.undertow.ServerRuntime;

/**
 * Person-detection server. Exposes {@code POST /detect} which runs person detection over a
 * base64-encoded frame and fires a cooldown-gated alert to the configured alert endpoint.
 */
public final class DetectionApplication {
    public static void main(String[] args) {
        ServerRuntime.runOrExit(
                args,
                DetectionEnvs.ENV.schema(),
                DetectionSpringApplication::start,
                DetectionUndertowApplication::start
        );
    }
}
