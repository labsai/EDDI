/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.tools.quota;

import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("PostgresToolQuotaStore with the database down")
class PostgresToolQuotaStoreUnitTest {

    @Test
    @DisplayName("every call throws ToolQuotaStoreException (so the caller fails closed) and the next call retries the DDL")
    void downDatabaseThrowsAndRetries() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenThrow(new SQLException("down"));
        @SuppressWarnings("unchecked")
        Instance<DataSource> instance = mock(Instance.class);
        when(instance.get()).thenReturn(dataSource);
        var store = new PostgresToolQuotaStore(instance);
        var key = new ToolQuotaKey("aaaaaaaaaaaaaaaaaaaaaaaa", "tool:a", "u", 0L);

        assertThrows(ToolQuotaStoreException.class, () -> store.tryAcquire(key, 1, Instant.now()));
        assertThrows(ToolQuotaStoreException.class, () -> store.tryAcquire(key, 1, Instant.now()));

        verify(dataSource, times(2)).getConnection();
    }
}
