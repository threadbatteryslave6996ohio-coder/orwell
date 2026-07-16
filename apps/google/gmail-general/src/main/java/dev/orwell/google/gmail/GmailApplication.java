package dev.orwell.google.gmail;

import dev.orwell.bootstrap.launch.AppServer;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Gmail mailbox receiver server. Gmail push notifications arrive at {@code POST /gmail/pubsub};
 * {@code POST /gmail/watch} (authenticated) registers the mailbox watch.
 */
@SpringBootApplication
public class GmailApplication {
    public static final AppServer SERVER =
            new AppServer(GmailApplication.class, "gmail-general", GmailEnvs.ENV);

    public static void main(String[] args) {
        SERVER.runOrExit(args);
    }
}
