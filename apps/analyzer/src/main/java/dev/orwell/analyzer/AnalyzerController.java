package dev.orwell.analyzer;

import dev.orwell.bootstrap.auth.RequireAuthentication;
import dev.orwell.google.gmail.GmailMessage;
import dev.orwell.logging.Logger;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

@RestController
public class AnalyzerController {

    private final Logger logger;

    public AnalyzerController(Logger logger) {
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    // Authentication runs in the shared interceptor before body parsing, preserving the
    // 401-before-400 ordering; malformed JSON gets the shared invalid-json 400 envelope.
    @RequireAuthentication
    @PostMapping("/analyzer/email")
    public Result email(@RequestBody GmailMessage mail) {
        String subject = mail.subject() == null ? "" : mail.subject();
        boolean match = subject.toLowerCase(Locale.ROOT).contains("login");
        // from() is caller-supplied and may be absent, so this map has to tolerate nulls.
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("subject", subject);
        metadata.put("from", mail.from());
        metadata.put("containsLogin", match);
        logger.info(match ? "Analyzed email: login match." : "Analyzed email.", metadata);
        return new Result(match, mail.id(), subject);
    }

    public record Result(boolean containsLogin, String id, String subject) {
    }
}
