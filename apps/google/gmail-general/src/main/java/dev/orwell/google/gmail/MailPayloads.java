package dev.orwell.google.gmail;

import dev.orwell.google.gmail.controller.MailResponse;
import dev.orwell.google.gmail.entity.EmailAttachmentEntity;
import dev.orwell.google.gmail.entity.EmailHeaderEntity;
import dev.orwell.google.gmail.entity.EmailMessageEntity;
import dev.orwell.google.gmail.repository.EmailAttachmentRepository;
import dev.orwell.google.gmail.repository.EmailHeaderRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Assembles stored rows into the two payload shapes — {@link MailResponse} for the read API and
 * {@link GmailMessage} for webhook delivery — so that a consumer sees the same message whichever
 * way it arrives.
 *
 * <p>Every method that takes a list loads headers and attachments for the whole list in one query
 * each. That is the point of the class: a page of fifty messages costs three queries, not a
 * hundred and one. Callers that already hold the rows (the ingestion path has just written them)
 * pass them in and skip the read entirely.
 */
@Component
public class MailPayloads {
    private final EmailHeaderRepository headers;
    private final EmailAttachmentRepository attachments;
    private final String attachmentUrlPrefix;

    public MailPayloads(
            EmailHeaderRepository headers,
            EmailAttachmentRepository attachments,
            @Value("${gmail.route-prefix:}") String routePrefix,
            @Value("${gmail.public-base-url:}") String publicBaseUrl) {
        this.headers = Objects.requireNonNull(headers, "headers");
        this.attachments = Objects.requireNonNull(attachments, "attachments");
        // Trailing slashes would produce "//mails"; a receiver treating that as a path is entitled
        // to resolve it as a protocol-relative URL, so strip them once here rather than per URL.
        this.attachmentUrlPrefix = stripTrailingSlash(publicBaseUrl) + stripTrailingSlash(routePrefix);
    }

    public List<MailResponse> responses(List<EmailMessageEntity> mails) {
        Loaded loaded = load(mails);
        return mails.stream().map(mail -> response(mail, loaded)).toList();
    }

    public MailResponse response(EmailMessageEntity mail) {
        return response(mail, load(List.of(mail)));
    }

    public List<GmailMessage> payloads(List<EmailMessageEntity> mails, String account) {
        Loaded loaded = load(mails);
        return mails.stream().map(mail -> payload(mail, account, loaded)).toList();
    }

    public GmailMessage payload(EmailMessageEntity mail, String account) {
        return payload(mail, account, load(List.of(mail)));
    }

    /** For the ingestion path, which wrote these rows a moment ago and need not read them back. */
    public GmailMessage payload(EmailMessageEntity mail, String account,
            List<EmailHeaderEntity> mailHeaders, List<EmailAttachmentEntity> mailAttachments) {
        return new GmailMessage(mail.getMessageId(), account, mail.getSubject(),
                mail.getFromAddress(), mail.getToAddress(), mail.getReceivedAt().toEpochMilli(),
                mail.getBody(), mail.getBodyHtml(), headerMap(mailHeaders),
                refs(mail, mailAttachments), mail.isTruncated());
    }

    public String attachmentUrl(Long mailId, int partIndex) {
        return "%s/mails/%d/attachments/%d".formatted(attachmentUrlPrefix, mailId, partIndex);
    }

    private MailResponse response(EmailMessageEntity mail, Loaded loaded) {
        return new MailResponse(mail.getId(), mail.getMessageId(), mail.getSubject(),
                mail.getFromAddress(), mail.getToAddress(), mail.getReceivedAt(),
                mail.getBody(), mail.getBodyHtml(), headerMap(loaded.headersOf(mail)),
                refs(mail, loaded.attachmentsOf(mail)), mail.getRawSizeBytes(), mail.isTruncated());
    }

    private GmailMessage payload(EmailMessageEntity mail, String account, Loaded loaded) {
        return payload(mail, account, loaded.headersOf(mail), loaded.attachmentsOf(mail));
    }

    /**
     * Names in first-appearance order, values in the order the message carried them.
     *
     * <p>Grouped case-insensitively under the spelling first seen: a consumer looking for
     * {@code Cc} should not have to know whether this sender wrote {@code CC}. The exact original
     * spelling of every occurrence survives in {@code email_headers} regardless.
     */
    private static Map<String, List<String>> headerMap(List<EmailHeaderEntity> rows) {
        Map<String, String> canonical = new LinkedHashMap<>();
        Map<String, List<String>> byName = new LinkedHashMap<>();
        for (EmailHeaderEntity row : rows) {
            String key = canonical.computeIfAbsent(
                    row.getName().toLowerCase(Locale.ROOT), ignored -> row.getName());
            byName.computeIfAbsent(key, ignored -> new ArrayList<>()).add(row.getValue());
        }
        return byName;
    }

    private List<AttachmentRef> refs(EmailMessageEntity mail, List<EmailAttachmentEntity> rows) {
        return rows.stream().map(row -> new AttachmentRef(
                row.getPartIndex(), row.getFilename(), row.getMimeType(), row.getSizeBytes(),
                row.getContentId(), row.isInline(), !mail.isTruncated(),
                attachmentUrl(mail.getId(), row.getPartIndex()))).toList();
    }

    private Loaded load(List<EmailMessageEntity> mails) {
        List<Long> ids = mails.stream().map(EmailMessageEntity::getId).filter(Objects::nonNull).toList();
        if (ids.isEmpty()) {
            return new Loaded(Map.of(), Map.of());
        }
        Map<Long, List<EmailHeaderEntity>> headerRows = new LinkedHashMap<>();
        for (EmailHeaderEntity row : headers.findByMessageIdInOrderByMessageIdAscOrdinalAsc(ids)) {
            headerRows.computeIfAbsent(row.getMessage().getId(), ignored -> new ArrayList<>()).add(row);
        }
        Map<Long, List<EmailAttachmentEntity>> attachmentRows = new LinkedHashMap<>();
        for (EmailAttachmentEntity row
                : attachments.findByMessageIdInOrderByMessageIdAscPartIndexAsc(ids)) {
            attachmentRows.computeIfAbsent(row.getMessage().getId(), ignored -> new ArrayList<>())
                    .add(row);
        }
        return new Loaded(headerRows, attachmentRows);
    }

    private static String stripTrailingSlash(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String trimmed = value.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    /** One page's headers and attachments, grouped by message id. */
    private record Loaded(
            Map<Long, List<EmailHeaderEntity>> headers,
            Map<Long, List<EmailAttachmentEntity>> attachments) {

        List<EmailHeaderEntity> headersOf(EmailMessageEntity mail) {
            return headers.getOrDefault(mail.getId(), List.of());
        }

        List<EmailAttachmentEntity> attachmentsOf(EmailMessageEntity mail) {
            return attachments.getOrDefault(mail.getId(), List.of());
        }
    }
}
