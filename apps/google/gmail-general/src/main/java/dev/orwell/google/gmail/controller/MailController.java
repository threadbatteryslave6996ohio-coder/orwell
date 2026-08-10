package dev.orwell.google.gmail.controller;

import dev.orwell.auth.AuthenticationContext;
import dev.orwell.bootstrap.auth.RequireAuthentication;
import dev.orwell.google.gmail.MailParser;
import dev.orwell.google.gmail.MailPayloads;
import dev.orwell.google.gmail.entity.EmailAttachmentEntity;
import dev.orwell.google.gmail.entity.EmailMessageEntity;
import dev.orwell.google.gmail.entity.EmailRawSourceEntity;
import dev.orwell.google.gmail.entity.UserEntity;
import dev.orwell.google.gmail.repository.EmailAttachmentRepository;
import dev.orwell.google.gmail.repository.EmailMessageRepository;
import dev.orwell.google.gmail.repository.EmailRawSourceRepository;
import dev.orwell.google.gmail.repository.UserRepository;
import jakarta.mail.Part;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

/**
 * Read-only access to mail stored by {@link dev.orwell.google.gmail.ImapMailPoller}.
 *
 * <p>Which mailbox a request reads is derived from the authenticated caller: the bearer token's
 * client id is matched against {@code users.client_id}. There is deliberately no parameter that
 * selects a user, so one consumer cannot read another's mail even by guessing an id — the only
 * mailbox reachable is the one the caller's own identity owns. That holds for a single message and
 * for an attachment too: both are looked up by {@code (id, userId)} rather than by id alone, so a
 * guessed id belonging to someone else is indistinguishable from one that does not exist.
 *
 * <p>{@code id} (see {@link dev.orwell.google.gmail.entity.EmailMessageEntity}) is assigned in
 * insertion order, so it doubles as a consumption cursor: a consumer polls {@code GET
 * /mails/latest} to get started, then repeatedly calls {@code GET /mails/latest?checkpoint=<last
 * id it saw>} to fetch only what arrived since.
 */
@RestController
@RequestMapping("${gmail.route-prefix:}/mails")
@RequireAuthentication
public class MailController {
    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 500;

    private final EmailMessageRepository repository;
    private final EmailAttachmentRepository attachments;
    private final EmailRawSourceRepository rawSources;
    private final UserRepository users;
    private final MailPayloads payloads;
    private final ObjectProvider<AuthenticationContext> authenticationContextProvider;

    public MailController(
            EmailMessageRepository repository,
            EmailAttachmentRepository attachments,
            EmailRawSourceRepository rawSources,
            UserRepository users,
            MailPayloads payloads,
            ObjectProvider<AuthenticationContext> authenticationContextProvider) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.attachments = Objects.requireNonNull(attachments, "attachments");
        this.rawSources = Objects.requireNonNull(rawSources, "rawSources");
        this.users = Objects.requireNonNull(users, "users");
        this.payloads = Objects.requireNonNull(payloads, "payloads");
        this.authenticationContextProvider =
                Objects.requireNonNull(authenticationContextProvider, "authenticationContextProvider");
    }

    /** The caller's most recently received mail first, bounded by {@code limit} (default 50, max 500). */
    @GetMapping
    public List<MailResponse> listMails(@RequestParam(name = "limit", required = false) Integer limit) {
        Pageable page = PageRequest.of(0, boundedLimit(limit));
        return payloads.responses(
                repository.findAllByUserIdOrderByIdDesc(currentUser().getId(), page).getContent());
    }

    /**
     * With no {@code checkpoint}: the caller's single most recent mail, or {@code 204 No Content}
     * if their mailbox is empty. With {@code checkpoint=<id>}: every mail of theirs with {@code id}
     * greater than it, oldest first, bounded by {@code limit} (default 50, max 500) — the
     * incremental-consumption path.
     */
    @GetMapping("/latest")
    public ResponseEntity<?> latest(
            @RequestParam(name = "checkpoint", required = false) Long checkpoint,
            @RequestParam(name = "limit", required = false) Integer limit) {
        Long userId = currentUser().getId();
        if (checkpoint == null) {
            return repository.findTopByUserIdOrderByIdDesc(userId)
                    .<ResponseEntity<?>>map(entity -> ResponseEntity.ok(payloads.response(entity)))
                    .orElseGet(() -> ResponseEntity.noContent().build());
        }
        Pageable page = PageRequest.of(0, boundedLimit(limit));
        return ResponseEntity.ok(payloads.responses(
                repository.findByUserIdAndIdGreaterThanOrderByIdAsc(userId, checkpoint, page)));
    }

    /** One of the caller's messages by id, with its full headers and attachment index. */
    @GetMapping("/{id}")
    public MailResponse mail(@PathVariable("id") Long id) {
        return payloads.response(ownedMail(id));
    }

    /**
     * The decoded bytes of one attachment, served from the message's stored raw source.
     *
     * <p>Streamed out of the archive rather than out of a column of its own: the bytes are stored
     * once, as part of the message they arrived in, so this is the only place a second copy could
     * have lived. A message stored above {@code GMAIL_MAX_MESSAGE_BYTES} has no archive at all,
     * which is a {@code 409} rather than a {@code 404} — the attachment is real and described in
     * the message's index; what is missing is the content, and that distinction is what tells an
     * operator to raise the cap rather than hunt for a bad id.
     */
    @GetMapping("/{id}/attachments/{n}")
    public ResponseEntity<byte[]> attachment(@PathVariable("id") Long id, @PathVariable("n") int n) {
        EmailMessageEntity mail = ownedMail(id);
        EmailAttachmentEntity attachment = attachments.findByMessageIdAndPartIndex(mail.getId(), n)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "No such attachment on this message."));
        EmailRawSourceEntity source = rawSources.findByMessageId(mail.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
                        "This message exceeded the size cap, so its content was not stored and its "
                                + "attachments cannot be downloaded."));

        byte[] content = decode(source, attachment);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(mediaType(attachment.getMimeType()));
        // Through the builder so a filename carrying a quote, newline or non-ASCII character is
        // encoded rather than injected into the header.
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename(attachment.getFilename() == null
                        ? "attachment-" + n : attachment.getFilename(), StandardCharsets.UTF_8)
                .build());
        return new ResponseEntity<>(content, headers, HttpStatus.OK);
    }

    private static byte[] decode(EmailRawSourceEntity source, EmailAttachmentEntity attachment) {
        try {
            Part part = MailParser.partAt(source.getContent(), attachment.getPartPath());
            try (InputStream stream = part.getInputStream()) {
                return stream.readAllBytes();
            }
        } catch (Exception exception) {
            // The index and the archive disagree about this message's shape. Nothing the caller
            // did causes this, and no retry fixes it, so report it as ours.
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Could not read this attachment out of the stored message source.", exception);
        }
    }

    private static MediaType mediaType(String mimeType) {
        try {
            return MediaType.parseMediaType(mimeType);
        } catch (RuntimeException exception) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }

    /**
     * A message by id, but only the caller's own. A 404 for someone else's id as well as for a
     * nonexistent one, so the route cannot be used to learn which ids are taken.
     */
    private EmailMessageEntity ownedMail(Long id) {
        return repository.findByIdAndUserId(id, currentUser().getId())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "No such message."));
    }

    /**
     * The user the authenticated caller owns. {@link RequireAuthentication} has already rejected
     * unauthenticated requests, so reaching this with no matching row means a valid client id that
     * no mailbox belongs to — a configuration gap, reported as 403 rather than 404 so it cannot be
     * used to probe which client ids exist.
     */
    private UserEntity currentUser() {
        String clientId = authenticationContextProvider.getObject().clientId();
        return users.findByClientId(clientId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.FORBIDDEN, "No mailbox is registered for this client."));
    }

    private static int boundedLimit(Integer requested) {
        if (requested == null || requested <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(requested, MAX_LIMIT);
    }
}
