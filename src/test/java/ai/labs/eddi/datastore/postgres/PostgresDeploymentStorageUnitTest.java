/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.postgres;

import ai.labs.eddi.configs.deployment.model.DeploymentInfo;
import ai.labs.eddi.configs.deployment.model.DeploymentInfo.DeploymentStatus;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.model.Deployment.Environment;
import ai.labs.eddi.utils.LogCaptureSupport;
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
import static org.mockito.Mockito.*;

class PostgresDeploymentStorageUnitTest {

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

    private PostgresDeploymentStorage storage;

    @BeforeEach
    void setUp() throws Exception {
        MockitoAnnotations.openMocks(this);
        lenient().when(dataSourceInstance.get()).thenReturn(dataSource);
        lenient().when(dataSource.getConnection()).thenReturn(connection);
        lenient().when(connection.createStatement()).thenReturn(statement);
        lenient().when(connection.prepareStatement(anyString())).thenReturn(preparedStatement);

        storage = new PostgresDeploymentStorage(dataSourceInstance);
    }

    // ─── setDeploymentInfo ───

    @Test
    void setDeploymentInfo_happyPath() throws Exception {
        when(preparedStatement.executeUpdate()).thenReturn(1);

        assertDoesNotThrow(() -> storage.setDeploymentInfo("production", "agent-1", 1, DeploymentStatus.deployed));

        verify(preparedStatement).setString(1, "production");
        verify(preparedStatement).setString(2, "agent-1");
        verify(preparedStatement).setInt(3, 1);
        verify(preparedStatement).setString(4, "deployed");
    }

    @Test
    void setDeploymentInfo_sqlException_throwsRuntimeException() throws Exception {
        when(preparedStatement.executeUpdate()).thenThrow(new SQLException("error"));

        assertThrows(RuntimeException.class,
                () -> storage.setDeploymentInfo("production", "agent-1", 1, DeploymentStatus.deployed));
    }

    // ─── readDeploymentInfo ───

    @Test
    void readDeploymentInfo_found() throws Exception {
        when(preparedStatement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true);
        mockDeploymentResultSet();

        DeploymentInfo info = storage.readDeploymentInfo("production", "agent-1", 1);

        assertNotNull(info);
        assertEquals(Environment.production, info.getEnvironment());
        assertEquals("agent-1", info.getAgentId());
        assertEquals(1, info.getAgentVersion());
        assertEquals(DeploymentStatus.deployed, info.getDeploymentStatus());
    }

    @Test
    void readDeploymentInfo_notFound_returnsNull() throws Exception {
        when(preparedStatement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(false);

        assertNull(storage.readDeploymentInfo("production", "agent-1", 1));
    }

    @Test
    void readDeploymentInfo_sqlException_throwsResourceStoreException() throws Exception {
        when(preparedStatement.executeQuery()).thenThrow(new SQLException("error"));

        assertThrows(IResourceStore.ResourceStoreException.class,
                () -> storage.readDeploymentInfo("production", "agent-1", 1));
    }

    // ─── readDeploymentInfos (no filter) ───

    @Test
    void readDeploymentInfos_noFilter_returnsList() throws Exception {
        when(preparedStatement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true, false);
        mockDeploymentResultSet();

        List<DeploymentInfo> infos = storage.readDeploymentInfos();
        assertEquals(1, infos.size());
    }

    @Test
    void readDeploymentInfos_noFilter_empty() throws Exception {
        when(preparedStatement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(false);

        List<DeploymentInfo> infos = storage.readDeploymentInfos();
        assertTrue(infos.isEmpty());
    }

    // ─── readDeploymentInfos (with status filter) ───

    @Test
    void readDeploymentInfos_withFilter_returnsList() throws Exception {
        when(preparedStatement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true, false);
        mockDeploymentResultSet();

        List<DeploymentInfo> infos = storage.readDeploymentInfos("deployed");
        assertEquals(1, infos.size());
        verify(preparedStatement).setString(1, "deployed");
    }

    @Test
    void readDeploymentInfos_sqlException_throwsResourceStoreException() throws Exception {
        when(preparedStatement.executeQuery()).thenThrow(new SQLException("error"));

        assertThrows(IResourceStore.ResourceStoreException.class, () -> storage.readDeploymentInfos());
    }

    /**
     * A row with a status this release does not know used to throw out of
     * Enum.valueOf and fail the read of every deployment, startup redeploy
     * included. It is skipped; the others are returned.
     */
    @Test
    void readDeploymentInfos_skipsARowWithAnUnknownStatusAndKeepsTheRest() throws Exception {
        when(preparedStatement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true, true, true, false);
        when(resultSet.getString("environment")).thenReturn("production", "production", "test");
        when(resultSet.getString("AGENT_ID")).thenReturn("agent-1", "agent-bad", "agent-3");
        when(resultSet.getInt("AGENT_VERSION")).thenReturn(1, 2, 3);
        when(resultSet.getString("deployment_status")).thenReturn("deployed", "PAUSED", "undeployed");

        List<DeploymentInfo> infos = storage.readDeploymentInfos();

        assertEquals(List.of("agent-1", "agent-3"), infos.stream().map(DeploymentInfo::getAgentId).toList());
        assertEquals(DeploymentStatus.deployed, infos.get(0).getDeploymentStatus());
        assertEquals(DeploymentStatus.undeployed, infos.get(1).getDeploymentStatus());
        assertEquals(Environment.test, infos.get(1).getEnvironment());
        verifyResourcesClosed();
    }

    @Test
    void readDeploymentInfos_skipsARowWithANullStatus() throws Exception {
        when(preparedStatement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true, true, false);
        when(resultSet.getString("environment")).thenReturn("production");
        when(resultSet.getString("AGENT_ID")).thenReturn("agent-null", "agent-2");
        when(resultSet.getInt("AGENT_VERSION")).thenReturn(1);
        when(resultSet.getString("deployment_status")).thenReturn(null, "deployed");

        assertEquals(List.of("agent-2"), storage.readDeploymentInfos().stream().map(DeploymentInfo::getAgentId).toList());
        verifyResourcesClosed();
    }

    /**
     * The environment is part of the primary key every later write uses. A v5 name
     * read as production would be redeployed by the sweep while undeploy and
     * cleanup, keyed on production, never reach the stored row — so a row whose
     * environment is not exactly a current constant name is skipped, as an unknown
     * status is, never coerced.
     */
    @Test
    void readDeploymentInfos_skipsARowWithALegacyOrUnknownEnvironment() throws Exception {
        when(preparedStatement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true, true, true, true, true, false);
        when(resultSet.getString("environment")).thenReturn("production", "unrestricted", "restricted", "Production", "test");
        when(resultSet.getString("AGENT_ID")).thenReturn("agent-1", "agent-v5a", "agent-v5b", "agent-case", "agent-5");
        when(resultSet.getInt("AGENT_VERSION")).thenReturn(1, 2, 3, 4, 5);
        when(resultSet.getString("deployment_status")).thenReturn("deployed");

        var records = LogCaptureSupport.captureRecordsOf(PostgresDeploymentStorage.class, () -> {
            try {
                assertEquals(List.of("agent-1", "agent-5"), storage.readDeploymentInfos().stream().map(DeploymentInfo::getAgentId).toList());
            } catch (IResourceStore.ResourceStoreException e) {
                throw new AssertionError(e);
            }
        });

        var warnings = records.stream().filter(LogCaptureSupport.CapturedRecord::isWarning).map(LogCaptureSupport.CapturedRecord::message).toList();
        assertEquals(3, warnings.size(), "one WARN per skipped row: " + records);
        assertTrue(warnings.get(0).contains("agentId=agent-v5a version=2: unknown environment 'unrestricted'"), warnings.get(0));
        assertTrue(warnings.get(2).contains("unknown environment 'Production'"), warnings.get(2));
        verifyResourcesClosed();
    }

    @Test
    void readDeploymentInfo_legacyEnvironmentNameReadsAsNoRow() throws Exception {
        when(preparedStatement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true);
        mockDeploymentResultSet();
        when(resultSet.getString("environment")).thenReturn("unrestricted");

        var records = LogCaptureSupport.captureRecordsOf(PostgresDeploymentStorage.class, () -> {
            try {
                assertNull(storage.readDeploymentInfo("unrestricted", "agent-1", 1));
            } catch (IResourceStore.ResourceStoreException e) {
                throw new AssertionError(e);
            }
        });

        assertTrue(records.stream().anyMatch(r -> r.isWarning() && r.message().contains("unknown environment 'unrestricted'")),
                "a WARN names the skipped row: " + records);
        verifyResourcesClosed();
    }

    @Test
    void readDeploymentInfo_unknownStatusReadsAsNoRow() throws Exception {
        when(preparedStatement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true);
        mockDeploymentResultSet();
        when(resultSet.getString("deployment_status")).thenReturn("PAUSED");

        assertNull(storage.readDeploymentInfo("production", "agent-1", 1));
        verifyResourcesClosed();
    }

    // ─── Helpers ───

    /**
     * The read closes its ResultSet, statement and connection, skip path included.
     * The connection is opened twice: once by the schema check, once by the read.
     */
    private void verifyResourcesClosed() throws SQLException {
        verify(resultSet).close();
        verify(preparedStatement).close();
        verify(connection, times(2)).close();
    }

    private void mockDeploymentResultSet() throws SQLException {
        when(resultSet.getString("environment")).thenReturn("production");
        when(resultSet.getString("AGENT_ID")).thenReturn("agent-1");
        when(resultSet.getInt("AGENT_VERSION")).thenReturn(1);
        when(resultSet.getString("deployment_status")).thenReturn("deployed");
    }
}
