package ai.labs.eddi.datastore.postgres;

import ai.labs.eddi.engine.model.DatabaseLog;
import ai.labs.eddi.engine.model.Deployment.Environment;
import ai.labs.eddi.engine.model.LogEntry;
import ai.labs.eddi.engine.runtime.IDatabaseLogs;
import io.quarkus.arc.profile.IfBuildProfile;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import javax.sql.DataSource;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * PostgreSQL implementation of {@link IDatabaseLogs}.
 *
 * @author ginccc
 */
@ApplicationScoped
@IfBuildProfile("postgres")
public class PostgresDatabaseLogs implements IDatabaseLogs {

    private static final Logger LOGGER = Logger.getLogger(PostgresDatabaseLogs.class);

    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS database_logs (
                id BIGSERIAL PRIMARY KEY,
                message TEXT,
                level VARCHAR(16),
                logger_name VARCHAR(512),
                timestamp TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                environment VARCHAR(64),
                AGENT_ID VARCHAR(255),
                AGENT_VERSION INTEGER,
                conversation_id VARCHAR(255),
                user_id VARCHAR(255),
                instance_id VARCHAR(128)
            )
            """;

    private final DataSource dataSource;
    private volatile boolean schemaInitialized = false;

    @Inject
    public PostgresDatabaseLogs(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    private synchronized void ensureSchema() {
        if (schemaInitialized)
            return;
        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute(CREATE_TABLE);
            schemaInitialized = true;
        } catch (SQLException e) {
            LOGGER.error("Failed to initialize database_logs table", e);
        }
    }

    @Override
    public List<DatabaseLog> getLogs(Integer skip, Integer limit) {
        ensureSchema();
        return getLogsInternal(null, null, null, null, null, null, skip, limit);
    }

    @Override
    public List<DatabaseLog> getLogs(Environment environment, String agentId, Integer agentVersion, String conversationId, String userId,
            String instanceId, Integer skip, Integer limit) {
        ensureSchema();
        return getLogsInternal(environment != null ? environment.toString() : null, agentId, agentVersion, conversationId, userId, instanceId, skip,
                limit);
    }

    @Override
    public void addLogs(String environment, String agentId, Integer agentVersion, String conversationId, String userId, String instanceId,
            String message) {
        ensureSchema();
        String sql = """
                INSERT INTO database_logs (message, timestamp, environment, AGENT_ID, AGENT_VERSION, conversation_id, user_id, instance_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, message);
            ps.setTimestamp(2, new Timestamp(System.currentTimeMillis()));
            ps.setString(3, environment);
            ps.setString(4, agentId);
            if (agentVersion != null) {
                ps.setInt(5, agentVersion);
            } else {
                ps.setNull(5, Types.INTEGER);
            }
            ps.setString(6, conversationId);
            ps.setString(7, userId);
            ps.setString(8, instanceId);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOGGER.error("Failed to add database log", e);
        }
    }

    @Override
    public void addLogsBatch(List<LogEntry> entries) {
        if (entries == null || entries.isEmpty())
            return;
        ensureSchema();

        String sql = """
                INSERT INTO database_logs (message, level, logger_name, timestamp, environment,
                 AGENT_ID, AGENT_VERSION, conversation_id, user_id, instance_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            for (LogEntry entry : entries) {
                ps.setString(1, entry.message());
                ps.setString(2, entry.level());
                ps.setString(3, entry.loggerName());
                ps.setTimestamp(4, new Timestamp(entry.timestamp()));
                ps.setString(5, entry.environment());
                ps.setString(6, entry.agentId());
                if (entry.agentVersion() != null) {
                    ps.setInt(7, entry.agentVersion());
                } else {
                    ps.setNull(7, Types.INTEGER);
                }
                ps.setString(8, entry.conversationId());
                ps.setString(9, entry.userId());
                ps.setString(10, entry.instanceId());
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException e) {
            LOGGER.errorv("Failed to batch-insert {0} log entries: {1}", entries.size(), e.getMessage());
        }
    }

    private List<DatabaseLog> getLogsInternal(String environment, String agentId, Integer agentVersion, String conversationId, String userId,
            String instanceId, Integer skip, Integer limit) {
        List<DatabaseLog> logs = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT * FROM database_logs WHERE 1=1");
        List<Object> params = new ArrayList<>();

        if (environment != null) {
            sql.append(" AND environment = ?");
            params.add(environment);
        }
        if (agentId != null) {
            sql.append(" AND AGENT_ID = ?");
            params.add(agentId);
        }
        if (agentVersion != null) {
            sql.append(" AND AGENT_VERSION = ?");
            params.add(agentVersion);
        }
        if (conversationId != null) {
            sql.append(" AND conversation_id = ?");
            params.add(conversationId);
        }
        if (userId != null) {
            sql.append(" AND user_id = ?");
            params.add(userId);
        }
        if (instanceId != null) {
            sql.append(" AND instance_id = ?");
            params.add(instanceId);
        }
        sql.append(" ORDER BY timestamp DESC");
        if (limit != null && limit > 0) {
            sql.append(" LIMIT ").append(limit);
        }
        if (skip != null && skip > 0) {
            sql.append(" OFFSET ").append(skip);
        }

        try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    DatabaseLog logRecord = new DatabaseLog();
                    logRecord.put("message", rs.getString("message"));
                    logRecord.put("level", rs.getString("level"));
                    logRecord.put("loggerName", rs.getString("logger_name"));
                    logRecord.put("timestamp", rs.getTimestamp("timestamp"));
                    logRecord.put("environment", rs.getString("environment"));
                    logRecord.put("agentId", rs.getString("AGENT_ID"));
                    logRecord.put("agentVersion", rs.getObject("AGENT_VERSION"));
                    logRecord.put("conversationId", rs.getString("conversation_id"));
                    logRecord.put("userId", rs.getString("user_id"));
                    logRecord.put("instanceId", rs.getString("instance_id"));
                    logs.add(logRecord);
                }
            }
        } catch (SQLException e) {
            LOGGER.error("Failed to read database logs", e);
        }
        return logs;
    }
}
