package dev.clippy.bucket.alerting;

public final class AlertServerMain {
    private AlertServerMain() {
    }

    public static void main(String[] args) throws Exception {
        AlertServer.fromEnvironment().run();
    }
}
