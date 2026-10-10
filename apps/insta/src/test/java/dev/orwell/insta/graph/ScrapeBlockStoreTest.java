package dev.orwell.insta.graph;

import dev.orwell.insta.instagram.ConnectionType;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The block list, against a real Postgres — the direction key, the upsert's insert/update answer,
 * and the handle-to-id resolution that lets a block be placed without paying Apify for a lookup.
 */
class ScrapeBlockStoreTest extends GraphTest {
    private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.MILLIS);

    @Test
    void blocksOneDirectionAndLeavesTheOtherWalkable() throws Exception {
        givenAccounts();
        ScrapeBlockStore blocks = new ScrapeBlockStore(connection);

        assertThat(blocks.block("1", ConnectionType.FOLLOWERS, "shop, list is noise", NOW)).isTrue();

        assertThat(blocks.find("1", ConnectionType.FOLLOWERS)).hasValueSatisfying(block -> {
            assertThat(block.reason()).isEqualTo("shop, list is noise");
            assertThat(block.blockedAt()).isEqualTo(NOW);
        });
        // The whole reason direction is on the row rather than the account.
        assertThat(blocks.find("1", ConnectionType.FOLLOWING)).isEmpty();
        assertThat(blocks.find("2", ConnectionType.FOLLOWERS)).isEmpty();
    }

    /** Blocking twice must update rather than fail, and must say which of the two it did. */
    @Test
    void reportsWhetherTheBlockWasNew() throws Exception {
        givenAccounts();
        ScrapeBlockStore blocks = new ScrapeBlockStore(connection);

        assertThat(blocks.block("1", ConnectionType.FOLLOWERS, "first", NOW)).isTrue();
        assertThat(blocks.block("1", ConnectionType.FOLLOWERS, "second", NOW)).isFalse();

        assertThat(count("scrape_block")).isEqualTo(1);
        assertThat(blocks.find("1", ConnectionType.FOLLOWERS).orElseThrow().reason())
                .isEqualTo("second");
    }

    @Test
    void unblockingSaysWhetherThereWasAnythingToLift() throws Exception {
        givenAccounts();
        ScrapeBlockStore blocks = new ScrapeBlockStore(connection);
        blocks.block("1", ConnectionType.FOLLOWERS, null, NOW);

        assertThat(blocks.unblock("1", ConnectionType.FOLLOWERS)).isTrue();
        assertThat(blocks.unblock("1", ConnectionType.FOLLOWERS)).isFalse();
        assertThat(blocks.find("1", ConnectionType.FOLLOWERS)).isEmpty();
    }

    /** The handle in the listing is the newest one, not whichever row the join reached first. */
    @Test
    void listsBlocksUnderTheMostRecentHandle() throws Exception {
        givenAccounts();
        ScrapeBlockStore blocks = new ScrapeBlockStore(connection);
        blocks.block("1", ConnectionType.FOLLOWERS, "noisy", NOW);
        blocks.block("1", ConnectionType.FOLLOWING, null, NOW);

        List<ScrapeBlockStore.Block> all = blocks.all();

        assertThat(all).extracting(ScrapeBlockStore.Block::label)
                .containsExactly("shop_now", "shop_now");
        assertThat(all).extracting(ScrapeBlockStore.Block::direction)
                .containsExactly(ConnectionType.FOLLOWERS, ConnectionType.FOLLOWING);
    }

    /** Resolution is case-insensitive, and a renamed account is found under its current handle. */
    @Test
    void resolvesAHandleToAnAccountIdWithoutApify() throws Exception {
        givenAccounts();
        ScrapeBlockStore blocks = new ScrapeBlockStore(connection);

        assertThat(blocks.accountIdFor("Shop_Now")).contains("1");
        // The handle this account used to have still points at it.
        assertThat(blocks.accountIdFor("oldshop")).contains("1");
        assertThat(blocks.accountIdFor("never-seen")).isEmpty();
    }

    /** Deleting an account takes its blocks with it rather than orphaning them. */
    @Test
    void cascadesWithTheAccount() throws Exception {
        givenAccounts();
        new ScrapeBlockStore(connection).block("1", ConnectionType.FOLLOWERS, null, NOW);

        try (Statement statement = connection.createStatement()) {
            statement.execute("DELETE FROM account WHERE id = '1'");
        }

        assertThat(count("scrape_block")).isZero();
    }

    private void givenAccounts() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    INSERT INTO account (id) VALUES ('1'), ('2');
                    INSERT INTO account_username
                        (account_id, username, first_seen_at, last_seen_at)
                    VALUES
                        ('1', 'oldshop', now() - interval '30 days', now() - interval '10 days'),
                        ('1', 'shop_now', now() - interval '9 days',  now()),
                        ('2', 'someone',  now() - interval '9 days',  now());
                    """);
        }
    }
}
