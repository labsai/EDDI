/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.connections.settings;

import ai.labs.eddi.connections.settings.IConnectionSettingsStore.StoredConnectionSettings;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.ReplaceOptions;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** {@link MongoConnectionSettingsStore} against a mocked driver. */
@SuppressWarnings("unchecked")
class MongoConnectionSettingsStoreTest {

    private MongoCollection<Document> collection;
    private FindIterable<Document> found;
    private MongoConnectionSettingsStore store;

    @BeforeEach
    void setUp() {
        var database = mock(MongoDatabase.class);
        collection = mock(MongoCollection.class);
        found = mock(FindIterable.class);
        when(database.getCollection(MongoConnectionSettingsStore.COLLECTION)).thenReturn(collection);
        when(collection.find(any(Bson.class))).thenReturn(found);
        store = new MongoConnectionSettingsStore(database);
    }

    @Test
    @DisplayName("a tenant that never stored settings reads as empty")
    void absentDocumentReadsAsEmpty() {
        when(found.first()).thenReturn(null);

        assertTrue(store.read("default").isEmpty());
    }

    @Test
    @DisplayName("every stored field is read back, and a missing key reads as unset")
    void storedFieldsAreReadBack() {
        Instant updatedAt = Instant.parse("2026-09-14T10:00:00Z");
        when(found.first()).thenReturn(new Document("_id", "default").append("enabled", true).append("credentialEndpointAllowlist",
                List.of("https://auth.atlassian.com")).append("updatedAt", Date.from(updatedAt)).append("updatedBy", "alice"));

        StoredConnectionSettings stored = store.read("default").orElseThrow();

        assertEquals(true, stored.settings().getEnabled());
        assertNull(stored.settings().getPublicBaseUrl(), "an absent key is unset, not an empty string");
        assertEquals(List.of("https://auth.atlassian.com"), stored.settings().getCredentialEndpointAllowlist());
        assertNull(stored.settings().getAllowPlaintextRemoteOrigins());
        assertEquals(updatedAt, stored.updatedAt());
        assertEquals("alice", stored.updatedBy());
    }

    @Test
    @DisplayName("a write replaces the tenant's one document by upsert, omitting unset fields")
    void writeUpsertsTheWholeDocument() {
        store.write("default", new StoredConnectionSettings(new ConnectionSettings(false, "https://eddi.example.com", null, null),
                Instant.parse("2026-09-14T10:00:00Z"), "alice"));

        var document = ArgumentCaptor.forClass(Document.class);
        var options = ArgumentCaptor.forClass(ReplaceOptions.class);
        verify(collection).replaceOne(any(Bson.class), document.capture(), options.capture());
        assertTrue(options.getValue().isUpsert());
        assertEquals(Set.of("_id", "enabled", "publicBaseUrl", "updatedAt", "updatedBy"), document.getValue().keySet());
        assertEquals("default", document.getValue().get("_id"));
        assertEquals(false, document.getValue().get("enabled"));
    }
}
