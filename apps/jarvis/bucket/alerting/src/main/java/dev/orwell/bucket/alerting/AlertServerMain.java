package dev.orwell.bucket.alerting;

import dev.orwell.env.EnvFiles;

public final class AlertServerMain {
    private AlertServerMain() {
    }

    public static void main(String[] args) throws Exception {
        var env = AlertEnvs.from(EnvFiles.load());
        AlertServer.fromEnv(env).run();
    }
}
