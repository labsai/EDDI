/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.mongo;

import ai.labs.eddi.modules.llm.tools.quota.IToolQuotaStore;
import ai.labs.eddi.modules.llm.tools.quota.MongoToolQuotaStore;
import ai.labs.eddi.modules.llm.tools.quota.ToolQuotaStoreContract;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MongoToolQuotaStore} against a real MongoDB (Testcontainers): the
 * shared {@link ToolQuotaStoreContract} plus the indexes.
 */
@DisplayName("MongoToolQuotaStore")
class MongoToolQuotaStoreIT extends MongoTestBase implements ToolQuotaStoreContract {

    private static final String COLLECTION = "toolquotacounters";

    private IToolQuotaStore store;

    @BeforeEach
    void setUp() {
        store = new MongoToolQuotaStore(getDatabase());
    }

    @Override
    public IToolQuotaStore store() {
        return store;
    }

    @Test
    void createsTheSubjectIndexAndTheTtlBackstop() {
        List<Document> indexes = getDatabase().getCollection(COLLECTION).listIndexes().into(new ArrayList<>());
        List<String> names = indexes.stream().map(index -> index.getString("name")).toList();
        assertTrue(names.containsAll(List.of("idx_tool_quota_subject", "idx_tool_quota_ttl")), names.toString());
        Document ttl = indexes.stream().filter(index -> "idx_tool_quota_ttl".equals(index.getString("name"))).findFirst().orElseThrow();
        assertEquals(0L, ((Number) ttl.get("expireAfterSeconds")).longValue());
    }
}
