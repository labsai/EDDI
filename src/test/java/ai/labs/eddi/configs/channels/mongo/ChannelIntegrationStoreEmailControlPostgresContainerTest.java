/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.channels.mongo;

import ai.labs.eddi.datastore.postgres.PostgresResourceStorageFactory;
import ai.labs.eddi.datastore.postgres.PostgresTestBase;
import ai.labs.eddi.datastore.serialization.DocumentBuilder;
import ai.labs.eddi.datastore.serialization.JsonSerialization;
import ai.labs.eddi.datastore.serialization.SerializationCustomizer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link ChannelIntegrationStoreEmailControlMongoContainerTest} against a real
 * PostgreSQL: the same document through the JSON column.
 */
@DisplayName("ChannelIntegrationStore on PostgreSQL — emailControl round trip")
class ChannelIntegrationStoreEmailControlPostgresContainerTest extends PostgresTestBase {

    private ChannelIntegrationStore store;

    @BeforeEach
    void setUp() {
        var json = new JsonSerialization(SerializationCustomizer.configureObjectMapper(new ObjectMapper(), false));
        store = new ChannelIntegrationStore(new PostgresResourceStorageFactory(createDataSourceInstance(), json), new DocumentBuilder(json));
    }

    @Test
    @DisplayName("emailControl is stored, versioned and read back unchanged")
    void roundTrip() throws Exception {
        EmailControlRoundTrip.assertRoundTrip(store);
    }
}
