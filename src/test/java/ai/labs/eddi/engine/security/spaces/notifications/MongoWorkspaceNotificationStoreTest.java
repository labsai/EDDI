/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.security.spaces.notifications;

import ai.labs.eddi.datastore.mongo.MongoTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;

/** {@link WorkspaceNotificationStoreContract} against a real MongoDB. */
@DisplayName("MongoWorkspaceNotificationStore")
class MongoWorkspaceNotificationStoreTest extends MongoTestBase implements WorkspaceNotificationStoreContract {

    private MongoWorkspaceNotificationStore store;

    @BeforeEach
    void setUp() {
        dropCollections(MongoWorkspaceNotificationStore.COLLECTION);
        store = new MongoWorkspaceNotificationStore(getDatabase());
    }

    @Override
    public IWorkspaceNotificationStore store() {
        return store;
    }
}
