package dev.orwell.loganalyzer;

import dev.orwell.env.http.EnvLoader;

public final class LogAnalyzerApplication {
    private LogAnalyzerApplication() {
    }

    public static void main(String[] args) throws Exception {
        var env = LogAnalyzerEnvs.from(EnvLoader.load("file"));
        LogAnalyzerServer.fromEnv(env).run();
    }
}
