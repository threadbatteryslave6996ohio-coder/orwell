package dev.orwell.insta.graph;

import dev.orwell.insta.InstaEnvs;
import dev.orwell.insta.apify.ApifyClient;
import dev.orwell.insta.apify.ApifyException;
import dev.orwell.insta.instagram.ConnectionType;
import dev.orwell.insta.instagram.ConnectionsPage;
import dev.orwell.insta.instagram.InstagramAccount;
import dev.orwell.insta.instagram.InstagramProfile;
import dev.orwell.insta.instagram.InstagramService;
import dev.orwell.insta.instagram.ProfileNotFoundException;
import dev.orwell.insta.support.InMemoryScrapeCache;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code insta sync-all}: every account already in the graph, both lists, one transaction each —
 * and a failure that is about one account must not cost the rest of the run.
 */
class SyncAllTest extends GraphTest {
    private static final List<ConnectionType> BOTH =
            List.of(ConnectionType.FOLLOWERS, ConnectionType.FOLLOWING);

    private final FakeInstagram instagram = new FakeInstagram();
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();

    @Test
    void walksBothListsOfEveryKnownAccount() throws Exception {
        known("1", "alice");
        known("2", "bob");
        instagram.account("1", "alice");
        instagram.account("2", "bob");
        instagram.list("alice", ConnectionType.FOLLOWERS, account("10", "fan"));
        instagram.list("alice", ConnectionType.FOLLOWING, account("2", "bob"));
        instagram.list("bob", ConnectionType.FOLLOWERS, account("1", "alice"));
        instagram.list("bob", ConnectionType.FOLLOWING, account("20", "idol"));

        int code = command().runAll(BOTH, null, null, print(out), print(new ByteArrayOutputStream()));

        assertThat(code).isZero();
        // alice → bob is seen from both ends, and is still one edge.
        assertThat(strings("SELECT follower_id || '>' || followee_id FROM follow_edge"
                + " WHERE active"))
                .containsExactlyInAnyOrder("1>2", "10>1", "2>20");
        assertThat(strings("SELECT subject_id || ':' || direction FROM scrape_run ORDER BY 1"))
                .containsExactly("1:FOLLOWERS", "1:FOLLOWING", "2:FOLLOWERS", "2:FOLLOWING");
        assertThat(text(out)).contains("2 of 2 accounts synced, 0 failed");
    }

    /** Accounts its own walks discover are left for the next run, or one run would never end. */
    @Test
    void visitsOnlyTheAccountsThatWereThereWhenItStarted() throws Exception {
        known("1", "alice");
        instagram.account("1", "alice");
        instagram.list("alice", ConnectionType.FOLLOWERS, account("10", "fan"));
        instagram.list("alice", ConnectionType.FOLLOWING);

        command().runAll(BOTH, null, null, print(out), print(new ByteArrayOutputStream()));

        assertThat(instagram.profilesAsked).containsExactly("alice");
        assertThat(strings("SELECT id FROM account ORDER BY id")).containsExactly("1", "10");
    }

    @Test
    void skipsAnAccountThatFailsAndCarriesOn() throws Exception {
        known("1", "alice");
        known("2", "gone");
        known("3", "carol");
        instagram.account("1", "alice");
        instagram.account("3", "carol");
        for (String username : List.of("alice", "carol")) {
            instagram.list(username, ConnectionType.FOLLOWERS);
            instagram.list(username, ConnectionType.FOLLOWING);
        }

        int code = command().runAll(BOTH, null, null, print(out), print(new ByteArrayOutputStream()));

        assertThat(code).isEqualTo(4);
        assertThat(instagram.profilesAsked).containsExactly("alice", "gone", "carol");
        assertThat(strings("SELECT DISTINCT subject_id FROM scrape_run ORDER BY 1"))
                .containsExactly("1", "3");
        assertThat(text(out)).contains("2 of 3 accounts synced, 1 failed").contains("failed: gone");
    }

    /**
     * An empty balance fails every account after it identically, so the run stops — and what it
     * already did stays, because each account committed on its own.
     */
    @Test
    void stopsOnAnExhaustedBalanceAndKeepsWhatItAlreadySynced() throws Exception {
        known("1", "alice");
        known("2", "bob");
        known("3", "carol");
        instagram.account("1", "alice");
        instagram.list("alice", ConnectionType.FOLLOWERS, account("10", "fan"));
        instagram.list("alice", ConnectionType.FOLLOWING);
        instagram.outOfCreditFor = "bob";

        assertThatThrownBy(() -> command().runAll(
                BOTH, null, null, print(out), print(new ByteArrayOutputStream())))
                .isInstanceOf(ApifyException.class);

        assertThat(instagram.profilesAsked).containsExactly("alice", "bob");
        assertThat(strings("SELECT follower_id FROM follow_edge")).containsExactly("10");
        assertThat(text(out)).contains("stopped after 1 of 3 accounts");
    }

    /** Never-synced accounts first, then the stalest — so a capped run resumes where the last ended. */
    @Test
    void visitsTheLeastRecentlySyncedFirstAndHonoursTheCap() throws Exception {
        known("1", "alice");
        known("2", "bob");
        known("3", "carol");
        execute("INSERT INTO scrape_run (subject_id, direction, started_at, finished_at, walked,"
                        + " complete, seen, skipped_no_id, added, retired) VALUES"
                        + " ('1', 'FOLLOWERS', now() - interval '1 day', now() - interval '1 day',"
                        + "  true, true, 0, 0, 0, 0),"
                        + " ('2', 'FOLLOWERS', now() - interval '3 days', now() - interval '3 days',"
                        + "  true, true, 0, 0, 0, 0)");

        assertThat(new KnownAccounts(connection).dueForSync(2))
                .extracting(KnownAccounts.Entry::username)
                .containsExactly("carol", "bob");
    }

    @Test
    void leavesOutAndCountsAccountsWithNoRecordedHandle() throws Exception {
        known("1", "alice");
        execute("INSERT INTO account (id) VALUES ('99')");

        KnownAccounts known = new KnownAccounts(connection);

        assertThat(known.dueForSync(null)).extracting(KnownAccounts.Entry::accountId)
                .containsExactly("1");
        assertThat(known.withoutHandle()).isEqualTo(1);
    }

    private SyncCommand command() {
        return new SyncCommand(InstaEnvs.from(Map.of(
                "APIFY_TOKEN", "test",
                "INSTA_DATABASE_URL", POSTGRES.getJdbcUrl(),
                "INSTA_DATABASE_USERNAME", POSTGRES.getUsername(),
                "INSTA_DATABASE_PASSWORD", POSTGRES.getPassword())), instagram, NO_OP_LOGGER);
    }

    private void known(String id, String username) throws SQLException {
        execute("INSERT INTO account (id) VALUES ('" + id + "')",
                "INSERT INTO account_username (account_id, username, first_seen_at, last_seen_at)"
                        + " VALUES ('" + id + "', '" + username + "', now(), now())");
    }

    private static InstagramAccount account(String id, String username) {
        return new InstagramAccount(id, username, null, false, false, null);
    }

    private static PrintStream print(ByteArrayOutputStream bytes) {
        return new PrintStream(bytes, true, StandardCharsets.UTF_8);
    }

    private static String text(ByteArrayOutputStream bytes) {
        return bytes.toString(StandardCharsets.UTF_8);
    }

    /**
     * Answers from canned data instead of Apify. An unknown username is a missing account, which
     * is what the profile actor reports for one.
     */
    private static final class FakeInstagram extends InstagramService {
        private final Map<String, InstagramProfile> profiles = new HashMap<>();
        private final Map<String, List<InstagramAccount>> lists = new HashMap<>();
        private final List<String> profilesAsked = new ArrayList<>();
        private String outOfCreditFor;

        FakeInstagram() {
            super(new ApifyClient("http://127.0.0.1:1", "unused", 1, NO_OP_LOGGER),
                    new InMemoryScrapeCache(), "unused", List.of(), 500, 500, NO_OP_LOGGER);
        }

        void account(String id, String username) {
            profiles.put(username, new InstagramProfile(
                    id, username, null, null, 10L, 10L, 0L, false, false, null, List.of()));
        }

        void list(String username, ConnectionType type, InstagramAccount... accounts) {
            lists.put(username + ":" + type, List.of(accounts));
        }

        @Override
        public InstagramProfile profile(String username) {
            profilesAsked.add(username);
            if (username.equals(outOfCreditFor)) {
                throw new ApifyException("out of credit", ApifyException.Kind.OUT_OF_CREDIT);
            }
            InstagramProfile profile = profiles.get(username);
            if (profile == null) {
                throw new ProfileNotFoundException("No public Instagram profile was found.");
            }
            return profile;
        }

        @Override
        public ConnectionsPage connections(
                String username, ConnectionType type, Integer limit, String cursor) {
            List<InstagramAccount> accounts = lists.get(username + ":" + type);
            if (accounts == null) {
                throw new AssertionError("Unexpected walk: " + username + " " + type);
            }
            return new ConnectionsPage(accounts, null, true);
        }
    }
}
