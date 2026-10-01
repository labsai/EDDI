/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.mongo;

import ai.labs.eddi.datastore.IResourceFilter;
import ai.labs.eddi.datastore.IResourceStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link IResourceFilter.QueryFilter#exact(String, String)} against a real
 * MongoDB: the origin-id lookup of a merge import relies on it selecting the
 * value itself and nothing else.
 */
@DisplayName("MongoResourceStorage exact-match filter (Testcontainers)")
class MongoResourceStorageExactMatchTest extends MongoTestBase {

    private static final String COLLECTION = "exact_match_resources";

    private MongoResourceStorage<Map<String, Object>> storage;
    private String plainId;
    private String newlineId;

    @BeforeEach
    void setUp() throws IOException {
        dropCollections(COLLECTION, COLLECTION + ".history");
        @SuppressWarnings("unchecked")
        Class<Map<String, Object>> type = (Class<Map<String, Object>>) (Class<?>) Map.class;
        storage = new MongoResourceStorage<>(getDatabase(), COLLECTION, documentBuilder, type);

        var plain = storage.newResource(Map.of("originId", "name"));
        storage.store(plain);
        plainId = plain.getId();
        var withNewline = storage.newResource(Map.of("originId", "name\n"));
        storage.store(withNewline);
        newlineId = withNewline.getId();
    }

    @Test
    @DisplayName("an exact filter selects the value itself, not the value followed by a newline")
    void exactFilterIgnoresATrailingNewlineVariant() {
        assertEquals(List.of(plainId), idsMatching(IResourceFilter.QueryFilter.exact("originId", "name")));
        assertEquals(List.of(newlineId), idsMatching(IResourceFilter.QueryFilter.exact("originId", "name\n")));
    }

    @Test
    @DisplayName("why a pattern is not enough: an escaped ^...$ regex also selects the trailing-newline variant")
    void anchoredRegexAlsoMatchesBeforeAFinalNewline() {
        // Documents the server behaviour the exact filter exists for; if MongoDB ever
        // stops matching $ before a final newline, this is the test that says so.
        List<String> ids = idsMatching(new IResourceFilter.QueryFilter("originId", "^name$"));

        assertEquals(2, ids.size(), "matched: " + ids);
    }

    private List<String> idsMatching(IResourceFilter.QueryFilter filter) {
        return storage.findResources(new IResourceFilter.QueryFilters[]{new IResourceFilter.QueryFilters(List.of(filter))}, null, 0, 10)
                .stream().map(IResourceStore.IResourceId::getId).sorted().toList();
    }
}
