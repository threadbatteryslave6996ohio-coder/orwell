package dev.orwell.google.gmail;

import dev.orwell.bootstrap.launch.AppServer;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Gmail mailbox receiver server. A background {@link ImapMailListener} watches the mailbox over
 * IMAP (IDLE) and forwards each new message to the configured webhook clients; there is no inbound
 * HTTP trigger. The server itself exposes only the shared health endpoint.
 */
@SpringBootApplication
public class GmailApplication {
    public static final AppServer SERVER =
            new AppServer(GmailApplication.class, "gmail-general", GmailEnvs.ENV);

    public static void main(String[] args) {
        SERVER.runOrExit(args);
    }
}
