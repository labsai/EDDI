/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.postgres;

import ai.labs.eddi.datastore.IResourceFilter;
import ai.labs.eddi.datastore.serialization.JsonSerialization;
import ai.labs.eddi.datastore.serialization.SerializationCustomizer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code pg_trgm} indexes for substring search, against a real PostgreSQL:
 * what they are, that a search planned for its value uses them, that an index
 * left invalid is rebuilt, and that another builder's lock is respected.
 */
@DisplayName("PostgreSQL substring-search indexes")
class PostgresSubstringSearchIndexesTest extends PostgresTestBase {

    private static final String COLLECTION = PostgresSubstringSearchIndexes.DESCRIPTORS;
    private static final List<PostgresSubstringSearchIndexes.Index> INDEXES = List.of(
            PostgresSubstringSearchIndexes.Index.NAME, PostgresSubstringSearchIndexes.Index.USER_ID);

    private DataSource dataSource;
    private PostgresResourceStorage<Map<String, Object>> storage;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        dataSource = createDataSource();
        // The real schema, as the storage creates it.
        storage = new PostgresResourceStorage<>(dataSource, COLLECTION,
                new JsonSerialization(SerializationCustomizer.configureObjectMapper(new ObjectMapper(), false)),
                (Class<Map<String, Object>>) (Class<?>) Map.class);
        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            for (var index : PostgresSubstringSearchIndexes.Index.values()) {
                stmt.execute("DROP INDEX IF EXISTS " + index.indexName());
            }
            stmt.execute("DELETE FROM resources WHERE collection_name = '" + COLLECTION + "'");
        }
    }

    private boolean ready(PostgresSubstringSearchIndexes indexes) throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            return indexes.isReady(conn);
        }
    }

    private PostgresSubstringSearchIndexes indexes() {
        return new PostgresSubstringSearchIndexes(dataSource, INDEXES);
    }

    private String indexDefinition(String name) throws SQLException {
        try (Connection conn = dataSource.getConnection();
                Statement stmt = conn.createStatement();
                ResultSet rs = stmt.executeQuery("SELECT indexdef FROM pg_indexes WHERE indexname = '" + name + "'")) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    @Test
    @DisplayName("builds one partial GIN trigram index per field, valid, and reports ready")
    void buildsPartialTrigramIndexes() throws Exception {
        var indexes = indexes();

        indexes.build();

        assertTrue(ready(indexes));
        for (var index : INDEXES) {
            String definition = indexDefinition(index.indexName());
            assertTrue(definition.contains("USING gin") && definition.contains("gin_trgm_ops")
                    && definition.contains("'" + index.field + "'") && definition.contains("collection_name = '" + COLLECTION + "'"),
                    definition);
        }
    }

    @Test
    @DisplayName("a substring search planned for its value uses the trigram index")
    void plannedSearchUsesTheIndex() throws Exception {
        for (int i = 0; i < 3000; i++) {
            var resource = storage.newResource(Map.<String, Object>of("name", "agent number " + i, "userId", "user-" + i));
            storage.store(resource);
        }
        var indexes = indexes();
        indexes.build();
        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("ANALYZE resources");
            StringBuilder plan = new StringBuilder();
            try (ResultSet rs = stmt.executeQuery("EXPLAIN SELECT id FROM resources WHERE collection_name = '" + COLLECTION
                    + "' AND data ->> 'name' LIKE '%number 1234%' ESCAPE '\\'")) {
                while (rs.next()) {
                    plan.append(rs.getString(1)).append('\n');
                }
            }
            assertTrue(plan.toString().contains(PostgresSubstringSearchIndexes.Index.NAME.indexName()), plan.toString());
        }

        // And the search finds exactly what it found without the index.
        Set<String> found = new HashSet<>();
        storage.findResources(new IResourceFilter.QueryFilters[]{new IResourceFilter.QueryFilters(
                List.of(new IResourceFilter.QueryFilter("name", new IResourceFilter.Contains("number 1234"))))}, null, 0, 50)
                .forEach(id -> found.add(id.getId()));
        assertEquals(1, found.size());
    }

    @Test
    @DisplayName("an index left invalid by an interrupted build is rebuilt, not skipped")
    void invalidIndexIsRebuilt() throws Exception {
        var indexes = indexes();
        indexes.build();
        String name = PostgresSubstringSearchIndexes.Index.NAME.indexName();
        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            // What CREATE INDEX CONCURRENTLY leaves behind when it is interrupted.
            stmt.execute("UPDATE pg_index SET indisvalid = false WHERE indexrelid = '" + name + "'::regclass");
        }
        var afterRestart = indexes();
        assertFalse(ready(afterRestart), "an invalid index must not count as ready");

        afterRestart.build();

        assertTrue(ready(afterRestart));
        try (Connection conn = dataSource.getConnection();
                Statement stmt = conn.createStatement();
                ResultSet rs = stmt.executeQuery("SELECT indisvalid FROM pg_index WHERE indexrelid = '" + name + "'::regclass")) {
            assertTrue(rs.next() && rs.getBoolean(1));
        }
    }

    @Test
    @DisplayName("another instance holding the build lock: this one builds nothing")
    void respectsAnotherBuildersLock() throws Exception {
        try (Connection other = dataSource.getConnection(); Statement stmt = other.createStatement()) {
            stmt.execute("SELECT pg_advisory_lock(" + 0x65646469_7472676DL + ")");
            try {
                var indexes = indexes();
                indexes.build();

                assertFalse(ready(indexes));
                assertEquals(null, indexDefinition(PostgresSubstringSearchIndexes.Index.NAME.indexName()));
            } finally {
                stmt.execute("SELECT pg_advisory_unlock(" + 0x65646469_7472676DL + ")");
            }
        }
    }
}
