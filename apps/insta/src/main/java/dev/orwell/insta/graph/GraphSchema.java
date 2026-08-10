package dev.orwell.insta.graph;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Creates the graph tables if they are not already there.
 *
 * <p>Deliberately not a migration tool. The schema is one idempotent file of
 * {@code CREATE … IF NOT EXISTS}, applied at the start of every sync, so there is no version table
 * to keep in step and no ordering to get wrong. The cost of that choice is that a *change* to an
 * existing table is not handled here — when the first one arrives, this becomes numbered
 * migrations, and the file below becomes migration 1.
 */
public final class GraphSchema {
    private static final String RESOURCE = "/dev/orwell/insta/graph/schema.sql";

    private GraphSchema() {
    }

    public static void apply(Connection connection) throws SQLException {
        String sql = read();
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static String read() {
        try (InputStream stream = GraphSchema.class.getResourceAsStream(RESOURCE)) {
            if (stream == null) {
                throw new IllegalStateException("Graph schema resource is missing: " + RESOURCE);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not read the graph schema.", exception);
        }
    }
}
