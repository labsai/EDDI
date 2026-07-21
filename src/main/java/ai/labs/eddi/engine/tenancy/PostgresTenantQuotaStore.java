/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.tenancy;

import ai.labs.eddi.engine.tenancy.model.QuotaCheckResult;
import ai.labs.eddi.engine.tenancy.model.TenantQuota;
import ai.labs.eddi.engine.tenancy.model.UsageSnapshot;
import static ai.labs.eddi.utils.LogSanitizer.sanitize;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import javax.sql.DataSource;
import java.sql.*;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * PostgreSQL-backed tenant quota store. Uses
 * {@code UPDATE ... WHERE ... RETURNING} for atomic counter operations — safe
 * for multi-instance deployments.
 * <p>
 * Schema is auto-created via {@code CREATE TABLE IF NOT EXISTS} on first
 * access, following the established pattern from
 * {@code PostgresGlobalVariableStore}.
 * <p>
 * Tables:
 * <ul>
 * <li>{@code tenant_quotas} — quota configuration</li>
 * <li>{@code tenant_usage} — rolling usage counters</li>
 * </ul>
 *
 * @since 6.0.0
 */
@DefaultBean
@ApplicationScoped
public class PostgresTenantQuotaStore implements ITenantQuotaStore {

    private static final Logger LOGGER = Logger.getLogger(PostgresTenantQuotaStore.class);

    private static final String CREATE_QUOTAS_TABLE = """
            CREATE TABLE IF NOT EXISTS tenant_quotas (
                tenant_id VARCHAR(255) PRIMARY KEY,
                max_conversations_per_day INT NOT NULL DEFAULT -1,
                max_agents_per_tenant INT NOT NULL DEFAULT -1,
                max_api_calls_per_minute INT NOT NULL DEFAULT -1,
                max_monthly_cost_usd DOUBLE PRECISION NOT NULL DEFAULT -1.0,
                enabled BOOLEAN NOT NULL DEFAULT TRUE
            )
            """;

    private static final String CREATE_USAGE_TABLE = """
            CREATE TABLE IF NOT EXISTS tenant_usage (
                tenant_id VARCHAR(255) PRIMARY KEY,
                conversations_today INT NOT NULL DEFAULT 0,
                day_start BIGINT NOT NULL DEFAULT 0,
                api_calls_this_minute INT NOT NULL DEFAULT 0,
                minute_start BIGINT NOT NULL DEFAULT 0,
                monthly_cost_usd DOUBLE PRECISION NOT NULL DEFAULT 0.0,
                cost_month VARCHAR(10)
            )
            """;

    private final Instance<DataSource> dataSourceInstance;
    private volatile boolean schemaInitialized = false;

    // Bootstrap config — stored as fields for lazy initialization in ensureSchema()
    private final String defaultTenantId;
    private final TenantQuota defaultQuota;

    @Inject
    public PostgresTenantQuotaStore(Instance<DataSource> dataSourceInstance,
            @ConfigProperty(name = "eddi.tenant.default-id", defaultValue = "default") String defaultTenantId,
            @ConfigProperty(name = "eddi.tenant.quota.enabled", defaultValue = "false") boolean enabled,
            @ConfigProperty(name = "eddi.tenant.quota.max-conversations-per-day", defaultValue = "-1") int maxConvPerDay,
            @ConfigProperty(name = "eddi.tenant.quota.max-agents-per-tenant", defaultValue = "-1") int maxAgents,
            @ConfigProperty(name = "eddi.tenant.quota.max-api-calls-per-minute", defaultValue = "-1") int maxApiCalls,
            @ConfigProperty(name = "eddi.tenant.quota.max-monthly-cost-usd", defaultValue = "-1") double maxCost) {

        this.dataSourceInstance = dataSourceInstance;
        this.defaultTenantId = defaultTenantId;
        this.defaultQuota = new TenantQuota(defaultTenantId, maxConvPerDay, maxAgents, maxApiCalls, maxCost, enabled);
    }

    /**
     * Test-only constructor — no CDI injection, no bootstrap.
     */
    PostgresTenantQuotaStore(Instance<DataSource> dataSourceInstance) {
        this.dataSourceInstance = dataSourceInstance;
        this.defaultTenantId = null;
        this.defaultQuota = null;
    }

    private synchronized void ensureSchema() {
        if (schemaInitialized)
            return;
        try (Connection conn = dataSourceInstance.get().getConnection();
                Statement stmt = conn.createStatement()) {
            stmt.execute(CREATE_QUOTAS_TABLE);
            stmt.execute(CREATE_USAGE_TABLE);
            LOGGER.info("PostgresTenantQuotaStore initialized (tables=tenant_quotas, tenant_usage)");

            // Bootstrap default tenant quota if none exists (parity with
            // InMemoryTenantQuotaStore).
            // Uses INSERT ... ON CONFLICT DO NOTHING so an existing quota is never
            // overwritten.
            if (defaultTenantId != null && defaultQuota != null) {
                bootstrapDefaultQuota(conn, defaultQuota);
            }

            schemaInitialized = true;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to initialize tenant quota tables", e);
        }
    }

    // ─── Quota Configuration ───

    /**
     * Bootstrap-only insert using ON CONFLICT DO NOTHING — never overwrites an
     * existing quota.
     */
    private void bootstrapDefaultQuota(Connection conn, TenantQuota quota) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                """
                        INSERT INTO tenant_quotas (tenant_id, max_conversations_per_day, max_agents_per_tenant,
                                                   max_api_calls_per_minute, max_monthly_cost_usd, enabled)
                        VALUES (?, ?, ?, ?, ?, ?)
                        ON CONFLICT (tenant_id) DO NOTHING
                        """)) {
            ps.setString(1, quota.tenantId());
            ps.setInt(2, quota.maxConversationsPerDay());
            ps.setInt(3, quota.maxAgentsPerTenant());
            ps.setInt(4, quota.maxApiCallsPerMinute());
            ps.setDouble(5, quota.maxMonthlyCostUsd());
            ps.setBoolean(6, quota.enabled());
            int inserted = ps.executeUpdate();
            if (inserted > 0) {
                LOGGER.infof("Bootstrapped default tenant quota: tenantId=%s, enabled=%s, maxConv=%d, maxAgents=%d, maxApi=%d, maxCost=%.2f",
                        quota.tenantId(), quota.enabled(), quota.maxConversationsPerDay(),
                        quota.maxAgentsPerTenant(), quota.maxApiCallsPerMinute(), quota.maxMonthlyCostUsd());
            }
        }
    }

    /**
     * Internal quota lookup reusing an existing connection (used during bootstrap).
     */
    private TenantQuota getQuotaInternal(Connection conn, String tenantId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM tenant_quotas WHERE tenant_id = ?")) {
            ps.setString(1, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return toQuota(rs);
                }
            }
        }
        return null;
    }

    /**
     * Internal quota upsert reusing an existing connection (used during bootstrap).
     */
    private void setQuotaInternal(Connection conn, TenantQuota quota) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                """
                        INSERT INTO tenant_quotas (tenant_id, max_conversations_per_day, max_agents_per_tenant,
                                                   max_api_calls_per_minute, max_monthly_cost_usd, enabled)
                        VALUES (?, ?, ?, ?, ?, ?)
                        ON CONFLICT (tenant_id) DO UPDATE SET
                            max_conversations_per_day = EXCLUDED.max_conversations_per_day,
                            max_agents_per_tenant = EXCLUDED.max_agents_per_tenant,
                            max_api_calls_per_minute = EXCLUDED.max_api_calls_per_minute,
                            max_monthly_cost_usd = EXCLUDED.max_monthly_cost_usd,
                            enabled = EXCLUDED.enabled
                        """)) {
            ps.setString(1, quota.tenantId());
            ps.setInt(2, quota.maxConversationsPerDay());
            ps.setInt(3, quota.maxAgentsPerTenant());
            ps.setInt(4, quota.maxApiCallsPerMinute());
            ps.setDouble(5, quota.maxMonthlyCostUsd());
            ps.setBoolean(6, quota.enabled());
            ps.executeUpdate();
        }
    }

    @Override
    public TenantQuota getQuota(String tenantId) {
        ensureSchema();
        try (Connection conn = dataSourceInstance.get().getConnection()) {
            return getQuotaInternal(conn, tenantId);
        } catch (SQLException e) {
            LOGGER.warnf("Failed to read quota for tenant '%s': %s", sanitize(tenantId), sanitize(e.getMessage()));
        }
        return null;
    }

    @Override
    public void setQuota(TenantQuota quota) {
        ensureSchema();
        try (Connection conn = dataSourceInstance.get().getConnection()) {
            setQuotaInternal(conn, quota);
        } catch (SQLException e) {
            LOGGER.errorf("Failed to set quota for tenant '%s': %s", sanitize(quota.tenantId()), sanitize(e.getMessage()));
        }
    }

    @Override
    public List<TenantQuota> listQuotas() {
        ensureSchema();
        List<TenantQuota> result = new ArrayList<>();
        try (Connection conn = dataSourceInstance.get().getConnection();
                PreparedStatement ps = conn.prepareStatement("SELECT * FROM tenant_quotas");
                ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                result.add(toQuota(rs));
            }
        } catch (SQLException e) {
            LOGGER.warnf("Failed to list quotas: %s", sanitize(e.getMessage()));
        }
        return result;
    }

    @Override
    public void deleteQuota(String tenantId) {
        ensureSchema();
        try (Connection conn = dataSourceInstance.get().getConnection()) {
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement ps = conn.prepareStatement(
                        "DELETE FROM tenant_quotas WHERE tenant_id = ?")) {
                    ps.setString(1, tenantId);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "DELETE FROM tenant_usage WHERE tenant_id = ?")) {
                    ps.setString(1, tenantId);
                    ps.executeUpdate();
                }
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            LOGGER.warnf("Failed to delete quota for tenant '%s': %s",
                    sanitize(tenantId), sanitize(e.getMessage()));
        }
    }

    // ─── Atomic Usage Operations ───

    @Override
    public QuotaCheckResult tryIncrementConversations(String tenantId, int limit) {
        if (limit < 0) {
            return QuotaCheckResult.OK;
        }

        long dayStartMs = Instant.now().truncatedTo(ChronoUnit.DAYS).toEpochMilli();

        ensureSchema();
        try (Connection conn = dataSourceInstance.get().getConnection()) {
            // First: try atomic increment within current window
            try (PreparedStatement ps = conn.prepareStatement(
                    """
                            UPDATE tenant_usage SET conversations_today = conversations_today + 1
                            WHERE tenant_id = ? AND day_start = ? AND conversations_today < ?
                            RETURNING conversations_today
                            """)) {
                ps.setString(1, tenantId);
                ps.setLong(2, dayStartMs);
                ps.setInt(3, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return QuotaCheckResult.OK;
                    }
                }
            }

            // Window may be stale — try to reset and increment atomically
            try (PreparedStatement ps = conn.prepareStatement(
                    """
                            INSERT INTO tenant_usage
                                (tenant_id, conversations_today, day_start,
                                 api_calls_this_minute, minute_start,
                                 monthly_cost_usd, cost_month)
                            VALUES (?, 1, ?, 0, ?, 0.0, ?)
                            ON CONFLICT (tenant_id) DO UPDATE SET
                                conversations_today = CASE WHEN tenant_usage.day_start < ? THEN 1 ELSE tenant_usage.conversations_today END,
                                day_start = CASE WHEN tenant_usage.day_start < ? THEN ? ELSE tenant_usage.day_start END
                            RETURNING conversations_today
                            """)) {
                long minuteStart = Instant.now().truncatedTo(ChronoUnit.MINUTES).toEpochMilli();
                String costMonth = YearMonth.now(ZoneOffset.UTC).toString();
                ps.setString(1, tenantId);
                ps.setLong(2, dayStartMs);
                ps.setLong(3, minuteStart);
                ps.setString(4, costMonth);
                ps.setLong(5, dayStartMs);
                ps.setLong(6, dayStartMs);
                ps.setLong(7, dayStartMs);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next() && rs.getInt(1) <= limit) {
                        return QuotaCheckResult.OK;
                    }
                }
            }
        } catch (SQLException e) {
            LOGGER.errorf("Failed to increment conversations for tenant '%s': %s", sanitize(tenantId), sanitize(e.getMessage()));
        }

        return QuotaCheckResult.denied("Daily conversation limit reached (" + limit + ")");
    }

    @Override
    public QuotaCheckResult tryIncrementApiCalls(String tenantId, int limit) {
        if (limit < 0) {
            return QuotaCheckResult.OK;
        }

        long minuteStart = Instant.now().truncatedTo(ChronoUnit.MINUTES).toEpochMilli();

        ensureSchema();
        try (Connection conn = dataSourceInstance.get().getConnection()) {
            try (PreparedStatement ps = conn.prepareStatement(
                    """
                            UPDATE tenant_usage SET api_calls_this_minute = api_calls_this_minute + 1
                            WHERE tenant_id = ? AND minute_start = ? AND api_calls_this_minute < ?
                            RETURNING api_calls_this_minute
                            """)) {
                ps.setString(1, tenantId);
                ps.setLong(2, minuteStart);
                ps.setInt(3, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return QuotaCheckResult.OK;
                    }
                }
            }

            // Window may be stale — reset
            try (PreparedStatement ps = conn.prepareStatement(
                    """
                            INSERT INTO tenant_usage
                                (tenant_id, conversations_today, day_start,
                                 api_calls_this_minute, minute_start,
                                 monthly_cost_usd, cost_month)
                            VALUES (?, 0, ?, 1, ?, 0.0, ?)
                            ON CONFLICT (tenant_id) DO UPDATE SET
                                api_calls_this_minute = CASE WHEN tenant_usage.minute_start < ? THEN 1 ELSE tenant_usage.api_calls_this_minute END,
                                minute_start = CASE WHEN tenant_usage.minute_start < ? THEN ? ELSE tenant_usage.minute_start END
                            RETURNING api_calls_this_minute
                            """)) {
                long dayStart = Instant.now().truncatedTo(ChronoUnit.DAYS).toEpochMilli();
                String costMonth = YearMonth.now(ZoneOffset.UTC).toString();
                ps.setString(1, tenantId);
                ps.setLong(2, dayStart);
                ps.setLong(3, minuteStart);
                ps.setString(4, costMonth);
                ps.setLong(5, minuteStart);
                ps.setLong(6, minuteStart);
                ps.setLong(7, minuteStart);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next() && rs.getInt(1) <= limit) {
                        return QuotaCheckResult.OK;
                    }
                }
            }
        } catch (SQLException e) {
            LOGGER.errorf("Failed to increment API calls for tenant '%s': %s", sanitize(tenantId), sanitize(e.getMessage()));
        }

        return QuotaCheckResult.denied("API rate limit reached (" + limit + "/min)");
    }

    @Override
    public QuotaCheckResult tryAddCost(String tenantId, double cost, double limit) {
        String monthKey = YearMonth.now(ZoneOffset.UTC).toString();

        ensureSchema();
        try (Connection conn = dataSourceInstance.get().getConnection();
                PreparedStatement ps = conn.prepareStatement(
                        """
                                INSERT INTO tenant_usage
                                    (tenant_id, conversations_today, day_start,
                                     api_calls_this_minute, minute_start,
                                     monthly_cost_usd, cost_month)
                                VALUES (?, 0, ?, 0, ?, ?, ?)
                                ON CONFLICT (tenant_id) DO UPDATE SET
                                    monthly_cost_usd = CASE WHEN tenant_usage.cost_month = ? THEN tenant_usage.monthly_cost_usd + ? ELSE ? END,
                                    cost_month = ?
                                RETURNING monthly_cost_usd
                                """)) {
            long now = Instant.now().toEpochMilli();
            ps.setString(1, tenantId);
            ps.setLong(2, now);
            ps.setLong(3, now);
            ps.setDouble(4, cost);
            ps.setString(5, monthKey);
            ps.setString(6, monthKey);
            ps.setDouble(7, cost);
            ps.setDouble(8, cost);
            ps.setString(9, monthKey);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    double totalCost = rs.getDouble(1);
                    // >=, not >, to agree with TenantQuotaService.checkCostBudget
                    // (currentCost >= limit) and InMemoryTenantQuotaStore. With > the
                    // pre-call gate denied at exactly the limit while post-call
                    // accounting allowed.
                    if (limit >= 0 && totalCost >= limit) {
                        return QuotaCheckResult.denied(
                                "Monthly cost budget exceeded ($%.2f / $%.2f)".formatted(totalCost, limit));
                    }
                }
            }
        } catch (SQLException e) {
            LOGGER.errorf("Failed to add cost for tenant '%s': %s", sanitize(tenantId), sanitize(e.getMessage()));
            // Fail closed — if cost accounting fails, deny the request rather than
            // silently bypassing budget enforcement
            return QuotaCheckResult.denied("Cost accounting failed — denying request for safety");
        }
        return QuotaCheckResult.OK;
    }

    // ─── Usage Reporting ───

    @Override
    public UsageSnapshot getUsage(String tenantId) {
        ensureSchema();
        try (Connection conn = dataSourceInstance.get().getConnection();
                PreparedStatement ps = conn.prepareStatement(
                        "SELECT * FROM tenant_usage WHERE tenant_id = ?")) {
            ps.setString(1, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return toSnapshot(tenantId, rs);
                }
            }
        } catch (SQLException e) {
            LOGGER.warnf("Failed to read usage for tenant '%s': %s", sanitize(tenantId), sanitize(e.getMessage()));
        }
        return UsageSnapshot.empty(tenantId);
    }

    @Override
    public double getMonthlyCost(String tenantId) {
        ensureSchema();
        try (Connection conn = dataSourceInstance.get().getConnection();
                PreparedStatement ps = conn.prepareStatement(
                        "SELECT monthly_cost_usd, cost_month FROM tenant_usage WHERE tenant_id = ?")) {
            ps.setString(1, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String monthKey = rs.getString("cost_month");
                    if (monthKey != null && monthKey.equals(YearMonth.now(ZoneOffset.UTC).toString())) {
                        return rs.getDouble("monthly_cost_usd");
                    }
                }
            }
        } catch (SQLException e) {
            LOGGER.warnf("Failed to read monthly cost for tenant '%s': %s", sanitize(tenantId), sanitize(e.getMessage()));
        }
        return 0.0;
    }

    @Override
    public void resetUsage(String tenantId) {
        ensureSchema();
        try (Connection conn = dataSourceInstance.get().getConnection();
                PreparedStatement ps = conn.prepareStatement("DELETE FROM tenant_usage WHERE tenant_id = ?")) {
            ps.setString(1, tenantId);
            ps.executeUpdate();
            LOGGER.infof("Reset usage counters for tenant '%s'", sanitize(tenantId));
        } catch (SQLException e) {
            LOGGER.errorf("Failed to reset usage for tenant '%s': %s", sanitize(tenantId), sanitize(e.getMessage()));
        }
    }

    // ─── Mapping ───

    private TenantQuota toQuota(ResultSet rs) throws SQLException {
        return new TenantQuota(
                rs.getString("tenant_id"),
                rs.getInt("max_conversations_per_day"),
                rs.getInt("max_agents_per_tenant"),
                rs.getInt("max_api_calls_per_minute"),
                rs.getDouble("max_monthly_cost_usd"),
                rs.getBoolean("enabled"));
    }

    private UsageSnapshot toSnapshot(String tenantId, ResultSet rs) throws SQLException {
        return new UsageSnapshot(
                tenantId,
                rs.getInt("conversations_today"),
                rs.getInt("api_calls_this_minute"),
                rs.getDouble("monthly_cost_usd"),
                Instant.ofEpochMilli(rs.getLong("minute_start")),
                Instant.ofEpochMilli(rs.getLong("day_start")),
                rs.getString("cost_month") != null
                        ? YearMonth.parse(rs.getString("cost_month"))
                        : YearMonth.now(ZoneOffset.UTC));
    }
}
