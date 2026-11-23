/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.mongo;

import ai.labs.eddi.modules.llm.impl.IProviderCooldownStore;
import ai.labs.eddi.modules.llm.impl.ProviderCooldownStoreContract;
import ai.labs.eddi.modules.llm.mongo.MongoProviderCooldownStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;

/** The shared {@link ProviderCooldownStoreContract} against MongoDB. */
@DisplayName("MongoProviderCooldownStore")
class MongoProviderCooldownStoreTest extends MongoTestBase implements ProviderCooldownStoreContract {

    private MongoProviderCooldownStore store;

    @BeforeEach
    void setUp() {
        dropCollections("provider_cooldowns");
        store = new MongoProviderCooldownStore(getDatabase());
    }

    @Override
    public IProviderCooldownStore cooldownStore() {
        return store;
    }
}
