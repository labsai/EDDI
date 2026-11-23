/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.channels.mongo;

import ai.labs.eddi.datastore.mongo.MongoResourceStorageFactory;
import ai.labs.eddi.datastore.mongo.MongoTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The sender roles survive a real MongoDB: a store that dropped
 * {@code emailControl} would put every sender back on role {@code default}
 * (email plan v3 §9) — the silent fail-open this guards against.
 */
@DisplayName("ChannelIntegrationStore on MongoDB — emailControl round trip")
class ChannelIntegrationStoreEmailControlMongoContainerTest extends MongoTestBase {

    private ChannelIntegrationStore store;

    @BeforeEach
    void setUp() {
        dropCollections("channels", "channels.history");
        store = new ChannelIntegrationStore(new MongoResourceStorageFactory(getDatabase()), documentBuilder);
    }

    @Test
    @DisplayName("emailControl is stored, versioned and read back unchanged")
    void roundTrip() throws Exception {
        EmailControlRoundTrip.assertRoundTrip(store);
    }
}
