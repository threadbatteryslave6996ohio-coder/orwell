package dev.orwell.insta.graph;

import dev.orwell.insta.instagram.InstagramAccount;
import dev.orwell.insta.instagram.InstagramProfile;
import dev.orwell.logging.Logger;
import dev.orwell.primitives.Sha256;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Writes what a lookup said about one account: its identity, the handle it was using, its bio, and
 * its profile picture.
 *
 * <p>Every table here is a history: a repeated observation bumps {@code last_seen_at} rather than
 * inserting a duplicate, and a changed value inserts a new row beside the old one. Nothing is ever
 * overwritten, so "what did this bio say in March" stays answerable.
 *
 * <p>What can be written depends on where the account came from, and the difference is not
 * cosmetic. A follower-list row carries id, username and a picture URL. Only a {@code profile}
 * lookup carries the <em>bio</em> — so {@link #record(InstagramAccount, Instant)} deliberately
 * cannot write one, and bios fill in only for accounts you pay to profile individually.
 */
public final class AccountWriter {
    private final Connection connection;
    private final PictureSource pictures;
    private final PictureStore store;
    private final Logger logger;

    public AccountWriter(
            Connection connection, PictureSource pictures, PictureStore store, Logger logger) {
        this.connection = Objects.requireNonNull(connection, "connection");
        this.pictures = Objects.requireNonNull(pictures, "pictures");
        this.store = Objects.requireNonNull(store, "store");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    /** Everything a {@code profile} lookup knows, bio and picture included. */
    public void record(InstagramProfile profile, Instant observedAt) throws SQLException {
        if (!hasId(profile.id())) {
            logger.warn("Skipped a profile with no Instagram id.",
                    Map.of("username", String.valueOf(profile.username())));
            return;
        }
        upsertAccount(profile.id(), observedAt);
        upsertUsername(profile.id(), profile.username(), observedAt);
        // Null means the source carried no bio at all; "" means it carried an empty one. Only the
        // second is a fact about the account, so only the second gets a row.
        if (profile.biography() != null) {
            upsertBio(profile.id(), profile.biography(), observedAt);
        }
        recordPicture(profile.id(), profile.profilePicUrl(), observedAt);
    }

    /** What a follower-list row knows: identity, handle, picture. No bio exists to write. */
    public void record(InstagramAccount account, Instant observedAt) throws SQLException {
        if (!hasId(account.id())) {
            return;
        }
        upsertAccount(account.id(), observedAt);
        upsertUsername(account.id(), account.username(), observedAt);
        recordPicture(account.id(), account.profilePicUrl(), observedAt);
    }

    /** Identity only — used for the far end of an edge before anything else is known about it. */
    public void upsertAccount(String accountId, Instant observedAt) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO account (id, added) VALUES (?, ?) ON CONFLICT (id) DO NOTHING")) {
            statement.setString(1, accountId);
            statement.setTimestamp(2, Timestamp.from(observedAt));
            statement.executeUpdate();
        }
    }

    public void upsertUsername(String accountId, String username, Instant observedAt)
            throws SQLException {
        if (username == null || username.isBlank()) {
            return;
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO account_username (account_id, username, first_seen_at, last_seen_at)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (account_id, username)
                DO UPDATE SET last_seen_at = EXCLUDED.last_seen_at""")) {
            statement.setString(1, accountId);
            statement.setString(2, username);
            statement.setTimestamp(3, Timestamp.from(observedAt));
            statement.setTimestamp(4, Timestamp.from(observedAt));
            statement.executeUpdate();
        }
    }

    private void upsertBio(String accountId, String bio, Instant observedAt) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO account_bio (account_id, bio_hash, bio, first_seen_at, last_seen_at)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (account_id, bio_hash)
                DO UPDATE SET last_seen_at = EXCLUDED.last_seen_at""")) {
            statement.setString(1, accountId);
            statement.setString(2, Sha256.hex(bio.getBytes(StandardCharsets.UTF_8)));
            statement.setString(3, bio);
            statement.setTimestamp(4, Timestamp.from(observedAt));
            statement.setTimestamp(5, Timestamp.from(observedAt));
            statement.executeUpdate();
        }
    }

    /**
     * Downloads the picture, identifies it by the hash of its bytes, and stores it only if those
     * exact bytes are not already in the bucket.
     *
     * <p>The URL cannot be used for any of this: Instagram signs it, so it differs on every scrape
     * even when the image has not changed. Hashing the bytes is the only way to tell a new picture
     * from the same one at a new address.
     *
     * <p>A failure here is logged and dropped. An expired CDN link or an unreachable bucket is not
     * a reason to lose the follow-graph data the same sync just collected.
     */
    private void recordPicture(String accountId, String sourceUrl, Instant observedAt) {
        if (sourceUrl == null || sourceUrl.isBlank() || !store.enabled()) {
            return;
        }
        try {
            byte[] bytes = pictures.fetch(sourceUrl);
            String hash = Sha256.hex(bytes);
            // Another account may already have these exact bytes — the default avatar is shared by
            // a great many — in which case the object exists and only the row is new.
            String key = existingKey(hash).orElse(null);
            if (key == null) {
                key = store.put(hash, bytes);
            }
            upsertPicture(accountId, hash, key, sourceUrl, bytes.length, observedAt);
        } catch (Exception exception) {
            logger.warn("Could not store a profile picture.", Map.of(
                    "accountId", accountId, "error", exception.toString()));
        }
    }

    private Optional<String> existingKey(String contentHash) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT bucket_key FROM account_profile_picture WHERE content_hash = ? LIMIT 1")) {
            statement.setString(1, contentHash);
            try (ResultSet results = statement.executeQuery()) {
                return results.next() ? Optional.of(results.getString(1)) : Optional.empty();
            }
        }
    }

    private void upsertPicture(
            String accountId, String hash, String key, String sourceUrl, int bytes,
            Instant observedAt) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO account_profile_picture
                    (account_id, content_hash, bucket_key, source_url, byte_size,
                     first_seen_at, last_seen_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (account_id, content_hash)
                DO UPDATE SET last_seen_at = EXCLUDED.last_seen_at,
                              source_url = EXCLUDED.source_url""")) {
            statement.setString(1, accountId);
            statement.setString(2, hash);
            statement.setString(3, key);
            statement.setString(4, sourceUrl);
            statement.setInt(5, bytes);
            statement.setTimestamp(6, Timestamp.from(observedAt));
            statement.setTimestamp(7, Timestamp.from(observedAt));
            statement.executeUpdate();
        }
    }

    private static boolean hasId(String id) {
        return id != null && !id.isBlank();
    }
}
