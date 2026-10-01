/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.mongo;

import ai.labs.eddi.datastore.IResourceFilter;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.security.spaces.Subjects;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link IResourceFilter.NotMatching} against a real MongoDB — the "Shared with
 * me" listing depends on it, and on a document lacking the field counting as
 * "not matching", which a mocked collection cannot show.
 */
@DisplayName("MongoResourceStorage NotMatching filter")
class MongoNotMatchingFilterTest extends MongoTestBase {

    private static final String COLLECTION = "not_matching_test";

    private MongoResourceStorage<Map<String, Object>> storage;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        dropCollections(COLLECTION, COLLECTION + ".history");
        storage = new MongoResourceStorage<>(getDatabase(), COLLECTION, documentBuilder, (Class<Map<String, Object>>) (Class<?>) Map.class);
    }

    @Test
    @DisplayName("excludes matching rows and keeps rows without the field")
    void excludesOnlyMatchingRows() throws IOException {
        String alice = store(Map.of("accessIndex", "|owner:alice|space:user:alice|"));
        String bob = store(Map.of("accessIndex", "|owner:bob|user:alice|"));
        String malice = store(Map.of("accessIndex", "|owner:malice|"));
        String unowned = store(new HashMap<>(Map.of("name", "legacy")));

        var filter = new IResourceFilter.QueryFilter("accessIndex",
                new IResourceFilter.NotMatching(Subjects.tokenPattern(Subjects.OWNER_TOKEN_PREFIX + "alice")));
        List<IResourceStore.IResourceId> found = storage.findResources(
                new IResourceFilter.QueryFilters[]{new IResourceFilter.QueryFilters(List.of(filter))}, null, 0, 50);

        Set<String> ids = new HashSet<>();
        found.forEach(id -> ids.add(id.getId()));
        assertEquals(Set.of(bob, malice, unowned), ids, "only alice's own row is excluded; " + alice + " must not appear");
    }

    private String store(Map<String, Object> content) throws IOException {
        var resource = storage.newResource(content);
        storage.store(resource);
        return resource.getId();
    }
}
