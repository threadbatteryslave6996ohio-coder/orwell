package dev.orwell.analyzer;

import dev.orwell.bootstrap.AppServer;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Email analyzer server. Exposes {@code POST /analyzer/email} which classifies whether a Gmail
 * message looks like a login notification, behind the shared bearer-token authentication.
 */
@SpringBootApplication
public class AnalyzerApplication {
    public static final AppServer SERVER =
            new AppServer(AnalyzerApplication.class, "analyzer", AnalyzerEnvs.ENV);

    public static void main(String[] args) {
        SERVER.runOrExit(args);
    }
}
