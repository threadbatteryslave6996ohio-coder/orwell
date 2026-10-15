package dev.orwell.insta.graph;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Every account the graph already holds, in the order {@code insta sync-all} should visit them.
 *
 * <p><b>Least recently synced first, never-synced before all of them.</b> A run over the whole
 * table is long and every step of it is billed, so it is the run most likely to be cut short — by
 * an exhausted Apify balance, a rate limit, or a {@code --max-accounts} cap. Ordering by the last
 * {@code scrape_run} means the next run starts where the last one stopped rather than re-paying for
 * the same head of the list every night.
 *
 * <p>An account is only listed if a handle has been recorded for it: the profile lookup takes a
 * username, and an id alone cannot be turned into one without paying for it. Most recently seen
 * handle wins, for the same reason as {@link ScrapeBlockStore#accountIdFor}.
 */
public final class KnownAccounts {
    private final Connection connection;

    public KnownAccounts(Connection connection) {
        this.connection = Objects.requireNonNull(connection, "connection");
    }

    /** @param max at most this many, or every one when null */
    public List<Entry> dueForSync(Integer max) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT a.id, u.username
                FROM account a
                JOIN LATERAL (
                    SELECT username FROM account_username
                    WHERE account_id = a.id
                    ORDER BY last_seen_at DESC LIMIT 1
                ) u ON TRUE
                LEFT JOIN LATERAL (
                    SELECT max(finished_at) AS at FROM scrape_run WHERE subject_id = a.id
                ) r ON TRUE
                ORDER BY r.at NULLS FIRST, a.id
                LIMIT ?""")) {
            // LIMIT NULL is Postgres for "no limit".
            statement.setObject(1, max, Types.INTEGER);
            try (ResultSet results = statement.executeQuery()) {
                List<Entry> entries = new ArrayList<>();
                while (results.next()) {
                    entries.add(new Entry(results.getString(1), results.getString(2)));
                }
                return entries;
            }
        }
    }

    /** Accounts {@link #dueForSync} cannot list, so a run can say how many it left out. */
    public int withoutHandle() throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT count(*) FROM account a
                WHERE NOT EXISTS (SELECT 1 FROM account_username u WHERE u.account_id = a.id)""");
             ResultSet results = statement.executeQuery()) {
            return results.next() ? results.getInt(1) : 0;
        }
    }

    public record Entry(String accountId, String username) {
    }
}
