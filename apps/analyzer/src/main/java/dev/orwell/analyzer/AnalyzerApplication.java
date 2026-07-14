package dev.orwell.analyzer;

import dev.orwell.bootstrap.AppServer;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Email analyzer server. Exposes {@code POST /analyzer/email} which classifies whether a Gmail
 * message looks like a login notification, behind the shared bearer-token authentication.
 */
@SpringBootApplication
public class AnalyzerApplication {
    public static final AppServer SERVER = AppServer.spring(AnalyzerApplication.class)
            .name("analyzer")
            .envs(AnalyzerEnvs.ENV)
            .properties(AnalyzerEnvs::springProperties)
            .build();

    public static void main(String[] args) {
        SERVER.runOrExit(args);
    }
}
