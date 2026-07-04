package dev.orwell.bucket.alerting;

public final class AlertServerMain {
    private AlertServerMain() {
    }

    public static void main(String[] args) throws Exception {
        AlertServer.fromEnvironment().run();
    }
}
