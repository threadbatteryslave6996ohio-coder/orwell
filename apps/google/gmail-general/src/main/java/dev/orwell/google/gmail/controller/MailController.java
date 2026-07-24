package dev.orwell.google.gmail.controller;

import dev.orwell.google.gmail.repository.EmailMessageRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Objects;

/**
 * Read-only access to mail stored by {@link dev.orwell.google.gmail.ImapMailPoller}. {@code id}
 * (see {@link dev.orwell.google.gmail.entity.EmailMessageEntity}) is assigned in insertion order,
 * so it doubles as a consumption cursor: a consumer polls {@code GET /mails/latest} to get
 * started, then repeatedly calls {@code GET /mails/latest?checkpoint=<last id it saw>} to fetch
 * only what arrived since.
 */
@RestController
@RequestMapping("${gmail.route-prefix:}/mails")
public class MailController {
    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 500;

    private final EmailMessageRepository repository;

    public MailController(EmailMessageRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    /** Most recently received mail first, bounded by {@code limit} (default 50, max 500). */
    @GetMapping
    public List<MailResponse> listMails(@RequestParam(name = "limit", required = false) Integer limit) {
        Pageable page = PageRequest.of(0, boundedLimit(limit));
        return repository.findAllByOrderByIdDesc(page).stream().map(MailResponse::from).toList();
    }

    /**
     * With no {@code checkpoint}: the single most recent mail, or {@code 204 No Content} if the
     * mailbox is empty. With {@code checkpoint=<id>}: every mail with {@code id} greater than it,
     * oldest first, bounded by {@code limit} (default 50, max 500) — the incremental-consumption
     * path.
     */
    @GetMapping("/latest")
    public ResponseEntity<?> latest(
            @RequestParam(name = "checkpoint", required = false) Long checkpoint,
            @RequestParam(name = "limit", required = false) Integer limit) {
        if (checkpoint == null) {
            return repository.findTopByOrderByIdDesc()
                    .<ResponseEntity<?>>map(entity -> ResponseEntity.ok(MailResponse.from(entity)))
                    .orElseGet(() -> ResponseEntity.noContent().build());
        }
        Pageable page = PageRequest.of(0, boundedLimit(limit));
        List<MailResponse> mails = repository.findByIdGreaterThanOrderByIdAsc(checkpoint, page).stream()
                .map(MailResponse::from).toList();
        return ResponseEntity.ok(mails);
    }

    private static int boundedLimit(Integer requested) {
        if (requested == null || requested <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(requested, MAX_LIMIT);
    }
}
