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
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The v2 pgvector layout against a real pgvector server: the indexes exist, the
 * statements langchain4j actually sends use them, and a table in the earlier
 * layout keeps working untouched.
 * <p>
 * The plans are those of the store's own statements, captured by
 * {@code auto_explain} in the server log — not of hand-written SQL that may
 * drift from what langchain4j's filter mapper emits. {@code enable_seqscan} is
 * off for the test role, so an index that the predicate cannot use shows up as
 * a sequential scan instead of being out-costed on a small table.
 */
@Testcontainers
@DisplayName("pgvector v2 layout (Testcontainers)")
class PgVectorScaleLayoutIT {

    private static final String KB_ID = "65f0aa11bb22cc33dd44ee77";
    private static final String TABLE = "eddi_kbid_" + KB_ID;
    private static final int DOCUMENTS = 300;
    private static final int CHUNKS_PER_DOCUMENT = 10;

    @Container
    static final PostgreSQLContainer PG = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("eddi_vectors")
            .withUsername("test")
            .withPassword("test");

    private static EmbeddingStoreFactory factory;

    @BeforeAll
    static void setUp() throws SQLException {
        try (Connection c = admin(); Statement s = c.createStatement()) {
            // Applied to every session opened from now on — the store's pooled ones too.
            s.execute("ALTER ROLE test SET session_preload_libraries = 'auto_explain'");
            s.execute("ALTER ROLE test SET auto_explain.log_min_duration = 0");
            s.execute("ALTER ROLE test SET auto_explain.log_format = 'text'");
            s.execute("ALTER ROLE test SET enable_seqscan = off");
        }
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

    private static Connection admin() throws SQLException {
        return DriverManager.getConnection(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
    }

    private static RagConfiguration kb(String namespace, String name, Map<String, String> extra) {
        var config = new RagConfiguration();
        config.setName(name);
        config.setStoreType("pgvector");
        config.setStoreNamespace(namespace);
        Map<String, String> params = new HashMap<>(Map.of("host", PG.getHost(), "port", String.valueOf(PG.getMappedPort(5432)), "database",
                "eddi_vectors", "user", "test", "password", "test", "dimension", "3"));
        params.putAll(extra);
        config.setStoreParameters(params);
        return config;
    }

    @Test
    @DisplayName("a new knowledge base gets a jsonb table with HNSW and metadata indexes, and its queries use them")
    void newTableIsIndexedAndUsed() throws Exception {
        EmbeddingStore<TextSegment> store = factory.getOrCreate(KB_ID, kb("id", "scaled", Map.of("hnswEfSearch", "77", "insertBatchSize", "500")));
        var eddiStore = assertInstanceOf(EddiPgVectorEmbeddingStore.class, store);

        assertEquals("jsonb", metadataType(TABLE));
        Map<String, String> indexes = indexes(TABLE);
        assertTrue(indexes.get(TABLE + "_hnsw_idx").contains("USING hnsw (embedding vector_cosine_ops) WITH (m='16', ef_construction='64')"),
                indexes.toString());
        assertTrue(indexes.get(TABLE + "_doc_src_idx").contains("((metadata ->> 'documentId'::text)), ((metadata ->> 'sourceKey'::text))"),
                indexes.toString());
        assertTrue(indexes.get(TABLE + "_src_run_idx").contains("((metadata ->> 'sourceKey'::text)), ((metadata ->> 'runId'::text))"),
                indexes.toString());

        // ef_search is applied to every connection the store borrows.
        try (Connection c = eddiStore.getConnection(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SHOW hnsw.ef_search")) {
            assertTrue(rs.next());
            assertEquals("77", rs.getString(1));
        }

        // A few thousand chunks, written in one call — split into 500-row batches.
        List<String> ids = new ArrayList<>();
        List<Embedding> embeddings = new ArrayList<>();
        List<TextSegment> segments = new ArrayList<>();
        for (int d = 0; d < DOCUMENTS; d++) {
            for (int c = 0; c < CHUNKS_PER_DOCUMENT; c++) {
                ids.add(UUID.randomUUID().toString());
                embeddings.add(Embedding.from(new float[]{1f, d / 10f, c + 1f}));
                segments.add(TextSegment.from("doc " + d + " chunk " + c, new Metadata(new HashMap<>(Map.of("documentId", "doc-" + d,
                        "sourceKey", "src-" + (d % 3), "runId", "run-1", "kbId", KB_ID, "url", "https://example.com/" + d)))));
            }
        }
        store.addAll(ids, embeddings, segments);
        assertEquals(DOCUMENTS * CHUNKS_PER_DOCUMENT, count(TABLE));
        try (Connection c = admin(); Statement s = c.createStatement()) {
            s.execute("ANALYZE " + TABLE);
        }

        // Replacement of one document's chunks, exactly as ingestion issues it.
        store.removeAll(metadataKey("documentId").isEqualTo("doc-7").and(metadataKey("sourceKey").isEqualTo("src-1")));
        assertEquals(DOCUMENTS * CHUNKS_PER_DOCUMENT - CHUNKS_PER_DOCUMENT, count(TABLE));
        String deletePlan = planOf("DELETE FROM " + TABLE + " WHERE");
        assertTrue(deletePlan.contains(TABLE + "_doc_src_idx"), deletePlan);
        assertFalse(deletePlan.contains("Seq Scan"), deletePlan);

        // A source's chunks that a run did not write — the sweep after a run.
        store.removeAll(metadataKey("sourceKey").isEqualTo("src-2").and(metadataKey("runId").isNotIn(List.of("run-1"))));
        String sweepPlan = planOf("DELETE FROM " + TABLE + " WHERE (metadata->>'sourceKey')");
        assertTrue(sweepPlan.contains(TABLE + "_src_run_idx"), sweepPlan);
        assertFalse(sweepPlan.contains("Seq Scan"), sweepPlan);

        // Retrieval orders by cosine distance through the HNSW index, with the kbId
        // filter.
        var result = store.search(EmbeddingSearchRequest.builder().queryEmbedding(Embedding.from(new float[]{1f, 0.5f, 3f})).maxResults(5)
                .filter(EmbeddingStoreFactory.retrievalFilter(KB_ID, kb("id", "scaled", Map.of()))).build());
        assertEquals(5, result.matches().size());
        assertTrue(result.matches().get(0).embedded().metadata().getString("url").startsWith("https://example.com/"),
                "metadata other than the indexed keys survives the jsonb round trip");
        String searchPlan = planOf("ORDER BY embedding <=>");
        assertTrue(searchPlan.contains(TABLE + "_hnsw_idx"), searchPlan);
    }

    @Test
    @DisplayName("a table in the earlier layout keeps working untouched, and is never converted")
    void legacyTableKeepsWorking() throws Exception {
        String legacyTable = "eddi_kb_legacy";
        // What every EDDI release up to 6.7 created: langchain4j's host/port builder,
        // json metadata.
        PgVectorEmbeddingStore old = PgVectorEmbeddingStore.builder().host(PG.getHost()).port(PG.getMappedPort(5432)).database("eddi_vectors")
                .user("test").password("test").table(legacyTable).dimension(3).createTable(true).build();
        old.add(Embedding.from(new float[]{0f, 1f, 0f}), TextSegment.from("written before the upgrade",
                new Metadata(new HashMap<>(Map.of("documentId", "old-doc", "sourceKey", "old-src", "kbId", "legacy")))));
        Set<String> indexesBefore = new HashSet<>(indexes(legacyTable).keySet());

        EmbeddingStore<TextSegment> store = factory.getOrCreate("65f0aa11bb22cc33dd44ee88", kb(null, "legacy", Map.of()));

        assertEquals("json", metadataType(legacyTable));
        assertEquals(indexesBefore, indexes(legacyTable).keySet(), "no index is added to an existing table");

        var found = store.search(EmbeddingSearchRequest.builder().queryEmbedding(Embedding.from(new float[]{0f, 1f, 0f})).maxResults(3).build());
        assertEquals(1, found.matches().size());
        assertEquals("written before the upgrade", found.matches().get(0).embedded().text());

        store.add(Embedding.from(new float[]{0f, 0f, 1f}), TextSegment.from("written after",
                new Metadata(new HashMap<>(Map.of("documentId", "new-doc", "sourceKey", "old-src", "kbId", "legacy")))));
        assertEquals(2, count(legacyTable));
        store.removeAll(metadataKey("documentId").isEqualTo("old-doc").and(metadataKey("sourceKey").isEqualTo("old-src")));
        assertEquals(1, count(legacyTable));

        var refused = assertThrows(IllegalArgumentException.class,
                () -> factory.getOrCreate("65f0aa11bb22cc33dd44ee88", kb(null, "legacy", Map.of("layout", "v2"))));
        assertTrue(refused.getMessage().contains("never converted"), refused.getMessage());
        assertEquals("json", metadataType(legacyTable));
    }

    @Test
    @DisplayName("layout v1 still creates a new table the old way")
    void explicitV1() throws Exception {
        String id = "65f0aa11bb22cc33dd44ee99";
        factory.getOrCreate(id, kb("id", "v1", Map.of("layout", "v1")));
        String table = "eddi_kbid_" + id;
        assertEquals("json", metadataType(table));
        assertEquals(Set.of(table + "_pkey"), indexes(table).keySet());
    }

    @Test
    @DisplayName("knowledge bases on one server share one connection pool")
    void poolIsShared() {
        factory.clearCache();
        factory.getOrCreate("65f0aa11bb22cc33dd44eeaa", kb("id", "a", Map.of()));
        factory.getOrCreate("65f0aa11bb22cc33dd44eebb", kb("id", "b", Map.of()));
        assertEquals(1, factory.pgVectorPoolCount());
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

    private static Map<String, String> indexes(String table) throws SQLException {
        Map<String, String> result = new HashMap<>();
        try (Connection c = admin(); var ps = c.prepareStatement("SELECT indexname, indexdef FROM pg_indexes WHERE tablename = ?")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.put(rs.getString(1), rs.getString(2));
                }
            }
        }
        return result;
    }

    private static long count(String table) throws SQLException {
        try (Connection c = admin(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT count(*) FROM " + table)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    /**
     * The last plan {@code auto_explain} logged for a statement containing
     * {@code marker}. The server log is read until the plan shows up: it is written
     * asynchronously to the container's output.
     */
    private static String planOf(String marker) throws InterruptedException {
        for (int attempt = 0; attempt < 50; attempt++) {
            String logs = PG.getLogs();
            int at = logs.lastIndexOf(marker);
            if (at >= 0) {
                int start = logs.lastIndexOf("plan:", at);
                int end = logs.indexOf("LOG:", at);
                String plan = logs.substring(Math.max(0, start), end < 0 ? logs.length() : end);
                if (plan.contains("->") || plan.contains("Scan")) {
                    return plan;
                }
            }
            Thread.sleep(100);
        }
        throw new AssertionError("auto_explain logged no plan for a statement containing: " + marker);
    }
}
