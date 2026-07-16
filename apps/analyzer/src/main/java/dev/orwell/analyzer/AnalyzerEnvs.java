package dev.orwell.analyzer;

import dev.orwell.bootstrap.launch.AppServerEnv;

public final class AnalyzerEnvs {
    public static final AppServerEnv ENV = new AppServerEnv(false, true);

    private AnalyzerEnvs() {
    }
}
