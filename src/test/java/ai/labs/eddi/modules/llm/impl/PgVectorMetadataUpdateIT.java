/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.rag.model.RagConfiguration;
import ai.labs.eddi.configs.variables.GlobalVariableResolver;
import ai.labs.eddi.secrets.SecretResolver;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link SegmentMetadataUpdates} on a real pgvector server, in both table
 * layouts: the earlier {@code json} metadata column and the current
 * {@code jsonb} one. The matching rows get the new title and URL, their vectors
 * and other keys stay, and other documents are untouched.
 */
@Testcontainers
@DisplayName("pgvector metadata update (Testcontainers)")
class PgVectorMetadataUpdateIT {

    @Container
    static final PostgreSQLContainer PG = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("eddi_vectors")
            .withUsername("test")
            .withPassword("test");

    private static EmbeddingStoreFactory factory;

    @BeforeAll
    static void setUp() {
        GlobalVariableResolver variables = mock(GlobalVariableResolver.class);
        SecretResolver secrets = mock(SecretResolver.class);
        when(variables.resolveAll(any())).thenAnswer(inv -> inv.getArgument(0));
        when(secrets.resolveSecrets(any())).thenAnswer(inv -> inv.getArgument(0));
        factory = new EmbeddingStoreFactory(variables, secrets);
    }

    @AfterAll
    static void tearDown() {
        if (factory != null) {
            factory.clearCache();
        }
    }

    private static RagConfiguration kb(String layout) {
        var config = new RagConfiguration();
        config.setName("metadata-" + layout);
        config.setStoreType("pgvector");
        config.setStoreNamespace("id");
        config.setStoreParameters(new HashMap<>(Map.of("host", PG.getHost(), "port",
                String.valueOf(PG.getMappedPort(5432)), "database", "eddi_vectors", "user", "test", "password",
                "test", "dimension", "3", "layout", layout)));
        return config;
    }

    private static void chunk(EmbeddingStore<TextSegment> store, String documentId, String title, float[] vector) {
        var metadata = new Metadata(new HashMap<>(Map.of("documentId", documentId, "sourceKey", "kb:src",
                "title", title, "url", "https://old/" + documentId, "runId", "run-1")));
        store.add(Embedding.from(vector), TextSegment.from("text of " + documentId + " " + title, metadata));
    }

    @ParameterizedTest(name = "layout {0}, metadata column {1}")
    @CsvSource({"v1, json", "v2, jsonb"})
    @DisplayName("rewrites the matching chunks' title and URL in place")
    void updatesInPlace(String layout, String columnType) throws SQLException {
        String id = "v1".equals(layout) ? "65f0aa11bb22cc33dd44ef01" : "65f0aa11bb22cc33dd44ef02";
        EmbeddingStore<TextSegment> store = factory.getOrCreate(id, kb(layout));
        var updates = assertInstanceOf(SegmentMetadataUpdates.class, store);
        assertEquals(columnType, metadataType("eddi_kbid_" + id));
        chunk(store, "doc-a", "Old", new float[]{1f, 0f, 0f});
        chunk(store, "doc-a", "Old", new float[]{0f, 1f, 0f});
        chunk(store, "doc-b", "Other", new float[]{0f, 0f, 1f});

        var values = new HashMap<String, String>();
        values.put("title", "New 'quoted' title");
        values.put("url", "https://new/doc-a");
        int changed = updates.updateMetadata(Map.of("sourceKey", "kb:src", "documentId", "doc-a"), values);

        assertEquals(2, changed);
        List<EmbeddingMatch<TextSegment>> matches = store.search(EmbeddingSearchRequest.builder()
                .queryEmbedding(Embedding.from(new float[]{1f, 0f, 0f})).maxResults(10).minScore(0.0).build())
                .matches();
        assertEquals(3, matches.size(), "no row added or removed");
        EmbeddingMatch<TextSegment> best = matches.getFirst();
        assertEquals("doc-a", best.embedded().metadata().getString("documentId"));
        assertTrue(best.score() > 0.99, "the vector is the one stored, not a new one: " + best.score());
        for (EmbeddingMatch<TextSegment> match : matches) {
            Metadata metadata = match.embedded().metadata();
            if ("doc-a".equals(metadata.getString("documentId"))) {
                assertEquals("New 'quoted' title", metadata.getString("title"));
                assertEquals("https://new/doc-a", metadata.getString("url"));
                assertEquals("run-1", metadata.getString("runId"));
            } else {
                assertEquals("Other", metadata.getString("title"));
                assertEquals("https://old/doc-b", metadata.getString("url"));
            }
        }
        assertEquals(0, updates.updateMetadata(Map.of("documentId", "doc-none"), Map.of("title", "x")));

        // A null value removes the key, and the row still reads back through a search.
        var removal = new HashMap<String, String>();
        removal.put("url", null);
        assertEquals(1, updates.updateMetadata(Map.of("documentId", "doc-b"), removal));
        Metadata docB = store.search(EmbeddingSearchRequest.builder()
                .queryEmbedding(Embedding.from(new float[]{0f, 0f, 1f})).maxResults(1).minScore(0.0).build())
                .matches().getFirst().embedded().metadata();
        assertEquals("doc-b", docB.getString("documentId"));
        assertTrue(!docB.containsKey("url"), "the key is gone, not null");
        assertEquals("Other", docB.getString("title"));
    }

    private static Connection admin() throws SQLException {
        return DriverManager.getConnection(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
    }

    private static String metadataType(String table) throws SQLException {
        try (Connection c = admin();
                var ps = c.prepareStatement(
                        "SELECT format_type(atttypid, NULL) FROM pg_attribute WHERE attrelid = to_regclass(?) AND attname = 'metadata'")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "table " + table + " has a metadata column");
                return rs.getString(1);
            }
        }
    }
}
