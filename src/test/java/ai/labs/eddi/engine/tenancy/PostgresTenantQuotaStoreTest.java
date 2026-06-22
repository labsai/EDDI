/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.tenancy;

import ai.labs.eddi.engine.tenancy.model.QuotaCheckResult;
import ai.labs.eddi.engine.tenancy.model.TenantQuota;
import ai.labs.eddi.engine.tenancy.model.UsageSnapshot;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.*;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link PostgresTenantQuotaStore}. Mocks the full JDBC chain:
 * DataSource → Connection → Statement/PreparedStatement → ResultSet.
 */
class PostgresTenantQuotaStoreTest {

    private static final String TENANT_ID = "tenant-abc";

    private DataSource dataSource;
    private Connection connection;
    private Statement statement;
    private PreparedStatement preparedStatement;
    private ResultSet resultSet;

    @SuppressWarnings("unchecked")
    private Instance<DataSource> dataSourceInstance;

    private PostgresTenantQuotaStore sut;

    @BeforeEach
    void setUp() throws Exception {
        dataSource = mock(DataSource.class);
        connection = mock(Connection.class);
        statement = mock(Statement.class);
        preparedStatement = mock(PreparedStatement.class);
        resultSet = mock(ResultSet.class);

        lenient().when(dataSource.getConnection()).thenReturn(connection);
        lenient().when(connection.createStatement()).thenReturn(statement);
        lenient().when(connection.prepareStatement(anyString())).thenReturn(preparedStatement);
        lenient().when(preparedStatement.executeQuery()).thenReturn(resultSet);

        dataSourceInstance = mock(Instance.class);
        lenient().when(dataSourceInstance.get()).thenReturn(dataSource);

        sut = new PostgresTenantQuotaStore(dataSourceInstance);
    }

    // ─── Schema initialization ────────────────────────────────────────────────

    @Nested
    @DisplayName("Schema initialization")
    class SchemaInit {

        @Test
        @DisplayName("should create tables on first access")
        void ensureSchema_createsTablesOnFirstAccess() throws Exception {
            when(resultSet.next()).thenReturn(false);

            sut.getQuota(TENANT_ID);

            verify(statement, times(2)).execute(anyString());
        }

        @Test
        @DisplayName("should only create tables once across multiple calls")
        void ensureSchema_idempotent() throws Exception {
            when(resultSet.next()).thenReturn(false);

            sut.getQuota(TENANT_ID);
            sut.getQuota(TENANT_ID);

            // Statement.execute called only 2 times (for the 2 CREATE TABLE statements)
            verify(statement, times(2)).execute(anyString());
        }

        @Test
        @DisplayName("should throw RuntimeException when schema creation fails")
        void ensureSchema_failsWithSQLException() throws Exception {
            when(connection.createStatement()).thenThrow(new SQLException("DB down"));

            assertThrows(RuntimeException.class, () -> sut.getQuota(TENANT_ID));
        }
    }

    // ─── getQuota ──────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("getQuota")
    class GetQuota {

        @Test
        @DisplayName("should return TenantQuota when found")
        void getQuota_found() throws Exception {
            when(resultSet.next()).thenReturn(true);
            when(resultSet.getString("tenant_id")).thenReturn(TENANT_ID);
            when(resultSet.getInt("max_conversations_per_day")).thenReturn(100);
            when(resultSet.getInt("max_agents_per_tenant")).thenReturn(5);
            when(resultSet.getInt("max_api_calls_per_minute")).thenReturn(60);
            when(resultSet.getDouble("max_monthly_cost_usd")).thenReturn(500.0);
            when(resultSet.getBoolean("enabled")).thenReturn(true);

            TenantQuota quota = sut.getQuota(TENANT_ID);

            assertNotNull(quota);
            assertEquals(TENANT_ID, quota.tenantId());
            assertEquals(100, quota.maxConversationsPerDay());
            assertEquals(5, quota.maxAgentsPerTenant());
            assertEquals(60, quota.maxApiCallsPerMinute());
            assertEquals(500.0, quota.maxMonthlyCostUsd());
            assertTrue(quota.enabled());
        }

        @Test
        @DisplayName("should return null when not found")
        void getQuota_notFound() throws Exception {
            when(resultSet.next()).thenReturn(false);

            TenantQuota quota = sut.getQuota(TENANT_ID);

            assertNull(quota);
        }

        @Test
        @DisplayName("should return null and log on SQLException")
        void getQuota_sqlException() throws Exception {
            // After schema init, the second getConnection call throws
            when(dataSource.getConnection())
                    .thenReturn(connection) // for ensureSchema
                    .thenThrow(new SQLException("connection error"));

            TenantQuota result = sut.getQuota(TENANT_ID);

            assertNull(result);
        }
    }

    // ─── setQuota ──────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("setQuota")
    class SetQuota {

        @Test
        @DisplayName("should upsert quota configuration")
        void setQuota_success() throws Exception {
            TenantQuota quota = new TenantQuota(TENANT_ID, 100, 5, 60, 500.0, true);
            when(preparedStatement.executeUpdate()).thenReturn(1);

            sut.setQuota(quota);

            verify(preparedStatement).setString(1, TENANT_ID);
            verify(preparedStatement).setInt(2, 100);
            verify(preparedStatement).setInt(3, 5);
            verify(preparedStatement).setInt(4, 60);
            verify(preparedStatement).setDouble(5, 500.0);
            verify(preparedStatement).setBoolean(6, true);
            verify(preparedStatement).executeUpdate();
        }

        @Test
        @DisplayName("should handle SQL exception gracefully")
        void setQuota_sqlException() throws Exception {
            TenantQuota quota = new TenantQuota(TENANT_ID, 100, 5, 60, 500.0, true);
            // After schema init, the second getConnection call throws
            when(dataSource.getConnection())
                    .thenReturn(connection) // schema init
                    .thenThrow(new SQLException("write error"));

            assertDoesNotThrow(() -> sut.setQuota(quota));
        }
    }

    // ─── listQuotas ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("listQuotas")
    class ListQuotas {

        @Test
        @DisplayName("should return empty list when no quotas")
        void listQuotas_empty() throws Exception {
            when(resultSet.next()).thenReturn(false);

            List<TenantQuota> result = sut.listQuotas();

            assertNotNull(result);
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("should return multiple quotas")
        void listQuotas_multiple() throws Exception {
            when(resultSet.next()).thenReturn(true, true, false);
            when(resultSet.getString("tenant_id")).thenReturn("t1", "t2");
            when(resultSet.getInt("max_conversations_per_day")).thenReturn(10, 20);
            when(resultSet.getInt("max_agents_per_tenant")).thenReturn(-1, -1);
            when(resultSet.getInt("max_api_calls_per_minute")).thenReturn(-1, -1);
            when(resultSet.getDouble("max_monthly_cost_usd")).thenReturn(-1.0, -1.0);
            when(resultSet.getBoolean("enabled")).thenReturn(true, false);

            List<TenantQuota> result = sut.listQuotas();

            assertEquals(2, result.size());
            assertEquals("t1", result.get(0).tenantId());
            assertEquals("t2", result.get(1).tenantId());
        }

        @Test
        @DisplayName("should return empty list on SQL exception")
        void listQuotas_sqlException() throws Exception {
            when(dataSource.getConnection())
                    .thenReturn(connection) // schema init
                    .thenThrow(new SQLException("read error"));

            List<TenantQuota> result = sut.listQuotas();

            assertNotNull(result);
            assertTrue(result.isEmpty());
        }
    }

    // ─── deleteQuota ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("deleteQuota")
    class DeleteQuota {

        @Test
        @DisplayName("should delete from both tables in a transaction")
        void deleteQuota_success() throws Exception {
            when(preparedStatement.executeUpdate()).thenReturn(1);

            sut.deleteQuota(TENANT_ID);

            verify(connection).setAutoCommit(false);
            // Two delete statements
            verify(preparedStatement, times(2)).setString(1, TENANT_ID);
            verify(preparedStatement, times(2)).executeUpdate();
            verify(connection).commit();
            verify(connection).setAutoCommit(true);
        }

        @Test
        @DisplayName("should rollback on inner SQL exception")
        void deleteQuota_rollbackOnFailure() throws Exception {
            // First executeUpdate succeeds, second fails
            when(preparedStatement.executeUpdate())
                    .thenReturn(1)
                    .thenThrow(new SQLException("delete failed"));

            assertDoesNotThrow(() -> sut.deleteQuota(TENANT_ID));

            verify(connection).rollback();
            verify(connection).setAutoCommit(true);
        }

        @Test
        @DisplayName("should handle outer connection exception gracefully")
        void deleteQuota_connectionException() throws Exception {
            when(dataSource.getConnection())
                    .thenReturn(connection) // schema init
                    .thenThrow(new SQLException("connection refused"));

            assertDoesNotThrow(() -> sut.deleteQuota(TENANT_ID));
        }
    }

    // ─── tryIncrementConversations ─────────────────────────────────────────────

    @Nested
    @DisplayName("tryIncrementConversations")
    class TryIncrementConversations {

        @Test
        @DisplayName("should return OK immediately when limit < 0 (unlimited)")
        void unlimited() {
            QuotaCheckResult result = sut.tryIncrementConversations(TENANT_ID, -1);

            assertEquals(QuotaCheckResult.OK, result);
        }

        @Test
        @DisplayName("should return OK when atomic increment succeeds within window")
        void withinLimit() throws Exception {
            when(resultSet.next()).thenReturn(true);
            when(resultSet.getInt(1)).thenReturn(5);

            QuotaCheckResult result = sut.tryIncrementConversations(TENANT_ID, 10);

            assertTrue(result.allowed());
        }

        @Test
        @DisplayName("should fallback to upsert and return OK when window is stale and under limit")
        void staleWindowReset() throws Exception {
            // First query (atomic increment) returns no rows
            ResultSet rs1 = mock(ResultSet.class);
            when(rs1.next()).thenReturn(false);

            // Second query (upsert) returns rows under limit
            ResultSet rs2 = mock(ResultSet.class);
            when(rs2.next()).thenReturn(true);
            when(rs2.getInt(1)).thenReturn(1);

            PreparedStatement ps1 = mock(PreparedStatement.class);
            when(ps1.executeQuery()).thenReturn(rs1);

            PreparedStatement ps2 = mock(PreparedStatement.class);
            when(ps2.executeQuery()).thenReturn(rs2);

            // Schema init connection
            Connection schemaConn = mock(Connection.class);
            when(schemaConn.createStatement()).thenReturn(statement);

            // Operation connection
            Connection opConn = mock(Connection.class);
            when(opConn.prepareStatement(anyString())).thenReturn(ps1, ps2);

            when(dataSource.getConnection()).thenReturn(schemaConn, opConn);

            QuotaCheckResult result = sut.tryIncrementConversations(TENANT_ID, 10);

            assertTrue(result.allowed());
        }

        @Test
        @DisplayName("should return denied when limit is reached")
        void limitReached() throws Exception {
            // First query: no rows (not in window or already at limit)
            ResultSet rs1 = mock(ResultSet.class);
            when(rs1.next()).thenReturn(false);

            // Second query: upsert returns count above limit
            ResultSet rs2 = mock(ResultSet.class);
            when(rs2.next()).thenReturn(true);
            when(rs2.getInt(1)).thenReturn(11);

            PreparedStatement ps1 = mock(PreparedStatement.class);
            when(ps1.executeQuery()).thenReturn(rs1);

            PreparedStatement ps2 = mock(PreparedStatement.class);
            when(ps2.executeQuery()).thenReturn(rs2);

            Connection schemaConn = mock(Connection.class);
            when(schemaConn.createStatement()).thenReturn(statement);

            Connection opConn = mock(Connection.class);
            when(opConn.prepareStatement(anyString())).thenReturn(ps1, ps2);

            when(dataSource.getConnection()).thenReturn(schemaConn, opConn);

            QuotaCheckResult result = sut.tryIncrementConversations(TENANT_ID, 10);

            assertFalse(result.allowed());
            assertNotNull(result.reason());
            assertTrue(result.reason().contains("10"));
        }

        @Test
        @DisplayName("should return denied on SQL exception")
        void sqlException() throws Exception {
            when(dataSource.getConnection())
                    .thenReturn(connection) // schema init
                    .thenThrow(new SQLException("db error"));

            QuotaCheckResult result = sut.tryIncrementConversations(TENANT_ID, 10);

            assertFalse(result.allowed());
            assertTrue(result.reason().contains("Daily conversation limit"));
        }
    }

    // ─── tryIncrementApiCalls ──────────────────────────────────────────────────

    @Nested
    @DisplayName("tryIncrementApiCalls")
    class TryIncrementApiCalls {

        @Test
        @DisplayName("should return OK when limit < 0 (unlimited)")
        void unlimited() {
            QuotaCheckResult result = sut.tryIncrementApiCalls(TENANT_ID, -1);

            assertEquals(QuotaCheckResult.OK, result);
        }

        @Test
        @DisplayName("should return OK when atomic increment succeeds")
        void withinLimit() throws Exception {
            when(resultSet.next()).thenReturn(true);
            when(resultSet.getInt(1)).thenReturn(3);

            QuotaCheckResult result = sut.tryIncrementApiCalls(TENANT_ID, 60);

            assertTrue(result.allowed());
        }

        @Test
        @DisplayName("should return denied when rate limit reached")
        void limitReached() throws Exception {
            ResultSet rs1 = mock(ResultSet.class);
            when(rs1.next()).thenReturn(false);

            ResultSet rs2 = mock(ResultSet.class);
            when(rs2.next()).thenReturn(true);
            when(rs2.getInt(1)).thenReturn(61);

            PreparedStatement ps1 = mock(PreparedStatement.class);
            when(ps1.executeQuery()).thenReturn(rs1);

            PreparedStatement ps2 = mock(PreparedStatement.class);
            when(ps2.executeQuery()).thenReturn(rs2);

            Connection schemaConn = mock(Connection.class);
            when(schemaConn.createStatement()).thenReturn(statement);

            Connection opConn = mock(Connection.class);
            when(opConn.prepareStatement(anyString())).thenReturn(ps1, ps2);

            when(dataSource.getConnection()).thenReturn(schemaConn, opConn);

            QuotaCheckResult result = sut.tryIncrementApiCalls(TENANT_ID, 60);

            assertFalse(result.allowed());
            assertTrue(result.reason().contains("60/min"));
        }

        @Test
        @DisplayName("should return denied on SQL exception")
        void sqlException() throws Exception {
            when(dataSource.getConnection())
                    .thenReturn(connection) // schema init
                    .thenThrow(new SQLException("db error"));

            QuotaCheckResult result = sut.tryIncrementApiCalls(TENANT_ID, 10);

            assertFalse(result.allowed());
            assertTrue(result.reason().contains("API rate limit"));
        }
    }

    // ─── tryAddCost ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("tryAddCost")
    class TryAddCost {

        @Test
        @DisplayName("should return OK when cost is within budget")
        void withinBudget() throws Exception {
            when(resultSet.next()).thenReturn(true);
            when(resultSet.getDouble(1)).thenReturn(50.0);

            QuotaCheckResult result = sut.tryAddCost(TENANT_ID, 10.0, 100.0);

            assertTrue(result.allowed());
        }

        @Test
        @DisplayName("should return denied when cost exceeds budget")
        void overBudget() throws Exception {
            when(resultSet.next()).thenReturn(true);
            when(resultSet.getDouble(1)).thenReturn(150.0);

            QuotaCheckResult result = sut.tryAddCost(TENANT_ID, 10.0, 100.0);

            assertFalse(result.allowed());
            assertTrue(result.reason().contains("Monthly cost budget exceeded"));
        }

        @Test
        @DisplayName("should return OK when limit is negative (unlimited)")
        void unlimitedBudget() throws Exception {
            when(resultSet.next()).thenReturn(true);
            when(resultSet.getDouble(1)).thenReturn(9999.0);

            QuotaCheckResult result = sut.tryAddCost(TENANT_ID, 10.0, -1.0);

            assertTrue(result.allowed());
        }

        @Test
        @DisplayName("should return OK when no rows returned (new month)")
        void noRowsReturned() throws Exception {
            when(resultSet.next()).thenReturn(false);

            QuotaCheckResult result = sut.tryAddCost(TENANT_ID, 10.0, 100.0);

            assertTrue(result.allowed());
        }

        @Test
        @DisplayName("should fail closed (deny) on SQL exception — safety measure")
        void failClosed() throws Exception {
            when(dataSource.getConnection())
                    .thenReturn(connection) // schema init
                    .thenThrow(new SQLException("db crash"));

            QuotaCheckResult result = sut.tryAddCost(TENANT_ID, 10.0, 100.0);

            assertFalse(result.allowed());
            assertTrue(result.reason().contains("Cost accounting failed"));
        }
    }

    // ─── getUsage ──────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("getUsage")
    class GetUsage {

        @Test
        @DisplayName("should return snapshot when tenant has usage data")
        void usageFound() throws Exception {
            when(resultSet.next()).thenReturn(true);
            when(resultSet.getInt("conversations_today")).thenReturn(5);
            when(resultSet.getInt("api_calls_this_minute")).thenReturn(3);
            when(resultSet.getDouble("monthly_cost_usd")).thenReturn(42.0);
            when(resultSet.getLong("minute_start")).thenReturn(1000L);
            when(resultSet.getLong("day_start")).thenReturn(2000L);
            when(resultSet.getString("cost_month")).thenReturn(YearMonth.now(ZoneOffset.UTC).toString());

            UsageSnapshot snapshot = sut.getUsage(TENANT_ID);

            assertNotNull(snapshot);
            assertEquals(TENANT_ID, snapshot.tenantId());
            assertEquals(5, snapshot.conversationsToday());
            assertEquals(3, snapshot.apiCallsThisMinute());
            assertEquals(42.0, snapshot.monthlyCostUsd());
        }

        @Test
        @DisplayName("should return empty snapshot when no usage data")
        void usageNotFound() throws Exception {
            when(resultSet.next()).thenReturn(false);

            UsageSnapshot snapshot = sut.getUsage(TENANT_ID);

            assertNotNull(snapshot);
            assertEquals(TENANT_ID, snapshot.tenantId());
            assertEquals(0, snapshot.conversationsToday());
        }

        @Test
        @DisplayName("should handle null cost_month in result set")
        void nullCostMonth() throws Exception {
            when(resultSet.next()).thenReturn(true);
            when(resultSet.getInt("conversations_today")).thenReturn(1);
            when(resultSet.getInt("api_calls_this_minute")).thenReturn(0);
            when(resultSet.getDouble("monthly_cost_usd")).thenReturn(0.0);
            when(resultSet.getLong("minute_start")).thenReturn(1000L);
            when(resultSet.getLong("day_start")).thenReturn(2000L);
            when(resultSet.getString("cost_month")).thenReturn(null);

            UsageSnapshot snapshot = sut.getUsage(TENANT_ID);

            assertNotNull(snapshot);
            assertNotNull(snapshot.costMonth());
        }

        @Test
        @DisplayName("should return empty snapshot on SQL exception")
        void sqlException() throws Exception {
            when(dataSource.getConnection())
                    .thenReturn(connection) // schema init
                    .thenThrow(new SQLException("db error"));

            UsageSnapshot snapshot = sut.getUsage(TENANT_ID);

            assertNotNull(snapshot);
            assertEquals(TENANT_ID, snapshot.tenantId());
            assertEquals(0, snapshot.conversationsToday());
        }
    }

    // ─── getMonthlyCost ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("getMonthlyCost")
    class GetMonthlyCost {

        @Test
        @DisplayName("should return cost when current month matches")
        void currentMonthMatch() throws Exception {
            when(resultSet.next()).thenReturn(true);
            when(resultSet.getString("cost_month")).thenReturn(YearMonth.now(ZoneOffset.UTC).toString());
            when(resultSet.getDouble("monthly_cost_usd")).thenReturn(123.45);

            double cost = sut.getMonthlyCost(TENANT_ID);

            assertEquals(123.45, cost);
        }

        @Test
        @DisplayName("should return 0.0 when month is stale")
        void staleMonth() throws Exception {
            when(resultSet.next()).thenReturn(true);
            when(resultSet.getString("cost_month")).thenReturn("2020-01");
            when(resultSet.getDouble("monthly_cost_usd")).thenReturn(99.0);

            double cost = sut.getMonthlyCost(TENANT_ID);

            assertEquals(0.0, cost);
        }

        @Test
        @DisplayName("should return 0.0 when cost_month is null")
        void nullCostMonth() throws Exception {
            when(resultSet.next()).thenReturn(true);
            when(resultSet.getString("cost_month")).thenReturn(null);

            double cost = sut.getMonthlyCost(TENANT_ID);

            assertEquals(0.0, cost);
        }

        @Test
        @DisplayName("should return 0.0 when no row found")
        void noRow() throws Exception {
            when(resultSet.next()).thenReturn(false);

            double cost = sut.getMonthlyCost(TENANT_ID);

            assertEquals(0.0, cost);
        }

        @Test
        @DisplayName("should return 0.0 on SQL exception")
        void sqlException() throws Exception {
            when(dataSource.getConnection())
                    .thenReturn(connection) // schema init
                    .thenThrow(new SQLException("db error"));

            double cost = sut.getMonthlyCost(TENANT_ID);

            assertEquals(0.0, cost);
        }
    }

    // ─── resetUsage ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("resetUsage")
    class ResetUsage {

        @Test
        @DisplayName("should delete usage row for tenant")
        void resetUsage_success() throws Exception {
            when(preparedStatement.executeUpdate()).thenReturn(1);

            sut.resetUsage(TENANT_ID);

            verify(preparedStatement).setString(1, TENANT_ID);
            verify(preparedStatement).executeUpdate();
        }

        @Test
        @DisplayName("should handle SQL exception gracefully")
        void resetUsage_sqlException() throws Exception {
            when(dataSource.getConnection())
                    .thenReturn(connection) // schema init
                    .thenThrow(new SQLException("db error"));

            assertDoesNotThrow(() -> sut.resetUsage(TENANT_ID));
        }
    }

    // ─── Bootstrap ──────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Bootstrap (CDI constructor)")
    class Bootstrap {

        @Test
        @DisplayName("should bootstrap default quota via atomic INSERT ON CONFLICT DO NOTHING")
        void bootstrapsAtomically() throws Exception {
            // executeUpdate returns 1 (row was inserted — no prior quota existed)
            when(preparedStatement.executeUpdate()).thenReturn(1);
            // getQuota after bootstrap returns no rows (the bootstrap INSERT used a
            // different PS)
            when(resultSet.next()).thenReturn(false);

            var bootstrapStore = new PostgresTenantQuotaStore(
                    dataSourceInstance, "default", false, -1, -1, -1, -1.0);

            // Trigger ensureSchema + bootstrap
            bootstrapStore.getQuota("any");

            // CREATE TABLE x2 + bootstrap INSERT + getQuota SELECT
            verify(statement, times(2)).execute(anyString());
            verify(preparedStatement, atLeastOnce()).executeUpdate();
        }
    }
}
