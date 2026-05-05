/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.postgres;

import ai.labs.eddi.configs.variables.model.GlobalVariable;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.*;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link PostgresGlobalVariableStore} — JDBC adapter with tenant
 * scoping. Uses mocked {@link DataSource} / {@link Connection} /
 * {@link PreparedStatement} to verify SQL operations.
 */
@SuppressWarnings("unchecked")
class PostgresGlobalVariableStoreTest {

    private static final String DEFAULT = GlobalVariable.DEFAULT_TENANT;

    private Connection connection;
    private PostgresGlobalVariableStore store;

    @BeforeEach
    void setUp() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        connection = mock(Connection.class);
        when(dataSource.getConnection()).thenReturn(connection);

        // Schema creation statement
        Statement stmt = mock(Statement.class);
        when(connection.createStatement()).thenReturn(stmt);

        Instance<DataSource> instance = mock(Instance.class);
        when(instance.get()).thenReturn(dataSource);

        store = new PostgresGlobalVariableStore(instance);
    }

    // ==================== getAll ====================

    @Test
    @DisplayName("getAll — returns empty map when no rows for tenant")
    void getAllEmpty() throws Exception {
        PreparedStatement ps = mock(PreparedStatement.class);
        ResultSet rs = mock(ResultSet.class);
        when(connection.prepareStatement(contains("WHERE tenant_id = ?"))).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(false);

        Map<String, String> result = store.getAll(DEFAULT);
        assertTrue(result.isEmpty());
        verify(ps).setString(1, DEFAULT);
    }

    @Test
    @DisplayName("getAll — returns key-value pairs for specific tenant")
    void getAllWithRows() throws Exception {
        PreparedStatement ps = mock(PreparedStatement.class);
        ResultSet rs = mock(ResultSet.class);
        when(connection.prepareStatement(contains("WHERE tenant_id = ?"))).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true, true, false);
        when(rs.getString("key")).thenReturn("model", "temp");
        when(rs.getString("value")).thenReturn("gpt-4.1", "0.7");

        Map<String, String> result = store.getAll(DEFAULT);

        assertEquals(2, result.size());
        assertEquals("gpt-4.1", result.get("model"));
        verify(ps).setString(1, DEFAULT);
    }

    // ==================== get ====================

    @Test
    @DisplayName("get — returns GlobalVariable with tenantId when found")
    void getFound() throws Exception {
        PreparedStatement ps = mock(PreparedStatement.class);
        ResultSet rs = mock(ResultSet.class);
        when(connection.prepareStatement(contains("AND \"key\" = ?"))).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true);
        when(rs.getString("tenant_id")).thenReturn(DEFAULT);
        when(rs.getString("key")).thenReturn("model");
        when(rs.getString("value")).thenReturn("gpt-4.1");
        when(rs.getString("description")).thenReturn("Default model");
        when(rs.getBoolean("exportable")).thenReturn(true);

        GlobalVariable result = store.get(DEFAULT, "model");

        assertNotNull(result);
        assertEquals(DEFAULT, result.tenantId());
        assertEquals("model", result.key());
        assertEquals("gpt-4.1", result.value());
        verify(ps).setString(1, DEFAULT);
        verify(ps).setString(2, "model");
    }

    @Test
    @DisplayName("get — returns null when not found")
    void getNotFound() throws Exception {
        PreparedStatement ps = mock(PreparedStatement.class);
        ResultSet rs = mock(ResultSet.class);
        when(connection.prepareStatement(contains("AND \"key\" = ?"))).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(false);

        assertNull(store.get(DEFAULT, "missing"));
    }

    // ==================== upsert ====================

    @Test
    @DisplayName("upsert — executes INSERT ON CONFLICT with tenant_id + key")
    void upsert() throws Exception {
        PreparedStatement ps = mock(PreparedStatement.class);
        when(connection.prepareStatement(contains("ON CONFLICT (tenant_id, \"key\")"))).thenReturn(ps);

        store.upsert(new GlobalVariable(DEFAULT, "model", "gpt-4.1", "Default", true));

        verify(ps).setString(1, DEFAULT);
        verify(ps).setString(2, "model");
        verify(ps).setString(3, "gpt-4.1");
        verify(ps).setString(4, "Default");
        verify(ps).setBoolean(5, true);
        verify(ps).executeUpdate();
    }

    @Test
    @DisplayName("upsert — different tenant writes separate row")
    void upsertDifferentTenant() throws Exception {
        PreparedStatement ps = mock(PreparedStatement.class);
        when(connection.prepareStatement(contains("ON CONFLICT (tenant_id, \"key\")"))).thenReturn(ps);

        store.upsert(new GlobalVariable("tenant-a", "model", "claude"));

        verify(ps).setString(1, "tenant-a");
        verify(ps).setString(2, "model");
        verify(ps).setString(3, "claude");
    }

    // ==================== delete ====================

    @Test
    @DisplayName("delete — executes DELETE with tenant_id + key")
    void delete() throws Exception {
        PreparedStatement ps = mock(PreparedStatement.class);
        when(connection.prepareStatement(contains("DELETE FROM"))).thenReturn(ps);

        store.delete(DEFAULT, "model");

        verify(ps).setString(1, DEFAULT);
        verify(ps).setString(2, "model");
        verify(ps).executeUpdate();
    }

    // ==================== listAll ====================

    @Test
    @DisplayName("listAll — returns empty list when no rows for tenant")
    void listAllEmpty() throws Exception {
        PreparedStatement ps = mock(PreparedStatement.class);
        ResultSet rs = mock(ResultSet.class);
        when(connection.prepareStatement(contains("WHERE tenant_id = ?"))).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(false);

        List<GlobalVariable> result = store.listAll(DEFAULT);
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("listAll — returns GlobalVariable objects with tenantId")
    void listAllWithRows() throws Exception {
        PreparedStatement ps = mock(PreparedStatement.class);
        ResultSet rs = mock(ResultSet.class);
        when(connection.prepareStatement(contains("WHERE tenant_id = ?"))).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true, true, false);
        when(rs.getString("tenant_id")).thenReturn(DEFAULT, DEFAULT);
        when(rs.getString("key")).thenReturn("a", "b");
        when(rs.getString("value")).thenReturn("1", "2");
        when(rs.getString("description")).thenReturn("desc-a", null);
        when(rs.getBoolean("exportable")).thenReturn(true, false);

        List<GlobalVariable> result = store.listAll(DEFAULT);

        assertEquals(2, result.size());
        assertEquals(DEFAULT, result.get(0).tenantId());
        assertEquals("a", result.get(0).key());
    }

    // ==================== error handling ====================

    @Test
    @DisplayName("getAll — propagates SQLException as RuntimeException")
    void getAllSqlException() throws Exception {
        PreparedStatement ps = mock(PreparedStatement.class);
        when(connection.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeQuery()).thenThrow(new SQLException("test error"));

        RuntimeException ex = assertThrows(RuntimeException.class, () -> store.getAll(DEFAULT));
        assertTrue(ex.getMessage().contains("Failed to list all global variables"));
        assertInstanceOf(SQLException.class, ex.getCause());
    }

    @Test
    @DisplayName("get — propagates SQLException as RuntimeException")
    void getSqlException() throws Exception {
        PreparedStatement ps = mock(PreparedStatement.class);
        when(connection.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeQuery()).thenThrow(new SQLException("test error"));

        RuntimeException ex = assertThrows(RuntimeException.class, () -> store.get(DEFAULT, "key"));
        assertTrue(ex.getMessage().contains("Failed to get global variable"));
    }

    @Test
    @DisplayName("upsert — propagates SQLException as RuntimeException")
    void upsertSqlException() throws Exception {
        PreparedStatement ps = mock(PreparedStatement.class);
        when(connection.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeUpdate()).thenThrow(new SQLException("test error"));

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> store.upsert(new GlobalVariable("k", "v")));
        assertTrue(ex.getMessage().contains("Failed to upsert global variable"));
    }

    @Test
    @DisplayName("delete — propagates SQLException as RuntimeException")
    void deleteSqlException() throws Exception {
        PreparedStatement ps = mock(PreparedStatement.class);
        when(connection.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeUpdate()).thenThrow(new SQLException("test error"));

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> store.delete(DEFAULT, "key"));
        assertTrue(ex.getMessage().contains("Failed to delete global variable"));
    }

    @Test
    @DisplayName("ensureSchema — only creates table once (idempotent)")
    void ensureSchemaIdempotent() throws Exception {
        PreparedStatement ps = mock(PreparedStatement.class);
        ResultSet rs = mock(ResultSet.class);
        when(connection.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(false);

        store.getAll(DEFAULT);
        store.getAll(DEFAULT);

        verify(connection, atMost(1)).createStatement();
    }
}
