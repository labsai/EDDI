/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.postgres;

import ai.labs.eddi.engine.alerts.AlertJson;
import ai.labs.eddi.engine.alerts.IAlertRuleStore;
import ai.labs.eddi.engine.alerts.model.AlertRule;
import ai.labs.eddi.engine.alerts.model.AlertScope;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.bson.types.ObjectId;
import org.jboss.logging.Logger;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * PostgreSQL implementation of {@link IAlertRuleStore}, table
 * {@code alert_rules}. The whole rule is kept as JSONB in {@code doc}; the
 * columns that queries filter on ({@code enabled}, {@code all_agents},
 * {@code agent_ids}) and the audit columns are kept beside it.
 *
 * @since 6.6.0
 */
@ApplicationScoped
@DefaultBean
public class PostgresAlertRuleStore implements IAlertRuleStore {

    private static final Logger LOGGER = Logger.getLogger(PostgresAlertRuleStore.class);

    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS alert_rules (
                id VARCHAR(64) PRIMARY KEY,
                enabled BOOLEAN NOT NULL,
                all_agents BOOLEAN NOT NULL,
                agent_ids TEXT[] NOT NULL DEFAULT '{}',
                created_by VARCHAR(255),
                created_at TIMESTAMPTZ,
                updated_by VARCHAR(255),
                updated_at TIMESTAMPTZ,
                doc JSONB NOT NULL
            )
            """;

    private static final String[] CREATE_INDEXES = {
            "CREATE INDEX IF NOT EXISTS idx_alert_rules_agents ON alert_rules USING GIN (agent_ids)",
            "CREATE INDEX IF NOT EXISTS idx_alert_rules_enabled ON alert_rules (enabled, all_agents)"};

    private static final String SELECT = "SELECT doc::text, created_by, updated_by FROM alert_rules";

    private final Instance<DataSource> dataSourceInstance;
    private volatile boolean schemaInitialized = false;

    @Inject
    public PostgresAlertRuleStore(Instance<DataSource> dataSourceInstance) {
        this.dataSourceInstance = dataSourceInstance;
    }

    private synchronized void ensureSchema() {
        if (schemaInitialized) {
            return;
        }
        try (Connection conn = dataSourceInstance.get().getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute(CREATE_TABLE);
            for (String index : CREATE_INDEXES) {
                stmt.execute(index);
            }
            schemaInitialized = true;
            LOGGER.info("PostgresAlertRuleStore schema initialized");
        } catch (SQLException e) {
            throw new RuntimeException("Failed to initialize alert_rules table", e);
        }
    }

    @Override
    public AlertRule create(AlertRule rule) {
        ensureSchema();
        AlertRule stored = rule.withId(new ObjectId().toHexString());
        String sql = "INSERT INTO alert_rules (id, enabled, all_agents, agent_ids, created_by, created_at, updated_by, updated_at, doc) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)";
        try (Connection conn = dataSourceInstance.get().getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, stored.id());
            bindColumns(conn, ps, 2, stored);
            ps.executeUpdate();
            return stored;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to create alert rule", e);
        }
    }

    @Override
    public Optional<AlertRule> read(String id) {
        ensureSchema();
        List<AlertRule> rules = select(SELECT + " WHERE id = ?", List.of(id));
        return rules.isEmpty() ? Optional.empty() : Optional.of(rules.get(0));
    }

    @Override
    public boolean update(AlertRule rule) {
        ensureSchema();
        String sql = "UPDATE alert_rules SET enabled = ?, all_agents = ?, agent_ids = ?, created_by = ?, created_at = ?, updated_by = ?, "
                + "updated_at = ?, doc = ?::jsonb WHERE id = ?";
        try (Connection conn = dataSourceInstance.get().getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            bindColumns(conn, ps, 1, rule);
            ps.setString(9, rule.id());
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to update alert rule", e);
        }
    }

    @Override
    public boolean delete(String id) {
        ensureSchema();
        try (Connection conn = dataSourceInstance.get().getConnection();
                PreparedStatement ps = conn.prepareStatement("DELETE FROM alert_rules WHERE id = ?")) {
            ps.setString(1, id);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to delete alert rule", e);
        }
    }

    @Override
    public List<AlertRule> list(String agentId, boolean onlyThatAgent, int offset, int limit) {
        ensureSchema();
        List<Object> params = new ArrayList<>();
        String where = "";
        if (agentId != null) {
            where = listWhere(onlyThatAgent);
            params.add(agentId);
        }
        params.add(limit);
        params.add(Math.max(0, offset));
        return select(SELECT + where + " ORDER BY created_at DESC NULLS LAST, id DESC LIMIT ? OFFSET ?", params);
    }

    @Override
    public long count(String agentId, boolean onlyThatAgent) {
        ensureSchema();
        String sql = "SELECT COUNT(*) FROM alert_rules" + (agentId == null ? "" : listWhere(onlyThatAgent));
        try (Connection conn = dataSourceInstance.get().getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            if (agentId != null) {
                ps.setString(1, agentId);
            }
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to count alert rules", e);
        }
    }

    private static String listWhere(boolean onlyThatAgent) {
        return onlyThatAgent ? " WHERE ? = ANY(agent_ids) AND cardinality(agent_ids) = 1" : " WHERE ? = ANY(agent_ids)";
    }

    @Override
    public List<AlertRule> findEnabledForAgent(String agentId) {
        ensureSchema();
        return select(SELECT + " WHERE enabled AND (all_agents OR ? = ANY(agent_ids))", List.of(agentId));
    }

    @Override
    public List<AlertRule> findEnabled() {
        ensureSchema();
        return select(SELECT + " WHERE enabled", List.of());
    }

    @Override
    public long clearAuthor(String userId) {
        ensureSchema();
        long changed = 0;
        for (String column : new String[]{"created_by", "updated_by"}) {
            try (Connection conn = dataSourceInstance.get().getConnection();
                    PreparedStatement ps = conn.prepareStatement("UPDATE alert_rules SET " + column + " = NULL WHERE " + column + " = ?")) {
                ps.setString(1, userId);
                changed += ps.executeUpdate();
            } catch (SQLException e) {
                throw new RuntimeException("Failed to clear alert rule authors", e);
            }
        }
        return changed;
    }

    @Override
    public List<AlertRule> findByAuthor(String userId, int limit) {
        ensureSchema();
        return select(SELECT + " WHERE created_by = ? OR updated_by = ? ORDER BY created_at DESC NULLS LAST, id DESC LIMIT ?",
                Arrays.asList(userId, userId, limit));
    }

    /**
     * Binds enabled, all_agents, agent_ids, created_by, created_at, updated_by,
     * updated_at and doc from {@code first} on. The author columns are the
     * authority (GDPR erasure clears them there), so they are blanked in the JSON.
     */
    private static void bindColumns(Connection conn, PreparedStatement ps, int first, AlertRule rule) throws SQLException {
        AlertScope scope = rule.scope() == null ? new AlertScope(false, List.of()) : rule.scope();
        ps.setBoolean(first, rule.enabled());
        ps.setBoolean(first + 1, scope.allAgents());
        ps.setArray(first + 2, conn.createArrayOf("text", scope.agentIds().toArray(new String[0])));
        ps.setString(first + 3, rule.createdBy());
        ps.setObject(first + 4, timestamp(rule.createdAt()));
        ps.setString(first + 5, rule.updatedBy());
        ps.setObject(first + 6, timestamp(rule.updatedAt()));
        ps.setString(first + 7, AlertJson.write(rule.withAudit(null, rule.createdAt(), null, rule.updatedAt())));
    }

    private List<AlertRule> select(String sql, List<Object> params) {
        try (Connection conn = dataSourceInstance.get().getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            List<AlertRule> rules = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    AlertRule rule = AlertJson.read(rs.getString(1), AlertRule.class);
                    rules.add(rule.withAudit(rs.getString(2), rule.createdAt(), rs.getString(3), rule.updatedAt()));
                }
            }
            return rules;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read alert rules", e);
        }
    }

    private static OffsetDateTime timestamp(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
