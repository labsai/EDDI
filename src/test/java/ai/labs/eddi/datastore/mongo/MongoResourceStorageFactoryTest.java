/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.mongo;

import ai.labs.eddi.datastore.IResourceStorageFactory;
import com.mongodb.client.MongoDatabase;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests that MongoResourceStorageFactory correctly creates storage instances
 * and exposes the database.
 */
class MongoResourceStorageFactoryTest {

    @Test
    void shouldExposeDatabase() {
        MongoDatabase database = mock(MongoDatabase.class);
        MongoResourceStorageFactory factory = new MongoResourceStorageFactory(database);

        assertSame(database, factory.getDatabase());
    }

    @Test
    void shouldImplementFactoryInterface() {
        MongoDatabase database = mock(MongoDatabase.class);
        MongoResourceStorageFactory factory = new MongoResourceStorageFactory(database);

        assertInstanceOf(IResourceStorageFactory.class, factory);
    }
}
