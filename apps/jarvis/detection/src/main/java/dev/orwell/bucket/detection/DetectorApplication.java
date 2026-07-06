package dev.orwell.bucket.detection;

import dev.orwell.env.http.EnvLoader;

public final class DetectorApplication {
    private DetectorApplication() {
    }

    public static void main(String[] args) throws Exception {
        var env = DetectionEnvs.from(EnvLoader.load("file"));
        DetectionServer.fromEnv(env).run();
    }
}
