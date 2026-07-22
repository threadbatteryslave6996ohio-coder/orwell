package dev.orwell.google.gmail;

import com.icegreen.greenmail.user.GreenMailUser;
import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetupTest;
import dev.orwell.logging.Logger;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * End-to-end coverage of the live listener path (connect, IMAP IDLE, catch-up by UID, deliver)
 * against an in-process GreenMail IMAP server. Uses plaintext IMAP ({@code IMAP_SSL=false}); the
 * store forwards to no webhook clients, so a delivered message simply lands as a file we can assert.
 */
class ImapMailListenerIntegrationTest {
    private GreenMail greenMail;
    private ImapMailListener listener;
    private Path store;

    @BeforeEach
    void setUp() throws IOException {
        greenMail = new GreenMail(ServerSetupTest.IMAP.dynamicPort());
        greenMail.start();
        store = Files.createTempDirectory("gmail-imap-test");
    }

    @AfterEach
    void tearDown() {
        if (listener != null) {
            listener.stop();
        }
        if (greenMail != null) {
            greenMail.stop();
        }
    }

    @Test
    void deliversMailPushedWhileConnected() throws Exception {
        GreenMailUser user = greenMail.setUser("bob@example.com", "bob@example.com", "secret");
        Logger silent = entry -> {
        };
        GmailService delivery = new GmailService(
                "http://localhost:1", "gmail-general", "", store.toString(), "", silent);
        listener = new ImapMailListener("127.0.0.1", greenMail.getImap().getPort(), false,
                "bob@example.com", "secret", "INBOX", store.toString(), delivery, silent);

        listener.start();
        // Wait until the listener has connected and written its UID checkpoint, so the message below
        // is delivered as new mail (via IDLE) rather than being part of the skipped initial history.
        awaitExists(store.resolve(".imap-uid"), 15_000);

        user.deliver(newMessage("Meeting notes", "The plain body."));

        String json = awaitDeliveredJsonContaining("Meeting notes", 15_000);
        assertTrue(json.contains("The plain body."), json);
    }

    private MimeMessage newMessage(String subject, String body) throws Exception {
        MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
        message.setFrom(new InternetAddress("alice@example.com"));
        message.setRecipient(Message.RecipientType.TO, new InternetAddress("bob@example.com"));
        message.setSubject(subject);
        message.setText(body);
        message.saveChanges();
        return message;
    }

    private static void awaitExists(Path path, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (Files.exists(path)) {
                return;
            }
            Thread.sleep(100);
        }
        fail("Timed out waiting for " + path);
    }

    // Polls on content rather than mere existence: the listener writes the file on another thread,
    // so a bare existence check can read it mid-write (before Jackson flushes) and see an empty file.
    private String awaitDeliveredJsonContaining(String marker, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            try (var files = Files.list(store)) {
                for (Path file : files.filter(p -> p.getFileName().toString().endsWith(".json")).toList()) {
                    String content = Files.readString(file);
                    if (content.contains(marker)) {
                        return content;
                    }
                }
            }
            Thread.sleep(100);
        }
        fail("Timed out waiting for a delivered message file containing: " + marker);
        throw new IllegalStateException("unreachable");
    }
}
