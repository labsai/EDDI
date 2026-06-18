/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.postgres;

import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.triggermanagement.model.AgentTriggerConfiguration;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import javax.sql.DataSource;
import java.io.IOException;
import java.sql.*;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class PostgresAgentTriggerStoreUnitTest {

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

    private PostgresAgentTriggerStore store;
    private AutoCloseable mocks;

    @BeforeEach
    void setUp() throws Exception {
        mocks = MockitoAnnotations.openMocks(this);
        lenient().when(dataSourceInstance.get()).thenReturn(dataSource);
        lenient().when(dataSource.getConnection()).thenReturn(connection);
        lenient().when(connection.createStatement()).thenReturn(statement);
        lenient().when(connection.prepareStatement(anyString())).thenReturn(preparedStatement);

        store = new PostgresAgentTriggerStore(dataSourceInstance, jsonSerialization);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (mocks != null) {
            mocks.close();
        }
    }

    // ─── readAllAgentTriggers ───

    @Test
    void readAllAgentTriggers_returnsList() throws Exception {
        when(preparedStatement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true, true, false);
        when(resultSet.getString("data")).thenReturn("{\"intent\":\"greet\"}");
        AgentTriggerConfiguration config = new AgentTriggerConfiguration();
        config.setIntent("greet");
        when(jsonSerialization.deserialize(anyString(), eq(AgentTriggerConfiguration.class))).thenReturn(config);

        List<AgentTriggerConfiguration> triggers = store.readAllAgentTriggers();
        assertEquals(2, triggers.size());
        verify(resultSet).close();
    }

    @Test
    void readAllAgentTriggers_empty() throws Exception {
        when(preparedStatement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(false);

        List<AgentTriggerConfiguration> triggers = store.readAllAgentTriggers();
        assertTrue(triggers.isEmpty());
        verify(resultSet).close();
    }

    @Test
    void readAllAgentTriggers_exception_throwsResourceStoreException() throws Exception {
        when(preparedStatement.executeQuery()).thenThrow(new SQLException("error"));

        assertThrows(IResourceStore.ResourceStoreException.class, () -> store.readAllAgentTriggers());
    }

    // ─── readAgentTrigger ───

    @Test
    void readAgentTrigger_found() throws Exception {
        when(preparedStatement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getString("data")).thenReturn("{\"intent\":\"greet\"}");
        AgentTriggerConfiguration config = new AgentTriggerConfiguration();
        config.setIntent("greet");
        when(jsonSerialization.deserialize(anyString(), eq(AgentTriggerConfiguration.class))).thenReturn(config);

        AgentTriggerConfiguration result = store.readAgentTrigger("greet");
        assertEquals("greet", result.getIntent());
        verify(resultSet).close();
    }

    @Test
    void readAgentTrigger_notFound_throwsResourceNotFoundException() throws Exception {
        when(preparedStatement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(false);

        assertThrows(IResourceStore.ResourceNotFoundException.class, () -> store.readAgentTrigger("missing"));
        verify(resultSet).close();
    }

    @Test
    void readAgentTrigger_sqlException_throwsResourceStoreException() throws Exception {
        when(preparedStatement.executeQuery()).thenThrow(new SQLException("error"));

        assertThrows(IResourceStore.ResourceStoreException.class, () -> store.readAgentTrigger("greet"));
    }

    // ─── updateAgentTrigger ───

    @Test
    void updateAgentTrigger_happyPath() throws Exception {
        when(jsonSerialization.serialize(any())).thenReturn("{}");
        when(preparedStatement.executeUpdate()).thenReturn(1);

        AgentTriggerConfiguration config = new AgentTriggerConfiguration();
        config.setIntent("greet");
        assertDoesNotThrow(() -> store.updateAgentTrigger("greet", config));
    }

    @Test
    void updateAgentTrigger_notFound_throwsResourceNotFoundException() throws Exception {
        when(jsonSerialization.serialize(any())).thenReturn("{}");
        when(preparedStatement.executeUpdate()).thenReturn(0);

        AgentTriggerConfiguration config = new AgentTriggerConfiguration();
        assertThrows(IResourceStore.ResourceNotFoundException.class, () -> store.updateAgentTrigger("missing", config));
    }

    @Test
    void updateAgentTrigger_sqlException_throwsResourceStoreException() throws Exception {
        when(jsonSerialization.serialize(any())).thenReturn("{}");
        when(preparedStatement.executeUpdate()).thenThrow(new SQLException("error"));

        AgentTriggerConfiguration config = new AgentTriggerConfiguration();
        assertThrows(IResourceStore.ResourceStoreException.class, () -> store.updateAgentTrigger("greet", config));
    }

    // ─── createAgentTrigger ───

    @Test
    void createAgentTrigger_happyPath() throws Exception {
        // Check query returns no existing record
        when(preparedStatement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(false);
        when(jsonSerialization.serialize(any())).thenReturn("{}");
        when(preparedStatement.executeUpdate()).thenReturn(1);

        AgentTriggerConfiguration config = new AgentTriggerConfiguration();
        config.setIntent("newIntent");
        assertDoesNotThrow(() -> store.createAgentTrigger(config));
        verify(resultSet).close();
    }

    @Test
    void createAgentTrigger_alreadyExists_throwsResourceAlreadyExistsException() throws Exception {
        when(preparedStatement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true);

        AgentTriggerConfiguration config = new AgentTriggerConfiguration();
        config.setIntent("existing");
        assertThrows(IResourceStore.ResourceAlreadyExistsException.class, () -> store.createAgentTrigger(config));
        verify(resultSet).close();
    }

    @Test
    void createAgentTrigger_checkException_throwsResourceStoreException() throws Exception {
        when(preparedStatement.executeQuery()).thenThrow(new SQLException("error"));

        AgentTriggerConfiguration config = new AgentTriggerConfiguration();
        config.setIntent("greet");
        assertThrows(IResourceStore.ResourceStoreException.class, () -> store.createAgentTrigger(config));
    }

    // ─── deleteAgentTrigger ───

    @Test
    void deleteAgentTrigger_happyPath() throws Exception {
        when(preparedStatement.executeUpdate()).thenReturn(1);
        assertDoesNotThrow(() -> store.deleteAgentTrigger("greet"));
    }

    @Test
    void deleteAgentTrigger_notFound_throwsResourceNotFoundException() throws Exception {
        when(preparedStatement.executeUpdate()).thenReturn(0);
        assertThrows(IResourceStore.ResourceNotFoundException.class, () -> store.deleteAgentTrigger("missing"));
    }

    @Test
    void deleteAgentTrigger_sqlException_throwsResourceStoreException() throws Exception {
        when(preparedStatement.executeUpdate()).thenThrow(new SQLException("error"));
        assertThrows(IResourceStore.ResourceStoreException.class, () -> store.deleteAgentTrigger("greet"));
    }
}
