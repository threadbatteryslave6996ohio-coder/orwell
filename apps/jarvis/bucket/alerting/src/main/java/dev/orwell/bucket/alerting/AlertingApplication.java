package dev.orwell.bucket.alerting;

public final class AlertingApplication {
    private AlertingApplication() {
    }

    public static void main(String[] args) throws Exception {
        AlertServer server = AlertServer.fromEnvironment();
        server.run();
    }
}
