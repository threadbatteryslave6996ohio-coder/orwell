package dev.orwell.insta.ui;

import dev.orwell.env.Env;
import dev.orwell.insta.InstaEnvs;
import dev.orwell.insta.graph.GraphSchema;
import dev.orwell.logging.Logger;

import java.io.PrintStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Objects;

/**
 * {@code insta ui} — serves the graph viewer until interrupted.
 *
 * <p>The only command that does not run, print and exit. It never talks to Apify, so it costs
 * nothing to leave open, and it never writes graph data — the one exception being that it applies
 * the schema at startup. Without that, pointing the viewer at a database no sync has touched
 * answers every request with an error, which would mean spending Apify credit before you could
 * confirm the page even loads. Applying it is idempotent and creates only empty tables.
 */
public final class UiCommand {
    private final Env env;
    private final Logger logger;

    public UiCommand(Env env, Logger logger) {
        this.env = Objects.requireNonNull(env, "env");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    /** @return the process exit code; only returns once the server is shut down. */
    public int run(PrintStream err) throws InterruptedException {
        String url = env.get(InstaEnvs.INSTA_DATABASE_URL);
        if (url == null || url.isBlank()) {
            err.println("ui needs INSTA_DATABASE_URL — it shows what `insta sync` has recorded.");
            return 2;
        }
        try (Connection connection = open(url)) {
            GraphSchema.apply(connection);
        } catch (SQLException exception) {
            err.println("ui could not reach the database: " + exception.getMessage());
            return 4;
        }

        String address = env.get(InstaEnvs.INSTA_UI_ADDRESS);
        int port = env.get(InstaEnvs.INSTA_UI_PORT);

        UiServer server = new UiServer(() -> open(url), logger);

        err.printf("insta ui on http://%s:%d  (no authentication — anyone who can reach this port "
                + "can read the graph)%n", address, port);
        server.startAndWait(address, port);
        return 0;
    }

    private Connection open(String url) throws SQLException {
        return DriverManager.getConnection(
                url,
                env.get(InstaEnvs.INSTA_DATABASE_USERNAME),
                env.get(InstaEnvs.INSTA_DATABASE_PASSWORD));
    }
}
