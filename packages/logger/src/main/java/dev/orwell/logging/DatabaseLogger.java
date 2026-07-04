package dev.orwell.logging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import dev.orwell.primitives.NonEmptyString;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.Objects;

public final class DatabaseLogger implements Logger {
    private static final String DEFAULT_TABLE_NAME = "app_logs";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .disable(SerializationFeature.INDENT_OUTPUT);

    private final String loggerName;
    private final DataSource dataSource;
    private final String tableName;
    private volatile boolean tableEnsured;

    public DatabaseLogger(String loggerName, DataSource dataSource) {
        this(loggerName, dataSource, DEFAULT_TABLE_NAME);
    }

    public DatabaseLogger(String loggerName, DataSource dataSource, String tableName) {
        this.loggerName = new NonEmptyString(loggerName, "loggerName cannot be blank").value();
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.tableName = Objects.requireNonNull(tableName, "tableName").trim();
        if (this.tableName.isEmpty()) {
            throw new IllegalArgumentException("tableName cannot be blank");
        }
    }

    @Override
    public void log(LogEntry entry) {
        Objects.requireNonNull(entry, "entry");
        ensureTable();
        String metadataJson = serializeMetadata(entry.metadata());
        String sql = "INSERT INTO " + tableName + " (level, logger_name, message, metadata, created_at) VALUES (?, ?, ?, ?, ?)";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, entry.level().name());
            statement.setString(2, loggerName);
            statement.setString(3, entry.message());
            if (metadataJson != null) {
                statement.setString(4, metadataJson);
            } else {
                statement.setNull(4, Types.VARCHAR);
            }
            statement.setTimestamp(5, Timestamp.from(Instant.now()));
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("Cannot write database log entry.", exception);
        }
    }

    private void ensureTable() {
        if (tableEnsured) {
            return;
        }
        synchronized (this) {
            if (tableEnsured) {
                return;
            }
            String sql = "CREATE TABLE IF NOT EXISTS " + tableName + " ("
                    + "id BIGSERIAL PRIMARY KEY, "
                    + "level VARCHAR(10) NOT NULL, "
                    + "logger_name VARCHAR(255) NOT NULL, "
                    + "message TEXT NOT NULL, "
                    + "metadata TEXT, "
                    + "created_at TIMESTAMP WITH TIME ZONE NOT NULL"
                    + ")";
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.execute();
                tableEnsured = true;
            } catch (SQLException exception) {
                throw new IllegalStateException("Cannot ensure database log table exists.", exception);
            }
        }
    }

    private static String serializeMetadata(java.util.Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return null;
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(metadata);
        } catch (JsonProcessingException e) {
            return metadata.toString();
        }
    }
}
