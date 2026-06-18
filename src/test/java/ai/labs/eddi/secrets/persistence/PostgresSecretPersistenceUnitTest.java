/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.secrets.persistence;

import ai.labs.eddi.secrets.model.EncryptedDek;
import ai.labs.eddi.secrets.model.EncryptedSecret;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import javax.sql.DataSource;
import java.sql.*;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class PostgresSecretPersistenceUnitTest {

    @Mock
    private Instance<DataSource> dataSourceInstance;
    @Mock
    private DataSource dataSource;
    @Mock
    private Connection connection;
    @Mock
    private Statement statement;
    @Mock
    private PreparedStatement preparedStatement;
    @Mock
    private ResultSet resultSet;

    private PostgresSecretPersistence persistence;

    @BeforeEach
    void setUp() throws Exception {
        MockitoAnnotations.openMocks(this);
        lenient().when(dataSourceInstance.get()).thenReturn(dataSource);
        lenient().when(dataSource.getConnection()).thenReturn(connection);
        lenient().when(connection.createStatement()).thenReturn(statement);
        lenient().when(connection.prepareStatement(anyString())).thenReturn(preparedStatement);

        persistence = new PostgresSecretPersistence(dataSourceInstance);
    }

    // ─── upsertSecret ───

    @Test
    void upsertSecret_happyPath() throws Exception {
        when(preparedStatement.executeUpdate()).thenReturn(1);

        EncryptedSecret secret = createTestSecret();
        assertDoesNotThrow(() -> persistence.upsertSecret(secret));

        verify(preparedStatement).setString(1, "tenant-1");
        verify(preparedStatement).setString(2, "api-key");
        verify(preparedStatement).executeUpdate();
    }

    @Test
    void upsertSecret_nullAllowedAgents_defaultsToWildcard() throws Exception {
        when(preparedStatement.executeUpdate()).thenReturn(1);

        EncryptedSecret secret = createTestSecret();
        secret.setAllowedAgents(null);
        persistence.upsertSecret(secret);

        verify(preparedStatement).setString(eq(8), contains("*"));
    }

    @Test
    void upsertSecret_sqlException_throwsPersistenceException() throws Exception {
        when(preparedStatement.executeUpdate()).thenThrow(new SQLException("DB error"));

        EncryptedSecret secret = createTestSecret();
        assertThrows(PersistenceException.class, () -> persistence.upsertSecret(secret));
    }

    // ─── findSecret ───

    @Test
    void findSecret_found() throws Exception {
        when(preparedStatement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true);
        mockSecretResultSet();

        Optional<EncryptedSecret> result = persistence.findSecret("tenant-1", "api-key");

        assertTrue(result.isPresent());
        assertEquals("tenant-1", result.get().getTenantId());
        assertEquals("api-key", result.get().getKeyName());
    }

    @Test
    void findSecret_notFound_returnsEmpty() throws Exception {
        when(preparedStatement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(false);

        Optional<EncryptedSecret> result = persistence.findSecret("tenant-1", "missing-key");
        assertTrue(result.isEmpty());
    }

    @Test
    void findSecret_sqlException_throwsPersistenceException() throws Exception {
        when(preparedStatement.executeQuery()).thenThrow(new SQLException("DB error"));

        assertThrows(PersistenceException.class, () -> persistence.findSecret("t", "k"));
    }

    // ─── deleteSecret ───

    @Test
    void deleteSecret_exists_returnsTrue() throws Exception {
        when(preparedStatement.executeUpdate()).thenReturn(1);
        assertTrue(persistence.deleteSecret("tenant-1", "api-key"));
    }

    @Test
    void deleteSecret_notExists_returnsFalse() throws Exception {
        when(preparedStatement.executeUpdate()).thenReturn(0);
        assertFalse(persistence.deleteSecret("tenant-1", "missing"));
    }

    @Test
    void deleteSecret_sqlException_throwsPersistenceException() throws Exception {
        when(preparedStatement.executeUpdate()).thenThrow(new SQLException("DB error"));
        assertThrows(PersistenceException.class, () -> persistence.deleteSecret("t", "k"));
    }

    // ─── listSecretsByTenant ───

    @Test
    void listSecretsByTenant_returnsList() throws Exception {
        when(preparedStatement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true, true, false);
        mockSecretResultSet();

        List<EncryptedSecret> secrets = persistence.listSecretsByTenant("tenant-1");
        assertEquals(2, secrets.size());
    }

    @Test
    void listSecretsByTenant_empty() throws Exception {
        when(preparedStatement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(false);

        List<EncryptedSecret> secrets = persistence.listSecretsByTenant("tenant-empty");
        assertTrue(secrets.isEmpty());
    }

    @Test
    void listSecretsByTenant_sqlException_throwsPersistenceException() throws Exception {
        when(preparedStatement.executeQuery()).thenThrow(new SQLException("DB error"));
        assertThrows(PersistenceException.class, () -> persistence.listSecretsByTenant("t"));
    }

    // ─── upsertDek ───

    @Test
    void upsertDek_happyPath() throws Exception {
        when(preparedStatement.executeUpdate()).thenReturn(1);

        EncryptedDek dek = new EncryptedDek("dek-1", "tenant-1", "encDek", "dekIv", Instant.now());
        assertDoesNotThrow(() -> persistence.upsertDek(dek));

        verify(preparedStatement).setString(1, "tenant-1");
        verify(preparedStatement).setString(2, "encDek");
        verify(preparedStatement).executeUpdate();
    }

    @Test
    void upsertDek_sqlException_throwsPersistenceException() throws Exception {
        when(preparedStatement.executeUpdate()).thenThrow(new SQLException("DB error"));

        EncryptedDek dek = new EncryptedDek("dek-1", "tenant-1", "encDek", "iv", Instant.now());
        assertThrows(PersistenceException.class, () -> persistence.upsertDek(dek));
    }

    // ─── findDek ───

    @Test
    void findDek_found() throws Exception {
        when(preparedStatement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getString("id")).thenReturn("dek-id-1");
        when(resultSet.getString("tenant_id")).thenReturn("tenant-1");
        when(resultSet.getString("encrypted_dek")).thenReturn("encDek");
        when(resultSet.getString("iv")).thenReturn("dekIv");
        Timestamp ts = Timestamp.from(Instant.now());
        when(resultSet.getTimestamp("created_at")).thenReturn(ts);

        Optional<EncryptedDek> result = persistence.findDek("tenant-1");
        assertTrue(result.isPresent());
        assertEquals("tenant-1", result.get().getTenantId());
        assertEquals("encDek", result.get().getEncryptedDek());
    }

    @Test
    void findDek_found_nullTimestamp() throws Exception {
        when(preparedStatement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getString("id")).thenReturn("dek-id-1");
        when(resultSet.getString("tenant_id")).thenReturn("tenant-1");
        when(resultSet.getString("encrypted_dek")).thenReturn("encDek");
        when(resultSet.getString("iv")).thenReturn("dekIv");
        when(resultSet.getTimestamp("created_at")).thenReturn(null);

        Optional<EncryptedDek> result = persistence.findDek("tenant-1");
        assertTrue(result.isPresent());
        assertNull(result.get().getCreatedAt());
    }

    @Test
    void findDek_notFound() throws Exception {
        when(preparedStatement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(false);

        Optional<EncryptedDek> result = persistence.findDek("tenant-missing");
        assertTrue(result.isEmpty());
    }

    @Test
    void findDek_sqlException_throwsPersistenceException() throws Exception {
        when(preparedStatement.executeQuery()).thenThrow(new SQLException("DB error"));
        assertThrows(PersistenceException.class, () -> persistence.findDek("t"));
    }

    // ─── deleteDek ───

    @Test
    void deleteDek_happyPath() throws Exception {
        when(preparedStatement.executeUpdate()).thenReturn(1);
        assertDoesNotThrow(() -> persistence.deleteDek("tenant-1"));
        verify(preparedStatement).setString(1, "tenant-1");
    }

    @Test
    void deleteDek_sqlException_throwsPersistenceException() throws Exception {
        when(preparedStatement.executeUpdate()).thenThrow(new SQLException("DB error"));
        assertThrows(PersistenceException.class, () -> persistence.deleteDek("t"));
    }

    // ─── listAllDeks ───

    @Test
    void listAllDeks_returnsList() throws Exception {
        when(preparedStatement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true, false);
        when(resultSet.getString("id")).thenReturn("dek-1");
        when(resultSet.getString("tenant_id")).thenReturn("tenant-1");
        when(resultSet.getString("encrypted_dek")).thenReturn("encDek");
        when(resultSet.getString("iv")).thenReturn("iv");
        when(resultSet.getTimestamp("created_at")).thenReturn(Timestamp.from(Instant.now()));

        List<EncryptedDek> deks = persistence.listAllDeks();
        assertEquals(1, deks.size());
    }

    @Test
    void listAllDeks_empty() throws Exception {
        when(preparedStatement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(false);

        List<EncryptedDek> deks = persistence.listAllDeks();
        assertTrue(deks.isEmpty());
    }

    @Test
    void listAllDeks_sqlException_throwsPersistenceException() throws Exception {
        when(preparedStatement.executeQuery()).thenThrow(new SQLException("DB error"));
        assertThrows(PersistenceException.class, () -> persistence.listAllDeks());
    }

    // ─── getMetaValue ───

    @Test
    void getMetaValue_found() throws Exception {
        when(preparedStatement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getString("value")).thenReturn("meta-val");

        assertEquals("meta-val", persistence.getMetaValue("salt"));
    }

    @Test
    void getMetaValue_notFound_returnsNull() throws Exception {
        when(preparedStatement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(false);

        assertNull(persistence.getMetaValue("missing-key"));
    }

    @Test
    void getMetaValue_sqlException_throwsPersistenceException() throws Exception {
        when(preparedStatement.executeQuery()).thenThrow(new SQLException("DB error"));
        assertThrows(PersistenceException.class, () -> persistence.getMetaValue("k"));
    }

    // ─── setMetaValue ───

    @Test
    void setMetaValue_happyPath() throws Exception {
        when(preparedStatement.executeUpdate()).thenReturn(1);
        assertDoesNotThrow(() -> persistence.setMetaValue("salt", "abc123"));
        verify(preparedStatement).setString(1, "salt");
        verify(preparedStatement).setString(2, "abc123");
    }

    @Test
    void setMetaValue_sqlException_throwsPersistenceException() throws Exception {
        when(preparedStatement.executeUpdate()).thenThrow(new SQLException("DB error"));
        assertThrows(PersistenceException.class, () -> persistence.setMetaValue("k", "v"));
    }

    // ─── ensureSchema idempotency ───

    @Test
    void ensureSchema_calledOnlyOnce() throws Exception {
        when(preparedStatement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(false);

        persistence.findSecret("t", "k");
        persistence.findSecret("t", "k");

        // Schema init creates Statement once, subsequent calls skip
        verify(connection, times(1)).createStatement();
    }

    // ─── resultSetToSecret — allowed_agents JSON parsing ───

    @Test
    void findSecret_invalidAllowedAgentsJson_defaultsToWildcard() throws Exception {
        when(preparedStatement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getString("id")).thenReturn("sec-1");
        when(resultSet.getString("tenant_id")).thenReturn("t");
        when(resultSet.getString("key_name")).thenReturn("k");
        when(resultSet.getString("encrypted_value")).thenReturn("ev");
        when(resultSet.getString("iv")).thenReturn("iv");
        when(resultSet.getString("dek_id")).thenReturn("dek");
        when(resultSet.getString("checksum")).thenReturn("chk");
        when(resultSet.getString("description")).thenReturn("desc");
        when(resultSet.getString("allowed_agents")).thenReturn("not-json");
        when(resultSet.getTimestamp("created_at")).thenReturn(null);
        when(resultSet.getTimestamp("last_accessed_at")).thenReturn(null);
        when(resultSet.getTimestamp("last_rotated_at")).thenReturn(null);

        Optional<EncryptedSecret> result = persistence.findSecret("t", "k");
        assertTrue(result.isPresent());
        assertEquals(List.of("*"), result.get().getAllowedAgents());
    }

    @Test
    void findSecret_nullAllowedAgentsJson_defaultsToWildcard() throws Exception {
        when(preparedStatement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getString("id")).thenReturn("sec-1");
        when(resultSet.getString("tenant_id")).thenReturn("t");
        when(resultSet.getString("key_name")).thenReturn("k");
        when(resultSet.getString("encrypted_value")).thenReturn("ev");
        when(resultSet.getString("iv")).thenReturn("iv");
        when(resultSet.getString("dek_id")).thenReturn("dek");
        when(resultSet.getString("checksum")).thenReturn("chk");
        when(resultSet.getString("description")).thenReturn("desc");
        when(resultSet.getString("allowed_agents")).thenReturn(null);
        when(resultSet.getTimestamp("created_at")).thenReturn(null);
        when(resultSet.getTimestamp("last_accessed_at")).thenReturn(null);
        when(resultSet.getTimestamp("last_rotated_at")).thenReturn(null);

        Optional<EncryptedSecret> result = persistence.findSecret("t", "k");
        assertTrue(result.isPresent());
        assertEquals(List.of("*"), result.get().getAllowedAgents());
    }

    // ─── Helpers ───

    private EncryptedSecret createTestSecret() {
        Instant now = Instant.now();
        return new EncryptedSecret("sec-1", "tenant-1", "api-key", "encVal", "iv123",
                "dek-1", "chk", "Test secret", List.of("agent-1"), now, now, now);
    }

    private void mockSecretResultSet() throws SQLException {
        Timestamp now = Timestamp.from(Instant.now());
        when(resultSet.getString("id")).thenReturn("sec-1");
        when(resultSet.getString("tenant_id")).thenReturn("tenant-1");
        when(resultSet.getString("key_name")).thenReturn("api-key");
        when(resultSet.getString("encrypted_value")).thenReturn("encVal");
        when(resultSet.getString("iv")).thenReturn("iv123");
        when(resultSet.getString("dek_id")).thenReturn("dek-1");
        when(resultSet.getString("checksum")).thenReturn("chk");
        when(resultSet.getString("description")).thenReturn("desc");
        when(resultSet.getString("allowed_agents")).thenReturn("[\"agent-1\"]");
        when(resultSet.getTimestamp("created_at")).thenReturn(now);
        when(resultSet.getTimestamp("last_accessed_at")).thenReturn(now);
        when(resultSet.getTimestamp("last_rotated_at")).thenReturn(now);
    }
}
