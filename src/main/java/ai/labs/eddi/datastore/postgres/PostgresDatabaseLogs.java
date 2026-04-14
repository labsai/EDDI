package ai.labs.eddi.datastore.postgres;

import ai.labs.eddi.engine.model.Deployment.Environment;
import ai.labs.eddi.engine.model.LogEntry;
import ai.labs.eddi.engine.runtime.IDatabaseLogs;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import jakarta.enterprise.inject.Instance;
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
@DefaultBean
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

    private final Instance<DataSource> dataSourceInstance;
    private volatile boolean schemaInitialized = false;

    @Inject
    public PostgresDatabaseLogs(Instance<DataSource> dataSourceInstance) {
        this.dataSourceInstance = dataSourceInstance;
    }

    private synchronized void ensureSchema() {
        if (schemaInitialized)
            return;
        try (Connection conn = dataSourceInstance.get().getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute(CREATE_TABLE);
            schemaInitialized = true;
        } catch (SQLException e) {
            LOGGER.error("Failed to initialize database_logs table", e);
        }
    }

    @Override
    public List<LogEntry> getLogs(Environment environment, String agentId, Integer agentVersion, String conversationId, String userId,
                                  String instanceId, Integer skip, Integer limit) {
        ensureSchema();
        return getLogsInternal(environment != null ? environment.toString() : null, agentId, agentVersion, conversationId, userId, instanceId, skip,
                limit);
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
        try (Connection conn = dataSourceInstance.get().getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
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

    private List<LogEntry> getLogsInternal(String environment, String agentId, Integer agentVersion, String conversationId, String userId,
                                           String instanceId, Integer skip, Integer limit) {
        List<LogEntry> logs = new ArrayList<>();
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

        try (Connection conn = dataSourceInstance.get().getConnection(); PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Timestamp ts = rs.getTimestamp("timestamp");
                    logs.add(new LogEntry(ts != null ? ts.getTime() : 0L, rs.getString("level"), rs.getString("logger_name"), rs.getString("message"),
                            rs.getString("environment"), rs.getString("AGENT_ID"), (Integer) rs.getObject("AGENT_VERSION"),
                            rs.getString("conversation_id"), rs.getString("user_id"), rs.getString("instance_id")));
                }
            }
        } catch (SQLException e) {
            LOGGER.error("Failed to read database logs", e);
        }
        return logs;
    }
    // === GDPR ===

    @Override
    public long pseudonymizeByUserId(String userId, String pseudonym) {
        ensureSchema();
        String sql = "UPDATE database_logs SET user_id = ? WHERE user_id = ?";
        try (Connection conn = dataSourceInstance.get().getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, pseudonym);
            ps.setString(2, userId);
            return ps.executeUpdate();
        } catch (SQLException e) {
            LOGGER.error("Failed to pseudonymize database logs", e);
            return 0;
        }
    }
}
