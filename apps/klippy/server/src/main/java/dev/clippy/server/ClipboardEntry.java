package dev.clippy.server;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "clipboard_entries", indexes = {
        @Index(name = "idx_clipboard_client_timestamp", columnList = "client_id, entry_timestamp")
})
public class ClipboardEntry {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "client_id", nullable = false, length = 128)
    private String clientId;

    @Column(name = "content", nullable = false, columnDefinition = "text")
    private String content;

    @Column(name = "entry_timestamp", nullable = false)
    private Instant timestamp;

    protected ClipboardEntry() {
    }

    public ClipboardEntry(String clientId, String content, Instant timestamp) {
        this.clientId = clientId;
        this.content = content;
        this.timestamp = timestamp;
    }

    public Long getId() {
        return id;
    }

    public String getClientId() {
        return clientId;
    }

    public String getContent() {
        return content;
    }

    public Instant getTimestamp() {
        return timestamp;
    }
}
