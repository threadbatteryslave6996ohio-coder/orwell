package dev.orwell.analyzer;

import dev.orwell.bootstrap.RequireAuthentication;
import dev.orwell.google.gmail.GmailMessage;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Locale;

@RestController
public class AnalyzerController {

    // Authentication runs in the shared interceptor before body parsing, preserving the
    // 401-before-400 ordering; malformed JSON gets the shared invalid-json 400 envelope.
    @RequireAuthentication
    @PostMapping("/analyzer/email")
    public Result email(@RequestBody GmailMessage mail) {
        String subject = mail.subject() == null ? "" : mail.subject();
        boolean match = subject.toLowerCase(Locale.ROOT).contains("login");
        System.out.println((match ? "LOGIN MATCH: " : "email: ") + subject + " <" + mail.from() + ">");
        return new Result(match, mail.id(), subject);
    }

    public record Result(boolean containsLogin, String id, String subject) {
    }
}
