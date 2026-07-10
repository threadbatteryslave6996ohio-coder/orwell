package dev.orwell.alerting;

import dev.orwell.env.http.EnvLoader;

public final class AlertingApplication {
    private AlertingApplication() {
    }

    public static void main(String[] args) throws Exception {
        var env = AlertEnvs.from(EnvLoader.load("file"));
        AlertServer server = AlertServer.fromEnv(env);
        server.run();
    }
}
