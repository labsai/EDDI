/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.mongo;

import ai.labs.eddi.engine.documents.spend.DocumentSpendStoreContract;
import ai.labs.eddi.engine.documents.spend.IDocumentSpendStore;
import ai.labs.eddi.engine.documents.spend.IDocumentSpendStore.SubjectKind;
import ai.labs.eddi.engine.documents.spend.MongoDocumentSpendStore;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MongoDocumentSpendStore} against a real MongoDB (Testcontainers): the
 * shared {@link DocumentSpendStoreContract} (atomic increments under
 * concurrency, retention, erasure) plus the indexes and the TTL backstop.
 */
@DisplayName("MongoDocumentSpendStore")
class MongoDocumentSpendStoreIT extends MongoTestBase implements DocumentSpendStoreContract {

    private static final String COLLECTION = "document_spend_days";

    private IDocumentSpendStore store;

    @BeforeEach
    void setUp() {
        store = new MongoDocumentSpendStore(getDatabase());
    }

    @Override
    public IDocumentSpendStore store() {
        return store;
    }

    @Test
    void createsTheLookupIndexesAndTheTtlBackstopOnFirstUse() {
        store.spent(SubjectKind.USER, "index-probe", TODAY);
        List<Document> indexes = getDatabase().getCollection(COLLECTION).listIndexes().into(new ArrayList<>());
        List<String> names = indexes.stream().map(index -> index.getString("name")).toList();
        assertTrue(names.containsAll(List.of("idx_document_spend_subject", "idx_document_spend_day", "idx_document_spend_ttl")), names.toString());
        Document ttl = indexes.stream().filter(index -> "idx_document_spend_ttl".equals(index.getString("name"))).findFirst().orElseThrow();
        assertEquals(0L, ((Number) ttl.get("expireAfterSeconds")).longValue());
    }

    @Test
    void aBucketExpiresWhenItLeavesTheRetention() {
        String user = DocumentSpendStoreContract.fresh("user-");
        store.add(SubjectKind.USER, user, TODAY, 1.0, TODAY - 34);
        Document stored = getDatabase().getCollection(COLLECTION).find(new Document("subject", user)).first();
        Date expected = Date.from(LocalDate.ofEpochDay(TODAY + 35).atStartOfDay(ZoneOffset.UTC).toInstant());
        assertEquals(expected, stored.getDate("expiresAt"), "35 retained days, today included");
    }
}
