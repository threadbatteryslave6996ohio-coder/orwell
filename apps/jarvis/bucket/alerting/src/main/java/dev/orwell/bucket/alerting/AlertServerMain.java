package dev.orwell.bucket.alerting;

import dev.orwell.env.http.EnvLoader;

public final class AlertServerMain {
    private AlertServerMain() {
    }

    public static void main(String[] args) throws Exception {
        var env = AlertEnvs.from(EnvLoader.load("file"));
        AlertServer.fromEnv(env).run();
    }
}
