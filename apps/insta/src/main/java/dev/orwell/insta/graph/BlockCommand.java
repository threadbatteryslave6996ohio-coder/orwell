package dev.orwell.insta.graph;

import dev.orwell.env.Env;
import dev.orwell.insta.InstaEnvs;
import dev.orwell.insta.instagram.ConnectionType;
import dev.orwell.logging.Logger;

import java.io.PrintStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * {@code insta block} / {@code insta unblock} / {@code insta blocks} — the accounts a walk leaves
 * alone.
 *
 * <p>Touches Postgres and nothing else. Placing a block costs no Apify credit, which is the whole
 * point: the handle is resolved to an account id out of {@code account_username}, from data a
 * previous walk already paid for.
 *
 * <p>That does mean an account insta has never seen cannot be blocked — there is no id to key the
 * row on, and inventing one is not possible without a lookup. It is not a real restriction: the
 * accounts worth blocking are the ones that turned up in somebody's follower list, which is exactly
 * how they got into {@code account} in the first place.
 */
public final class BlockCommand {
    private static final DateTimeFormatter DAY =
            DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault());

    private final Env env;
    private final Logger logger;

    public BlockCommand(Env env, Logger logger) {
        this.env = Objects.requireNonNull(env, "env");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    /** What the user asked for. {@code username} is null for {@link Action#LIST}. */
    public enum Action { BLOCK, UNBLOCK, LIST }

    /** @return the process exit code. */
    public int run(
            Action action, String username, List<ConnectionType> directions, String reason,
            PrintStream out, PrintStream err) throws Exception {

        String url = env.get(InstaEnvs.INSTA_DATABASE_URL);
        if (url == null || url.isBlank()) {
            err.println("block needs INSTA_DATABASE_URL — the block list lives beside the graph.");
            return 2;
        }

        try (Connection connection = DriverManager.getConnection(
                url,
                env.get(InstaEnvs.INSTA_DATABASE_USERNAME),
                env.get(InstaEnvs.INSTA_DATABASE_PASSWORD))) {

            GraphSchema.apply(connection);
            ScrapeBlockStore blocks = new ScrapeBlockStore(connection);
            if (action == Action.LIST) {
                return list(blocks, out);
            }

            Optional<String> accountId = blocks.accountIdFor(username);
            if (accountId.isEmpty()) {
                err.printf("insta has not seen %s, so there is no account id to block it by. "
                        + "Sync it, or an account it follows, first.%n", username);
                return 2;
            }
            return action == Action.BLOCK
                    ? block(blocks, accountId.get(), username, directions, reason, out)
                    : unblock(blocks, accountId.get(), username, directions, out);
        }
    }

    private int block(
            ScrapeBlockStore blocks, String accountId, String username,
            List<ConnectionType> directions, String reason, PrintStream out) throws Exception {

        Instant now = Instant.now();
        for (ConnectionType direction : directions) {
            boolean added = blocks.block(accountId, direction, reason, now);
            out.printf("%s %s (%s)%s%n",
                    added ? "blocked" : "already blocked, reason updated:",
                    username, name(direction),
                    reason == null || reason.isBlank() ? "" : " — " + reason);
        }
        logger.info("Recorded a scrape block.", Map.of(
                "username", username, "accountId", accountId,
                "directions", directions.toString(), "reason", String.valueOf(reason)));
        return 0;
    }

    private int unblock(
            ScrapeBlockStore blocks, String accountId, String username,
            List<ConnectionType> directions, PrintStream out) throws Exception {

        for (ConnectionType direction : directions) {
            boolean removed = blocks.unblock(accountId, direction);
            out.printf("%s %s (%s)%n",
                    removed ? "unblocked" : "was not blocked:", username, name(direction));
        }
        return 0;
    }

    private int list(ScrapeBlockStore blocks, PrintStream out) throws Exception {
        List<ScrapeBlockStore.Block> all = blocks.all();
        if (all.isEmpty()) {
            // stdout, not stderr: an empty list is the answer, not a failure to produce one.
            out.println("No accounts are blocked.");
            return 0;
        }
        for (ScrapeBlockStore.Block block : all) {
            out.printf("%-24s %-10s %-10s %s%n",
                    block.label(), name(block.direction()), DAY.format(block.blockedAt()),
                    block.reason() == null ? "" : block.reason());
        }
        return 0;
    }

    private static String name(ConnectionType direction) {
        return direction.name().toLowerCase(Locale.ROOT);
    }
}
