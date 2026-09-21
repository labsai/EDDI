/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.postgres;

import ai.labs.eddi.modules.ingestion.IngestionStateStoreException;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What the store does when it cannot create its schema.
 */
class PostgresIngestionStateStoreSchemaTest {

    @Test
    @DisplayName("a failed schema creation is reported, not logged and ignored")
    @SuppressWarnings("unchecked")
    void schemaFailurePropagates() throws Exception {
        // Logging and carrying on left the store to fail later on a missing table,
        // with an error that says nothing about why the table is missing.
        SQLException cause = new SQLException("permission denied for schema public");
        Statement statement = mock(Statement.class);
        when(statement.execute(anyString())).thenThrow(cause);
        Connection connection = mock(Connection.class);
        when(connection.createStatement()).thenReturn(statement);
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenReturn(connection);
        Instance<DataSource> instance = mock(Instance.class);
        when(instance.get()).thenReturn(dataSource);

        var store = new PostgresIngestionStateStore(instance);

        var thrown = assertThrows(IngestionStateStoreException.class, store::ensureSchema);
        assertSame(cause, thrown.getCause());

        // Not marked initialized, so the next call tries again rather than assuming
        // a schema that was never created.
        assertThrows(IngestionStateStoreException.class, store::ensureSchema);
        verify(dataSource, times(2)).getConnection();
    }
}
