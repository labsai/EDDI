/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.postgres;

import jakarta.enterprise.inject.Instance;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The readiness check is served at the anonymous {@code /q/health/*} surface,
 * so its payload must not disclose the JDBC URL (host/port/database, sometimes
 * credentials) nor the raw connection-failure text.
 */
class PostgresHealthCheckTest {

    @SuppressWarnings("unchecked")
    private static Instance<DataSource> dataSourceInstance(DataSource dataSource) {
        Instance<DataSource> instance = mock(Instance.class);
        when(instance.get()).thenReturn(dataSource);
        return instance;
    }

    @Test
    void up_doesNotLeakJdbcUrl() throws Exception {
        var metaData = mock(DatabaseMetaData.class);
        when(metaData.getDatabaseProductName()).thenReturn("PostgreSQL");
        when(metaData.getURL()).thenReturn("jdbc:postgresql://secret-host:5432/eddi?user=admin&password=hunter2");
        var connection = mock(Connection.class);
        var statement = mock(Statement.class);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.execute("SELECT 1")).thenReturn(true);
        when(connection.getMetaData()).thenReturn(metaData);
        var dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenReturn(connection);

        HealthCheckResponse response = new PostgresHealthCheck(dataSourceInstance(dataSource)).call();

        assertEquals(HealthCheckResponse.Status.UP, response.getStatus());
        var data = response.getData().orElseGet(Map::of);
        assertFalse(data.containsKey("url"), "readiness payload must not expose the JDBC URL");
    }

    @Test
    void down_doesNotLeakExceptionText() throws Exception {
        var dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenThrow(new SQLException("FATAL: password authentication failed for user \"admin\""));

        HealthCheckResponse response = new PostgresHealthCheck(dataSourceInstance(dataSource)).call();

        assertEquals(HealthCheckResponse.Status.DOWN, response.getStatus());
        var data = response.getData().orElseGet(Map::of);
        assertFalse(data.containsKey("error"), "readiness payload must not expose the raw exception text");
    }
}
