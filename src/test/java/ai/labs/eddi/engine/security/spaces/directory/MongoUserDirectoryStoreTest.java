/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.security.spaces.directory;

import ai.labs.eddi.datastore.mongo.MongoTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;

/** {@link UserDirectoryStoreContract} against a real MongoDB. */
@DisplayName("MongoUserDirectoryStore")
class MongoUserDirectoryStoreTest extends MongoTestBase implements UserDirectoryStoreContract {

    private MongoUserDirectoryStore store;

    @BeforeEach
    void setUp() {
        dropCollections(MongoUserDirectoryStore.COLLECTION);
        store = new MongoUserDirectoryStore(getDatabase());
    }

    @Override
    public IUserDirectoryStore store() {
        return store;
    }
}
