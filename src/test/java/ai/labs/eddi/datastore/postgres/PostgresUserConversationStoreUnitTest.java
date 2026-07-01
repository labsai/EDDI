/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.postgres;

import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.model.Deployment;
import ai.labs.eddi.engine.triggermanagement.model.UserConversation;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import javax.sql.DataSource;
import java.sql.*;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class PostgresUserConversationStoreUnitTest {

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
    @Mock
    private IJsonSerialization jsonSerialization;

    private PostgresUserConversationStore store;

    @BeforeEach
    void setUp() throws Exception {
        MockitoAnnotations.openMocks(this);
        lenient().when(dataSourceInstance.get()).thenReturn(dataSource);
        lenient().when(dataSource.getConnection()).thenReturn(connection);
        lenient().when(connection.createStatement()).thenReturn(statement);
        lenient().when(connection.prepareStatement(anyString())).thenReturn(preparedStatement);

        store = new PostgresUserConversationStore(dataSourceInstance, jsonSerialization);
    }

    // ─── readUserConversation ───

    @Test
    void readUserConversation_found() throws Exception {
        when(preparedStatement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getString("data")).thenReturn("{\"intent\":\"greet\"}");
        UserConversation conv = createUserConversation();
        when(jsonSerialization.deserialize(anyString(), eq(UserConversation.class))).thenReturn(conv);

        UserConversation result = store.readUserConversation("greet", "user-1");

        assertNotNull(result);
        assertEquals("greet", result.getIntent());
    }

    @Test
    void readUserConversation_notFound_returnsNull() throws Exception {
        when(preparedStatement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(false);

        assertNull(store.readUserConversation("missing", "user-1"));
    }

    @Test
    void readUserConversation_exception_throwsResourceStoreException() throws Exception {
        when(preparedStatement.executeQuery()).thenThrow(new SQLException("error"));

        assertThrows(IResourceStore.ResourceStoreException.class,
                () -> store.readUserConversation("greet", "user-1"));
    }

    // ─── createUserConversation ───

    @Test
    void createUserConversation_happyPath() throws Exception {
        // First call readUserConversation (returns null => not exists)
        when(preparedStatement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(false);
        when(jsonSerialization.serialize(any())).thenReturn("{}");
        when(preparedStatement.executeUpdate()).thenReturn(1);

        UserConversation conv = createUserConversation();
        assertDoesNotThrow(() -> store.createUserConversation(conv));
    }

    @Test
    void createUserConversation_alreadyExists_throwsResourceAlreadyExistsException() throws Exception {
        when(preparedStatement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getString("data")).thenReturn("{}");
        UserConversation existing = createUserConversation();
        when(jsonSerialization.deserialize(anyString(), eq(UserConversation.class))).thenReturn(existing);

        UserConversation conv = createUserConversation();
        assertThrows(IResourceStore.ResourceAlreadyExistsException.class,
                () -> store.createUserConversation(conv));
    }

    @Test
    void createUserConversation_insertException_throwsResourceStoreException() throws Exception {
        // Read check: no existing record
        when(preparedStatement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(false);
        when(jsonSerialization.serialize(any())).thenThrow(new RuntimeException("ser error"));

        UserConversation conv = createUserConversation();
        assertThrows(IResourceStore.ResourceStoreException.class,
                () -> store.createUserConversation(conv));
    }

    // ─── deleteUserConversation ───

    @Test
    void deleteUserConversation_happyPath() throws Exception {
        when(preparedStatement.executeUpdate()).thenReturn(1);
        assertDoesNotThrow(() -> store.deleteUserConversation("greet", "user-1"));
        verify(preparedStatement).setString(1, "greet");
        verify(preparedStatement).setString(2, "user-1");
    }

    @Test
    void deleteUserConversation_sqlException_logsError() throws Exception {
        when(preparedStatement.executeUpdate()).thenThrow(new SQLException("error"));
        // Should not throw, just logs
        assertDoesNotThrow(() -> store.deleteUserConversation("greet", "user-1"));
    }

    // ─── GDPR: deleteAllForUser ───

    @Test
    void deleteAllForUser_returnsCount() throws Exception {
        when(preparedStatement.executeUpdate()).thenReturn(3);

        assertEquals(3, store.deleteAllForUser("user-1"));
    }

    @Test
    void deleteAllForUser_sqlException_returnsZero() throws Exception {
        when(preparedStatement.executeUpdate()).thenThrow(new SQLException("error"));

        assertEquals(0, store.deleteAllForUser("user-1"));
    }

    // ─── getAllForUser ───

    @Test
    void getAllForUser_returnsList() throws Exception {
        when(preparedStatement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true, true, false);
        when(resultSet.getString("data")).thenReturn("{}");
        UserConversation conv = createUserConversation();
        when(jsonSerialization.deserialize(anyString(), eq(UserConversation.class))).thenReturn(conv);

        List<UserConversation> results = store.getAllForUser("user-1");
        assertEquals(2, results.size());
    }

    @Test
    void getAllForUser_empty() throws Exception {
        when(preparedStatement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(false);

        List<UserConversation> results = store.getAllForUser("user-1");
        assertTrue(results.isEmpty());
    }

    @Test
    void getAllForUser_exception_throwsResourceStoreException() throws Exception {
        when(preparedStatement.executeQuery()).thenThrow(new SQLException("error"));

        assertThrows(IResourceStore.ResourceStoreException.class,
                () -> store.getAllForUser("user-1"));
    }

    // ─── Helpers ───

    private UserConversation createUserConversation() {
        return new UserConversation("greet", "user-1", Deployment.Environment.production, "agent-1", "conv-1");
    }
}
