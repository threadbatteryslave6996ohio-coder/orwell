package dev.orwell.bucket.detection;

import dev.orwell.env.EnvFiles;

public final class DetectorApplication {
    private DetectorApplication() {
    }

    public static void main(String[] args) throws Exception {
        var env = DetectionEnvs.from(EnvFiles.load());
        DetectionServer.fromEnv(env).run();
    }
}
