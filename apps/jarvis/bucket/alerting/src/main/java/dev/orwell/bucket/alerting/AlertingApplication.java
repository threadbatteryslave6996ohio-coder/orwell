package dev.orwell.bucket.alerting;

import dev.orwell.env.EnvFiles;

public final class AlertingApplication {
    private AlertingApplication() {
    }

    public static void main(String[] args) throws Exception {
        var env = AlertEnvs.from(EnvFiles.load());
        AlertServer server = AlertServer.fromEnv(env);
        server.run();
    }
}
