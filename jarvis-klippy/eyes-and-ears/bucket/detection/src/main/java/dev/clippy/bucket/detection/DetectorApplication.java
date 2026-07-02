package dev.clippy.bucket.detection;

public final class DetectorApplication {
    private DetectorApplication() {
    }

    public static void main(String[] args) throws Exception {
        DetectionServer.fromEnvironment().run();
    }
}
