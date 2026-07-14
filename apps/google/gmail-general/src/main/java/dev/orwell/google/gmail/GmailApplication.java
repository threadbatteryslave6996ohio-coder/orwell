package dev.orwell.google.gmail;

import dev.orwell.bootstrap.AppServer;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Gmail mailbox receiver server. Gmail push notifications arrive at {@code POST /gmail/pubsub};
 * {@code POST /gmail/watch} (authenticated) registers the mailbox watch.
 */
@SpringBootApplication
public class GmailApplication {
    public static final AppServer SERVER = AppServer.spring(GmailApplication.class)
            .name("gmail-general")
            .envs(GmailEnvs.ENV)
            .properties(GmailEnvs::springProperties)
            .build();

    public static void main(String[] args) {
        SERVER.runOrExit(args);
    }
}
