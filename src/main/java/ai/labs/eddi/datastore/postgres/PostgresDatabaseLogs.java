package ai.labs.eddi.datastore.postgres;

import ai.labs.eddi.engine.model.DatabaseLog;
import ai.labs.eddi.engine.model.Deployment.Environment;
import ai.labs.eddi.engine.runtime.IDatabaseLogs;
import io.quarkus.arc.profile.IfBuildProfile;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import org.jboss.logging.MDC;

import javax.sql.DataSource;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.LogRecord;

/**
 * PostgreSQL implementation of {@link IDatabaseLogs}.
 */
@ApplicationScoped
@IfBuildProfile("postgres")
public class PostgresDatabaseLogs extends Handler implements IDatabaseLogs {

    private static final Logger LOGGER = Logger.getLogger(PostgresDatabaseLogs.class);

    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS database_logs (
                id BIGSERIAL PRIMARY KEY,
                message TEXT,
                timestamp TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                environment VARCHAR(64),
                bot_id VARCHAR(255),
                bot_version INTEGER,
                conversation_id VARCHAR(255),
                user_id VARCHAR(255)
            )
            """;

    private final DataSource dataSource;
    private volatile boolean schemaInitialized = false;

    @Inject
    public PostgresDatabaseLogs(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    private synchronized void ensureSchema() {
        if (schemaInitialized) return;
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute(CREATE_TABLE);
            schemaInitialized = true;
        } catch (SQLException e) {
            LOGGER.error("Failed to initialize database_logs table", e);
        }
    }

    @Override
    public List<DatabaseLog> getLogs(Integer skip, Integer limit) {
        ensureSchema();
        return getLogsInternal(null, null, null, null, null, skip, limit);
    }

    @Override
    public List<DatabaseLog> getLogs(Environment environment, String botId, Integer botVersion,
                                     String conversationId, String userId, Integer skip, Integer limit) {
        ensureSchema();
        return getLogsInternal(environment != null ? environment.toString() : null,
                botId, botVersion, conversationId, userId, skip, limit);
    }

    @Override
    public void addLogs(String environment, String botId, Integer botVersion,
                        String conversationId, String userId, String message) {
        ensureSchema();
        String sql = """
                INSERT INTO database_logs (message, timestamp, environment, bot_id, bot_version, conversation_id, user_id)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, message);
            ps.setTimestamp(2, new Timestamp(System.currentTimeMillis()));
            ps.setString(3, environment);
            ps.setString(4, botId);
            if (botVersion != null) {
                ps.setInt(5, botVersion);
            } else {
                ps.setNull(5, Types.INTEGER);
            }
            ps.setString(6, conversationId);
            ps.setString(7, userId);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOGGER.error("Failed to add database log", e);
        }
    }

    private List<DatabaseLog> getLogsInternal(String environment, String botId, Integer botVersion,
                                               String conversationId, String userId,
                                               Integer skip, Integer limit) {
        List<DatabaseLog> logs = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT * FROM database_logs WHERE 1=1");
        List<Object> params = new ArrayList<>();

        if (environment != null) {
            sql.append(" AND environment = ?");
            params.add(environment);
        }
        if (botId != null) {
            sql.append(" AND bot_id = ?");
            params.add(botId);
        }
        if (botVersion != null) {
            sql.append(" AND bot_version = ?");
            params.add(botVersion);
        }
        if (conversationId != null) {
            sql.append(" AND conversation_id = ?");
            params.add(conversationId);
        }
        if (userId != null) {
            sql.append(" AND user_id = ?");
            params.add(userId);
        }
        sql.append(" ORDER BY timestamp ASC");
        if (limit != null && limit > 0) {
            sql.append(" LIMIT ").append(limit);
        }
        if (skip != null && skip > 0) {
            sql.append(" OFFSET ").append(skip);
        }

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    DatabaseLog log = new DatabaseLog();
                    log.put("message", rs.getString("message"));
                    log.put("timestamp", rs.getTimestamp("timestamp"));
                    log.put("environment", rs.getString("environment"));
                    log.put("botId", rs.getString("bot_id"));
                    log.put("botVersion", rs.getObject("bot_version"));
                    log.put("conversationId", rs.getString("conversation_id"));
                    log.put("userId", rs.getString("user_id"));
                    logs.add(log);
                }
            }
        } catch (SQLException e) {
            LOGGER.error("Failed to read database logs", e);
        }
        return logs;
    }

    @Override
    public void publish(LogRecord record) {
        String environment = (String) MDC.get("environment");
        String botId = (String) MDC.get("botId");
        String conversationId = (String) MDC.get("conversationId");
        String userId = (String) MDC.get("userId");
        Integer botVersion = null;

        try {
            String botVersionString = (String) MDC.get("botVersion");
            if (botVersionString != null) {
                botVersion = Integer.parseInt(botVersionString);
            }
        } catch (NumberFormatException e) {
            LOGGER.debugv("Failed to parse botVersion from MDC: {0}", e.getMessage());
        }

        if (environment != null && botId != null && botVersion != null) {
            addLogs(environment, botId, botVersion, conversationId, userId, record.getMessage());
        }
    }

    @Override
    public void flush() {
    }

    @Override
    public void close() throws SecurityException {
    }
}
