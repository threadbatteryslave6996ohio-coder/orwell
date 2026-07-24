package dev.orwell.google.gmail;

import dev.orwell.bootstrap.launch.AppServer;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Gmail mailbox receiver server. A scheduled {@link ImapMailPoller} pulls the mailbox over IMAP
 * on a fixed interval and stores each new message in the {@code gmail} database; there is no
 * inbound HTTP trigger for ingestion. {@link dev.orwell.google.gmail.controller.MailController}
 * exposes stored mail for consumers to read back over HTTP.
 */
@SpringBootApplication
@EnableScheduling
public class GmailApplication {
    public static final AppServer SERVER =
            new AppServer(GmailApplication.class, "gmail-general", GmailEnvs.ENV);

    public static void main(String[] args) {
        SERVER.runOrExit(args);
    }
}
