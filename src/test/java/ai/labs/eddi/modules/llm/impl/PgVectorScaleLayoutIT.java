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
    private static final String SCOPED_USER = "scoped";
    private static final String SCOPED_SCHEMA = "myschema";

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
            // Created here, as the owner, so the scoped role below never installs it
            // into its own schema.
            s.execute("CREATE EXTENSION IF NOT EXISTS vector");
            // A login whose current_schema() is myschema, with public behind it on the
            // search_path. Not a superuser, and its DDL is logged.
            s.execute("CREATE ROLE " + SCOPED_USER + " LOGIN PASSWORD '" + SCOPED_USER + "'");
            s.execute("CREATE SCHEMA " + SCOPED_SCHEMA + " AUTHORIZATION " + SCOPED_USER);
            s.execute("GRANT USAGE, CREATE ON SCHEMA public TO " + SCOPED_USER);
            s.execute("ALTER ROLE " + SCOPED_USER + " SET search_path = " + SCOPED_SCHEMA + ", public");
            s.execute("ALTER ROLE " + SCOPED_USER + " SET log_statement = 'ddl'");
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

    @Test
    @DisplayName("search_path myschema, public: an unqualified table is created in myschema as v2; public.docs keeps v1; a rebuild creates no index")
    void unqualifiedTableResolvesInCurrentSchema() throws Exception {
        legacyPublicTable("docs");
        Set<String> publicIndexes = new HashSet<>(indexes("public", "docs").keySet());
        String unqualifiedId = "65f0aa11bb22cc33dd44ef01";
        String qualifiedId = "65f0aa11bb22cc33dd44ef02";

        String firstBuild = ddlLoggedDuring(() -> {
            EmbeddingStore<TextSegment> store = factory.getOrCreate(unqualifiedId, scopedKb("docs", Map.of()));
            store.add(Embedding.from(new float[]{1f, 0f, 0f}), segment("written through docs"));
        });

        // langchain4j created myschema.docs, and its layout was decided for that table
        // —
        // not taken over from the old-layout public.docs further down the search_path.
        assertEquals("jsonb", metadataType(SCOPED_SCHEMA + ".docs"));
        assertEquals(Set.of("docs_pkey", "docs_hnsw_idx", "docs_doc_src_idx", "docs_src_run_idx"), indexes(SCOPED_SCHEMA, "docs").keySet());
        assertEquals(3, createIndexStatements(firstBuild), firstBuild);
        assertEquals(1, count(SCOPED_SCHEMA + ".docs"));
        assertEquals("json", metadataType("public.docs"));
        assertEquals(1, count("public.docs"));
        assertEquals(publicIndexes, indexes("public", "docs").keySet());

        String qualifiedBuild = ddlLoggedDuring(() -> {
            EmbeddingStore<TextSegment> store = factory.getOrCreate(qualifiedId, scopedKb("public.docs", Map.of()));
            var found = store.search(EmbeddingSearchRequest.builder().queryEmbedding(Embedding.from(new float[]{0f, 1f, 0f})).maxResults(3).build());
            assertEquals(1, found.matches().size());
            assertEquals("written before the upgrade", found.matches().get(0).embedded().text());
            store.add(Embedding.from(new float[]{0f, 0f, 1f}), segment("written through public.docs"));
        });

        // public.docs is used as it is: still v1, no new index, the row lands there.
        assertEquals("json", metadataType("public.docs"));
        assertEquals(publicIndexes, indexes("public", "docs").keySet(), "no index is added to an existing v1 table");
        assertEquals(0, createIndexStatements(qualifiedBuild), qualifiedBuild);
        assertEquals(2, count("public.docs"));
        assertEquals(1, count(SCOPED_SCHEMA + ".docs"));

        // A second build of both finds every index where it is and creates none.
        factory.clearCache();
        String rebuild = ddlLoggedDuring(() -> {
            factory.getOrCreate(unqualifiedId, scopedKb("docs", Map.of()));
            factory.getOrCreate(qualifiedId, scopedKb("public.docs", Map.of()));
        });
        assertEquals(0, createIndexStatements(rebuild), rebuild);
        assertEquals("json", metadataType("public.docs"));
    }

    @Test
    @DisplayName("search_path myschema, public: layout v2 on an unqualified table is not refused because of an old-layout public table")
    void explicitV2NotRefusedByPublicTable() throws Exception {
        legacyPublicTable("docs_v2");
        try (Connection c = scoped()) {
            assertFalse(PgVectorSchema.inspect(c, "docs_v2").exists(), "public.docs_v2 is not the table langchain4j would use");
            assertEquals("json", PgVectorSchema.inspect(c, "public.docs_v2").metadataType());
        }

        factory.getOrCreate("65f0aa11bb22cc33dd44ef03", scopedKb("docs_v2", Map.of("layout", "v2")));

        assertEquals("jsonb", metadataType(SCOPED_SCHEMA + ".docs_v2"));
        assertEquals(4, indexes(SCOPED_SCHEMA, "docs_v2").size());
        assertEquals("json", metadataType("public.docs_v2"));
    }

    @Test
    @DisplayName("search_path myschema, public: v2 tables of one name in both schemas each get, and keep, their own indexes")
    void indexesAreLookedUpInTheTablesSchema() throws Exception {
        String qualifiedId = "65f0aa11bb22cc33dd44ef04";
        String unqualifiedId = "65f0aa11bb22cc33dd44ef05";
        Set<String> expected = Set.of("shared_pkey", "shared_hnsw_idx", "shared_doc_src_idx", "shared_src_run_idx");

        // A table outside current_schema(), named with its schema.
        String qualifiedBuild = ddlLoggedDuring(() -> factory.getOrCreate(qualifiedId, scopedKb("public.shared", Map.of())));
        assertEquals("jsonb", metadataType("public.shared"));
        assertEquals(expected, indexes("public", "shared").keySet());
        assertEquals(3, createIndexStatements(qualifiedBuild), qualifiedBuild);

        // The same name unqualified is myschema.shared. public.shared's indexes are on
        // the search_path under the very names this table's indexes get, and must not
        // be mistaken for them.
        String unqualifiedBuild = ddlLoggedDuring(() -> factory.getOrCreate(unqualifiedId, scopedKb("shared", Map.of())));
        assertEquals("jsonb", metadataType(SCOPED_SCHEMA + ".shared"));
        assertEquals(expected, indexes(SCOPED_SCHEMA, "shared").keySet());
        assertEquals(3, createIndexStatements(unqualifiedBuild), unqualifiedBuild);

        try (Connection c = scoped()) {
            for (String suffix : List.of(PgVectorSchema.SUFFIX_HNSW, PgVectorSchema.SUFFIX_DOC_SOURCE, PgVectorSchema.SUFFIX_SOURCE_RUN)) {
                assertTrue(PgVectorSchema.indexExists(c, "shared", suffix), suffix);
                assertTrue(PgVectorSchema.indexExists(c, "public.shared", suffix), suffix);
            }
        }

        factory.clearCache();
        String rebuild = ddlLoggedDuring(() -> {
            factory.getOrCreate(qualifiedId, scopedKb("public.shared", Map.of()));
            factory.getOrCreate(unqualifiedId, scopedKb("shared", Map.of()));
        });
        assertEquals(0, createIndexStatements(rebuild), rebuild);
    }

    /** A knowledge base on {@code table} that logs in as the scoped role. */
    private static RagConfiguration scopedKb(String table, Map<String, String> extra) {
        Map<String, String> params = new HashMap<>(Map.of("user", SCOPED_USER, "password", SCOPED_USER, "table", table));
        params.putAll(extra);
        return kb("id", "search-path-" + table, params);
    }

    private static Connection scoped() throws SQLException {
        return DriverManager.getConnection(PG.getJdbcUrl(), SCOPED_USER, SCOPED_USER);
    }

    /**
     * {@code public.
     * <table>
     * } as every EDDI release up to 6.7 created it — json metadata, no index
     * besides the key — holding one row, and usable by the scoped role.
     */
    private static void legacyPublicTable(String table) throws SQLException {
        PgVectorEmbeddingStore old = PgVectorEmbeddingStore.builder().host(PG.getHost()).port(PG.getMappedPort(5432)).database("eddi_vectors")
                .user("test").password("test").table("public." + table).dimension(3).createTable(true).build();
        old.add(Embedding.from(new float[]{0f, 1f, 0f}), segment("written before the upgrade"));
        try (Connection c = admin(); Statement s = c.createStatement()) {
            s.execute("GRANT ALL ON public." + table + " TO " + SCOPED_USER);
        }
        assertEquals("json", metadataType("public." + table));
    }

    private static TextSegment segment(String text) {
        return TextSegment.from(text, new Metadata(new HashMap<>(Map.of("documentId", "doc", "sourceKey", "src"))));
    }

    @FunctionalInterface
    private interface Action {
        void run() throws Exception;
    }

    /**
     * The server log the scoped role's DDL wrote while {@code action} ran. Its DDL
     * is logged ({@code log_statement = ddl}); the window is bounded by two marker
     * statements of that role, and read once the closing one has reached the log —
     * so a statement missing from it was not sent, rather than not yet logged.
     */
    private static String ddlLoggedDuring(Action action) throws Exception {
        String start = marker();
        action.run();
        String end = marker();
        for (int attempt = 0; attempt < 100; attempt++) {
            String logs = PG.getLogs();
            int to = logs.indexOf(end);
            if (to >= 0) {
                int from = logs.indexOf(start);
                assertTrue(from >= 0 && from < to, "the opening marker is logged before the closing one");
                return logs.substring(from, to);
            }
            Thread.sleep(100);
        }
        throw new AssertionError("the server log never showed marker " + end);
    }

    private static String marker() throws SQLException {
        String marker = "eddi-ddl-marker-" + UUID.randomUUID();
        try (Connection c = scoped(); Statement s = c.createStatement()) {
            s.execute("COMMENT ON SCHEMA " + SCOPED_SCHEMA + " IS '" + marker + "'");
        }
        return marker;
    }

    private static int createIndexStatements(String log) {
        int count = 0;
        for (int at = log.indexOf("CREATE INDEX"); at >= 0; at = log.indexOf("CREATE INDEX", at + 1)) {
            count++;
        }
        return count;
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
        return indexes("public", table);
    }

    private static Map<String, String> indexes(String schema, String table) throws SQLException {
        Map<String, String> result = new HashMap<>();
        try (Connection c = admin();
                var ps = c.prepareStatement("SELECT indexname, indexdef FROM pg_indexes WHERE schemaname = ? AND tablename = ?")) {
            ps.setString(1, schema);
            ps.setString(2, table);
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
