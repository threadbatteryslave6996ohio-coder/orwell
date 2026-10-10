package dev.orwell.testing;

import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Shared base for tests that drive a real Postgres over plain JDBC, with no Spring context: a
 * Testcontainers instance plus the {@code execute}/{@code query} pair every such test was otherwise
 * writing for itself.
 *
 * <p>The container field is static and inherited, so every subclass shares one, and the
 * Testcontainers extension starts it around each test class. <strong>Nothing resets its contents
 * between tests</strong> — a subclass drops and recreates the tables it cares about in its own
 * {@code @BeforeEach}, which is also what keeps two subclasses in one JVM from seeing each other's
 * rows.
 *
 * <p>The counterpart to {@link PostgresIntegrationTest}, and separate from it on purpose. That base
 * registers {@code spring.datasource.*} and {@code ddl-auto: create-drop}, which is exactly wrong
 * for a test whose subject is the schema itself — a migration that runs before Hibernate has
 * nothing left to migrate once Hibernate has built the current shape. Tests here stand up the
 * legacy table by hand and check what the code under test does to it.
 *
 * <p>Helpers are static because the container is: a subclass's own {@code @BeforeAll} and static
 * fixtures can use them.
 */
@Testcontainers
public abstract class PostgresJdbcTest {

    @Container
    protected static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final DataSource DATA_SOURCE = new ContainerDataSource();

    /**
     * The container as a {@link DataSource}, for code under test that takes one.
     *
     * <p>Deliberately not pooled and not Spring's {@code DriverManagerDataSource}: a connection per
     * call is what these tests want anyway, and it keeps {@code spring-jdbc} off the test-support
     * classpath for the sake of one class.
     */
    protected static DataSource dataSource() {
        return DATA_SOURCE;
    }

    protected static Connection connection() throws SQLException {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    /** Runs each statement in order on one connection. For standing a fixture up, or tearing it down. */
    protected static void execute(String... statements) throws SQLException {
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            for (String sql : statements) {
                statement.execute(sql);
            }
        }
    }

    /**
     * The first column of every row, as text. Narrow on purpose: these tests ask the catalog yes/no
     * questions and read back single values, and {@code getString} renders whatever type answers.
     *
     * <p>Unchecked, so an assertion can call it inline — a broken query here is a broken test, not
     * a condition worth declaring.
     */
    protected static List<String> query(String sql) {
        try (Connection connection = connection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            List<String> rows = new ArrayList<>();
            while (resultSet.next()) {
                rows.add(resultSet.getString(1));
            }
            return rows;
        } catch (SQLException exception) {
            throw new IllegalStateException(exception);
        }
    }

    /** The minimum a {@link DataSource} has to be; everything past {@code getConnection} is refused. */
    private static final class ContainerDataSource implements DataSource {
        @Override
        public Connection getConnection() throws SQLException {
            return connection();
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return DriverManager.getConnection(POSTGRES.getJdbcUrl(), username, password);
        }

        @Override
        public PrintWriter getLogWriter() {
            return null;
        }

        @Override
        public void setLogWriter(PrintWriter out) {
        }

        @Override
        public void setLoginTimeout(int seconds) {
        }

        @Override
        public int getLoginTimeout() {
            return 0;
        }

        @Override
        public Logger getParentLogger() {
            return Logger.getGlobal();
        }

        @Override
        public <T> T unwrap(Class<T> type) throws SQLException {
            throw new SQLException("Not a wrapper for " + type);
        }

        @Override
        public boolean isWrapperFor(Class<?> type) {
            return false;
        }
    }
}
