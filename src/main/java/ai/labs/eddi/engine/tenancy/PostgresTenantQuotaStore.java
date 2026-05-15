/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.tenancy;

import ai.labs.eddi.engine.tenancy.model.QuotaCheckResult;
import ai.labs.eddi.engine.tenancy.model.TenantQuota;
import ai.labs.eddi.engine.tenancy.model.UsageSnapshot;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
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

    private final DataSource dataSource;

    @Inject
    public PostgresTenantQuotaStore(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    // ─── Quota Configuration ───

    @Override
    public TenantQuota getQuota(String tenantId) {
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(
                        "SELECT * FROM tenant_quotas WHERE tenant_id = ?")) {
            ps.setString(1, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return toQuota(rs);
                }
            }
        } catch (SQLException e) {
            LOGGER.warnf("Failed to read quota for tenant '%s': %s", tenantId, e.getMessage());
        }
        return null;
    }

    @Override
    public void setQuota(TenantQuota quota) {
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(
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
        } catch (SQLException e) {
            LOGGER.errorf("Failed to set quota for tenant '%s': %s", quota.tenantId(), e.getMessage());
        }
    }

    @Override
    public List<TenantQuota> listQuotas() {
        List<TenantQuota> result = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement("SELECT * FROM tenant_quotas");
                ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                result.add(toQuota(rs));
            }
        } catch (SQLException e) {
            LOGGER.warnf("Failed to list quotas: %s", e.getMessage());
        }
        return result;
    }

    @Override
    public void deleteQuota(String tenantId) {
        try (Connection conn = dataSource.getConnection()) {
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM tenant_quotas WHERE tenant_id = ?")) {
                ps.setString(1, tenantId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM tenant_usage WHERE tenant_id = ?")) {
                ps.setString(1, tenantId);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            LOGGER.warnf("Failed to delete quota for tenant '%s': %s", tenantId, e.getMessage());
        }
    }

    // ─── Atomic Usage Operations ───

    @Override
    public QuotaCheckResult tryIncrementConversations(String tenantId, int limit) {
        if (limit < 0) {
            return QuotaCheckResult.OK;
        }

        long dayStartMs = Instant.now().truncatedTo(ChronoUnit.DAYS).toEpochMilli();

        try (Connection conn = dataSource.getConnection()) {
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
                            INSERT INTO tenant_usage (tenant_id, conversations_today, day_start, api_calls_this_minute, minute_start, monthly_cost_usd, cost_month)
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
            LOGGER.errorf("Failed to increment conversations for tenant '%s': %s", tenantId, e.getMessage());
        }

        return QuotaCheckResult.denied("Daily conversation limit reached (" + limit + ")");
    }

    @Override
    public QuotaCheckResult tryIncrementApiCalls(String tenantId, int limit) {
        if (limit < 0) {
            return QuotaCheckResult.OK;
        }

        long minuteStart = Instant.now().truncatedTo(ChronoUnit.MINUTES).toEpochMilli();

        try (Connection conn = dataSource.getConnection()) {
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
                            INSERT INTO tenant_usage (tenant_id, conversations_today, day_start, api_calls_this_minute, minute_start, monthly_cost_usd, cost_month)
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
            LOGGER.errorf("Failed to increment API calls for tenant '%s': %s", tenantId, e.getMessage());
        }

        return QuotaCheckResult.denied("API rate limit reached (" + limit + "/min)");
    }

    @Override
    public QuotaCheckResult tryAddCost(String tenantId, double cost, double limit) {
        String monthKey = YearMonth.now(ZoneOffset.UTC).toString();

        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(
                        """
                                INSERT INTO tenant_usage (tenant_id, conversations_today, day_start, api_calls_this_minute, minute_start, monthly_cost_usd, cost_month)
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
                    if (limit >= 0 && totalCost > limit) {
                        return QuotaCheckResult.denied(
                                "Monthly cost budget exceeded ($%.2f / $%.2f)".formatted(totalCost, limit));
                    }
                }
            }
        } catch (SQLException e) {
            LOGGER.errorf("Failed to add cost for tenant '%s': %s", tenantId, e.getMessage());
        }
        return QuotaCheckResult.OK;
    }

    // ─── Usage Reporting ───

    @Override
    public UsageSnapshot getUsage(String tenantId) {
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement(
                        "SELECT * FROM tenant_usage WHERE tenant_id = ?")) {
            ps.setString(1, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return toSnapshot(tenantId, rs);
                }
            }
        } catch (SQLException e) {
            LOGGER.warnf("Failed to read usage for tenant '%s': %s", tenantId, e.getMessage());
        }
        return UsageSnapshot.empty(tenantId);
    }

    @Override
    public double getMonthlyCost(String tenantId) {
        try (Connection conn = dataSource.getConnection();
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
            LOGGER.warnf("Failed to read monthly cost for tenant '%s': %s", tenantId, e.getMessage());
        }
        return 0.0;
    }

    @Override
    public void resetUsage(String tenantId) {
        try (Connection conn = dataSource.getConnection();
                PreparedStatement ps = conn.prepareStatement("DELETE FROM tenant_usage WHERE tenant_id = ?")) {
            ps.setString(1, tenantId);
            ps.executeUpdate();
            LOGGER.infof("Reset usage counters for tenant '%s'", tenantId);
        } catch (SQLException e) {
            LOGGER.errorf("Failed to reset usage for tenant '%s': %s", tenantId, e.getMessage());
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
