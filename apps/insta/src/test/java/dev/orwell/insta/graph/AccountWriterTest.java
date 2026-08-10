package dev.orwell.insta.graph;

import dev.orwell.insta.instagram.InstagramAccount;
import dev.orwell.insta.instagram.InstagramProfile;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** What a lookup gets recorded as, and what a second identical lookup does not duplicate. */
class AccountWriterTest extends GraphTest {

    @Test
    void recordsIdentityUsernameAndBioFromAProfile() throws Exception {
        writer().record(profile("1", "nasa", "Explore the universe"), Instant.now());

        assertThat(strings("SELECT id FROM account")).containsExactly("1");
        assertThat(strings("SELECT username FROM account_username")).containsExactly("nasa");
        assertThat(strings("SELECT bio FROM account_bio")).containsExactly("Explore the universe");
    }

    @Test
    void bumpsTheTimestampInsteadOfDuplicatingAnUnchangedObservation() throws Exception {
        Instant first = Instant.now().minus(1, ChronoUnit.DAYS);
        writer().record(profile("1", "nasa", "Explore"), first);

        Instant second = Instant.now();
        writer().record(profile("1", "nasa", "Explore"), second);

        assertThat(count("account_username")).isEqualTo(1);
        assertThat(count("account_bio")).isEqualTo(1);
        assertThat(instant("SELECT first_seen_at FROM account_bio"))
                .isCloseTo(first, within());
        assertThat(instant("SELECT last_seen_at FROM account_bio"))
                .isCloseTo(second, within());
    }

    /** A rename keeps the account and adds a handle; it does not create a second account. */
    @Test
    void keepsBothHandlesWhenAnAccountIsRenamed() throws Exception {
        writer().record(profile("1", "old_handle", null), Instant.now().minus(1, ChronoUnit.DAYS));

        writer().record(profile("1", "new_handle", null), Instant.now());

        assertThat(count("account")).isEqualTo(1);
        assertThat(strings("SELECT username FROM account_username ORDER BY last_seen_at"))
                .containsExactly("old_handle", "new_handle");
    }

    @Test
    void keepsBothBiosWhenOneChanges() throws Exception {
        writer().record(profile("1", "nasa", "First"), Instant.now().minus(1, ChronoUnit.DAYS));

        writer().record(profile("1", "nasa", "Second"), Instant.now());

        assertThat(strings("SELECT bio FROM account_bio ORDER BY last_seen_at"))
                .containsExactly("First", "Second");
    }

    /** No bio observed and an empty bio are different facts, and only one of them is a row. */
    @Test
    void tellsAnAbsentBioApartFromAnEmptyOne() throws Exception {
        writer().record(profile("1", "nobio", null), Instant.now());
        assertThat(count("account_bio")).isZero();

        writer().record(profile("2", "emptybio", ""), Instant.now());
        assertThat(strings("SELECT bio FROM account_bio")).containsExactly("");
    }

    /** A follower-list row carries no bio, so it must never be able to write one. */
    @Test
    void writesNoBioForAFollowerListRow() throws Exception {
        writer().record(new InstagramAccount("1", "alice", "Alice", null, null, null), Instant.now());

        assertThat(strings("SELECT username FROM account_username")).containsExactly("alice");
        assertThat(count("account_bio")).isZero();
    }

    /** An account with no id cannot be keyed, so nothing is written rather than something wrong. */
    @Test
    void skipsAnAccountWithNoInstagramId() throws Exception {
        writer().record(profile(null, "ghost", "hi"), Instant.now());

        assertThat(count("account")).isZero();
    }

    // ─────────────────────────────────────────────────────────── pictures

    @Test
    void downloadsHashesAndStoresAProfilePicture(@org.junit.jupiter.api.io.TempDir Path directory)
            throws Exception {
        Recording store = new Recording(directory);

        writer(bytes("image-one"), store).record(
                profileWithPicture("1", "nasa", "https://cdn/one?sig=a"), Instant.now());

        assertThat(count("account_profile_picture")).isEqualTo(1);
        assertThat(store.stored).hasSize(1);
        assertThat(strings("SELECT bucket_key FROM account_profile_picture").get(0))
                .startsWith("insta/avatars/").endsWith(".jpg");
        assertThat(Files.exists(directory.resolve(store.stored.get(0)))).isTrue();
    }

    /**
     * The heart of it: Instagram signs its CDN URLs, so the same picture arrives at a new address
     * every scrape. Keyed on the URL this would record a new picture daily and re-upload forever.
     */
    @Test
    void treatsTheSameImageAtANewUrlAsUnchanged(@org.junit.jupiter.api.io.TempDir Path directory)
            throws Exception {
        Recording store = new Recording(directory);
        AccountWriter writer = writer(bytes("image-one"), store);

        writer.record(profileWithPicture("1", "nasa", "https://cdn/one?sig=a"), Instant.now());
        writer.record(profileWithPicture("1", "nasa", "https://cdn/one?sig=DIFFERENT"), Instant.now());

        assertThat(count("account_profile_picture")).isEqualTo(1);
        assertThat(store.stored).hasSize(1);          // uploaded once, not twice
    }

    @Test
    void recordsASecondPictureWhenTheImageActuallyChanges(
            @org.junit.jupiter.api.io.TempDir Path directory) throws Exception {
        Recording store = new Recording(directory);

        writer(bytes("image-one"), store)
                .record(profileWithPicture("1", "nasa", "https://cdn/one"), Instant.now());
        writer(bytes("image-two"), store)
                .record(profileWithPicture("1", "nasa", "https://cdn/two"), Instant.now());

        assertThat(count("account_profile_picture")).isEqualTo(2);
        assertThat(store.stored).hasSize(2);
    }

    /** The default avatar is shared by a great many accounts; it should be one object. */
    @Test
    void storesSharedImageBytesOnlyOnce(@org.junit.jupiter.api.io.TempDir Path directory)
            throws Exception {
        Recording store = new Recording(directory);
        AccountWriter writer = writer(bytes("default-avatar"), store);

        writer.record(profileWithPicture("1", "alice", "https://cdn/a"), Instant.now());
        writer.record(profileWithPicture("2", "bob", "https://cdn/b"), Instant.now());

        assertThat(count("account_profile_picture")).isEqualTo(2);   // a row each
        assertThat(store.stored).hasSize(1);                         // one object
        assertThat(strings("SELECT DISTINCT bucket_key FROM account_profile_picture")).hasSize(1);
    }

    /** An expired CDN link must not cost you the follow data collected in the same sync. */
    @Test
    void keepsTheAccountWhenThePictureCannotBeFetched(
            @org.junit.jupiter.api.io.TempDir Path directory) throws Exception {
        AccountWriter writer = new AccountWriter(connection, url -> {
            throw new IllegalStateException("410 Gone");
        }, new Recording(directory), NO_OP_LOGGER);

        writer.record(profileWithPicture("1", "nasa", "https://cdn/expired"), Instant.now());

        assertThat(strings("SELECT id FROM account")).containsExactly("1");
        assertThat(count("account_profile_picture")).isZero();
    }

    /** With storage off, nothing is downloaded at all — not fetched and thrown away. */
    @Test
    void neverDownloadsWhenPictureStorageIsDisabled() throws Exception {
        AccountWriter writer = new AccountWriter(connection, url -> {
            throw new AssertionError("should not have downloaded anything");
        }, new DisabledPictureStore(), NO_OP_LOGGER);

        writer.record(profileWithPicture("1", "nasa", "https://cdn/one"), Instant.now());

        assertThat(count("account_profile_picture")).isZero();
        assertThat(strings("SELECT id FROM account")).containsExactly("1");
    }

    // ─────────────────────────────────────────────────────────── helpers

    private static org.assertj.core.data.TemporalUnitOffset within() {
        return org.assertj.core.api.Assertions.within(2, ChronoUnit.SECONDS);
    }

    private AccountWriter writer() {
        return new AccountWriter(connection, url -> {
            throw new UnsupportedOperationException("no pictures in this test");
        }, new DisabledPictureStore(), NO_OP_LOGGER);
    }

    private AccountWriter writer(byte[] image, PictureStore store) {
        return new AccountWriter(connection, url -> image, store, NO_OP_LOGGER);
    }

    private static byte[] bytes(String content) {
        return content.getBytes(StandardCharsets.UTF_8);
    }

    private static InstagramProfile profile(String id, String username, String bio) {
        return new InstagramProfile(
                id, username, null, bio, null, null, null, null, null, null, List.of());
    }

    private static InstagramProfile profileWithPicture(String id, String username, String url) {
        return new InstagramProfile(
                id, username, null, null, null, null, null, null, null, url, List.of());
    }

    /** A filesystem store that remembers which keys it actually wrote. */
    private static final class Recording implements PictureStore {
        private final FilesystemPictureStore delegate;
        private final List<String> stored = new ArrayList<>();

        Recording(Path root) {
            this.delegate = new FilesystemPictureStore(root);
        }

        @Override
        public String put(String contentHash, byte[] bytes) throws Exception {
            String key = delegate.put(contentHash, bytes);
            stored.add(key);
            return key;
        }
    }
}
