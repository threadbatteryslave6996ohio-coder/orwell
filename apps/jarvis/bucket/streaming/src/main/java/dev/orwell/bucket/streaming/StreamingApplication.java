package dev.orwell.bucket.streaming;

public final class StreamingApplication {
    private StreamingApplication() {
    }

    public static void main(String[] args) throws Exception {
        AnalysisWorker.main(args);
    }
}
