/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.postgres;

import ai.labs.eddi.datastore.IResourceStorage;
import ai.labs.eddi.datastore.IResourceStorageFactory;
import ai.labs.eddi.datastore.serialization.IDocumentBuilder;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests that PostgresResourceStorageFactory correctly creates storage
 * instances.
 */
class PostgresResourceStorageFactoryTest {

    @Test
    void shouldImplementFactoryInterface() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        @SuppressWarnings("unchecked")
        Instance<DataSource> dataSourceInstance = mock(Instance.class);
        when(dataSourceInstance.get()).thenReturn(dataSource);

        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        IJsonSerialization jsonSerialization = mock(IJsonSerialization.class);

        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);

        PostgresResourceStorageFactory factory = new PostgresResourceStorageFactory(dataSourceInstance, jsonSerialization);

        assertInstanceOf(IResourceStorageFactory.class, factory);
    }

    @Test
    void shouldCreatePostgresStorage() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        @SuppressWarnings("unchecked")
        Instance<DataSource> dataSourceInstance = mock(Instance.class);
        when(dataSourceInstance.get()).thenReturn(dataSource);

        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        IJsonSerialization jsonSerialization = mock(IJsonSerialization.class);
        IDocumentBuilder documentBuilder = mock(IDocumentBuilder.class);

        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);

        PostgresResourceStorageFactory factory = new PostgresResourceStorageFactory(dataSourceInstance, jsonSerialization);

        IResourceStorage<String> storage = factory.create("test_collection", documentBuilder, String.class);

        assertNotNull(storage);
        assertInstanceOf(PostgresResourceStorage.class, storage);
    }

    @Test
    void shouldExposeDataSource() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        @SuppressWarnings("unchecked")
        Instance<DataSource> dataSourceInstance = mock(Instance.class);
        when(dataSourceInstance.get()).thenReturn(dataSource);

        IJsonSerialization jsonSerialization = mock(IJsonSerialization.class);

        PostgresResourceStorageFactory factory = new PostgresResourceStorageFactory(dataSourceInstance, jsonSerialization);

        assertSame(dataSource, factory.getDataSource());
    }
}
