/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.rag.model.RagConfiguration;
import ai.labs.eddi.configs.rag.rest.KnowledgeBaseStorageGuard;
import ai.labs.eddi.configs.variables.GlobalVariableResolver;
import ai.labs.eddi.modules.ingestion.IIngestionStateStore;
import ai.labs.eddi.modules.ingestion.IngestionPipeline;
import ai.labs.eddi.modules.ingestion.KnowledgeBaseReembedService;
import ai.labs.eddi.modules.ingestion.RagSourceIngestionService;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.request.EmbeddingInputType;
import dev.langchain4j.model.output.Response;
import ai.labs.eddi.modules.rag.VectorSpaceGuard;
import ai.labs.eddi.modules.rag.VectorSpaceMismatchException;
import ai.labs.eddi.secrets.SecretResolver;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingStore;
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
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The vector space and the pgvector table, against a real pgvector server:
 * reads create nothing, an empty table is dropped under a lock, a shared table
 * is not, a changed size on a shared table is refused up front, two knowledge
 * bases on one table hold different models without refusing each other, and the
 * table's real column size is what a vector is checked against.
 * <p>
 * Needs Docker; CI runs it (it is an {@code *IT}, outside the unit-test run).
 */
@Testcontainers
@DisplayName("vector space and pgvector tables (Testcontainers)")
class PgVectorVectorSpaceIT {

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

    private static Connection admin() throws SQLException {
        return DriverManager.getConnection(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
    }

    private static RagConfiguration kb(String name, String model, int dimension, String table) {
        var config = new RagConfiguration();
        config.setName(name);
        config.setStoreType("pgvector");
        config.setStoreNamespace("id");
        config.setEmbeddingProvider("openai");
        config.setEmbeddingParameters(Map.of("model", model));
        Map<String, String> params = new HashMap<>(Map.of("host", PG.getHost(), "port", String.valueOf(PG.getMappedPort(5432)), "database",
                "eddi_vectors", "user", "test", "password", "test", "dimension", String.valueOf(dimension)));
        if (table != null) {
            params.put("table", table);
        }
        config.setStoreParameters(params);
        return config;
    }

    private static boolean tableExists(String table) throws SQLException {
        try (Connection c = admin();
                Statement s = c.createStatement();
                ResultSet rs = s.executeQuery("SELECT to_regclass('" + table + "') IS NOT NULL")) {
            rs.next();
            return rs.getBoolean(1);
        }
    }

    private static void addRow(EmbeddingStore<TextSegment> store, String kbId, float... vector) {
        store.add(Embedding.from(vector), TextSegment.from("text of " + kbId, new Metadata(new HashMap<>(Map.of("kbId", kbId)))));
    }

    private static float[] ones(int dimension) {
        float[] vector = new float[dimension];
        Arrays.fill(vector, 1f);
        return vector;
    }

    private static int count(EmbeddingStore<TextSegment> store, int dimension) {
        return store.search(EmbeddingSearchRequest.builder().queryEmbedding(Embedding.from(ones(dimension)))
                .maxResults(100).minScore(0.0).build()).matches().size();
    }

    @Test
    @DisplayName("a read does not create an absent table, and the first ingestion does")
    void readCreatesNothing() throws Exception {
        String id = "65f0aa11bb22cc33dd44ee01";
        String table = "eddi_kbid_" + id;
        RagConfiguration config = kb("reads", "m", 3, null);

        EmbeddingStore<TextSegment> read = factory.getOrCreate(id, config);

        assertEquals(0, count(read, 3));
        assertFalse(tableExists(table), "a search must not run CREATE TABLE");
        assertFalse(factory.inspectPgVectorTable(id, config, id).exists());

        EmbeddingStore<TextSegment> write = factory.getOrCreateForIngestion(id, config);
        addRow(write, id, 1f, 2f, 3f);
        assertTrue(tableExists(table));
        assertEquals(1, count(factory.getOrCreate(id, config), 3));
    }

    @Test
    @DisplayName("an empty table is dropped, and the next write recreates it for the new size")
    void emptyTableIsDroppedAndRecreated() throws Exception {
        String id = "65f0aa11bb22cc33dd44ee02";
        String table = "eddi_kbid_" + id;
        RagConfiguration three = kb("drop", "m", 3, null);
        addRow(factory.getOrCreateForIngestion(id, three), id, 1f, 2f, 3f);
        assertEquals(3, factory.inspectPgVectorTable(id, three, id).columnDimension());

        var outcome = factory.removeKnowledgeBaseVectors(id, three);

        assertEquals(EmbeddingStoreFactory.RemovalOutcome.TABLE_DROPPED, outcome);
        assertFalse(tableExists(table));
        // Nothing is cached any more, and a read of the absent table is empty.
        assertEquals(0, count(factory.getOrCreate(id, three), 3));

        RagConfiguration five = kb("drop", "m", 5, null);
        addRow(factory.getOrCreateForIngestion(id, five), id, 1f, 2f, 3f, 4f, 5f);
        assertEquals(5, factory.inspectPgVectorTable(id, five, id).columnDimension());
    }

    @Test
    @DisplayName("a table that still holds another knowledge base's rows keeps them, and is not dropped")
    void sharedTableIsNotDropped() throws Exception {
        String a = "65f0aa11bb22cc33dd44ee03";
        String b = "65f0aa11bb22cc33dd44ee04";
        RagConfiguration configA = kb("a", "m", 3, "shared_two_kbs");
        RagConfiguration configB = kb("b", "m", 3, "shared_two_kbs");
        addRow(factory.getOrCreateForIngestion(a, configA), a, 1f, 2f, 3f);
        addRow(factory.getOrCreateForIngestion(b, configB), b, 1f, 2f, 3f);

        var outcome = factory.removeKnowledgeBaseVectors(a, configA);

        assertEquals(EmbeddingStoreFactory.RemovalOutcome.TABLE_KEPT_SHARED, outcome);
        assertTrue(tableExists("shared_two_kbs"));
        assertEquals(1, count(factory.getOrCreate(b, configB), 3), "only A's chunks went");
        assertTrue(factory.inspectPgVectorTable(a, configA, a).otherRows());
    }

    @Test
    @DisplayName("a re-embed that changes the vector size of a table other knowledge bases keep rows in is refused before anything changes")
    void sizeChangeOnASharedTableIsRefused() throws Exception {
        String a = "65f0aa11bb22cc33dd44ee05";
        String b = "65f0aa11bb22cc33dd44ee06";
        addRow(factory.getOrCreateForIngestion(a, kb("a", "m", 3, "shared_resize")), a, 1f, 2f, 3f);
        addRow(factory.getOrCreateForIngestion(b, kb("b", "m", 3, "shared_resize")), b, 1f, 2f, 3f);
        IngestionPipeline pipeline = mock(IngestionPipeline.class);
        IIngestionStateStore stateStore = mock(IIngestionStateStore.class);
        KnowledgeBaseStorageGuard storageGuard = mock(KnowledgeBaseStorageGuard.class);
        when(storageGuard.sharersOf(anyString(), any(), any())).thenReturn(List.of(new KnowledgeBaseStorageGuard.Sharer(b, b)));
        // The new settings embed to 5 dimensions: the probe measures the size every
        // later check uses.
        EmbeddingModel model = mock(EmbeddingModel.class);
        when(model.embed(anyString())).thenReturn(Response.from(Embedding.from(ones(5))));
        EmbeddingModelFactory models = mock(EmbeddingModelFactory.class);
        when(models.getOrCreate(any(RagConfiguration.class), any(EmbeddingInputType.class))).thenReturn(model);
        var service = new KnowledgeBaseReembedService(pipeline, mock(RagSourceIngestionService.class), stateStore, factory,
                mock(VectorSpaceGuard.class), storageGuard, models);

        // A is now configured for another size.
        var refused = assertThrows(KnowledgeBaseReembedService.ReembedRefusedException.class,
                () -> service.reembed(a, kb("a", "m", 5, "shared_resize")));

        assertEquals(KnowledgeBaseReembedService.CODE_SHARED_TABLE_SIZE, refused.code());
        assertEquals(2, rowCount("shared_resize"), "nothing was removed");
    }

    @Test
    @DisplayName("two knowledge bases on one table hold different models of the same size without refusing each other")
    void differentModelsOnOneTable() throws Exception {
        String a = "65f0aa11bb22cc33dd44ee07";
        String b = "65f0aa11bb22cc33dd44ee08";
        VectorSpaceGuard guard = guard();
        RagConfiguration configA = kb("a", "model-x", 3, "shared_models");
        RagConfiguration configB = kb("b", "model-y", 3, "shared_models");
        addRow(factory.getOrCreateForIngestion(a, configA), a, 1f, 2f, 3f);

        guard.requireCompatible(a, configA);
        guard.verifyDimension(a, configA, 3, true);
        guard.requireCompatible(b, configB);
        guard.verifyDimension(b, configB, 3, true);

        assertDoesNotThrow(() -> guard.requireCompatible(a, configA));
        assertDoesNotThrow(() -> guard.requireCompatible(b, configB));
        // But a model change of A alone is A's to re-embed.
        assertThrows(VectorSpaceMismatchException.class, () -> guard.requireCompatible(a, kb("a", "model-z", 3, "shared_models")));
        assertDoesNotThrow(() -> guard.requireCompatible(b, configB));
    }

    @Test
    @DisplayName("the table's real column size, not the configured one, is what a vector is checked against")
    void realColumnSizeIsDetected() throws Exception {
        String id = "65f0aa11bb22cc33dd44ee09";
        // The table was made for 3 by an earlier configuration…
        addRow(factory.getOrCreateForIngestion(id, kb("real", "m", 3, "legacy_three")), id, 1f, 2f, 3f);
        VectorSpaceGuard guard = guard();
        // …and the knowledge base is now configured for 5 with a model that returns 5.
        RagConfiguration now = kb("real", "m", 5, "legacy_three");

        var refused = assertThrows(VectorSpaceMismatchException.class, () -> guard.verifyDimension(id, now, 5, true));

        assertTrue(refused.getMessage().contains("was made for 3"), refused.getMessage());
        assertDoesNotThrow(() -> guard.verifyDimension(id, now, 3, true));
    }

    @Test
    @DisplayName("a table that is not an EDDI pgvector table is never read, written, deleted from or dropped")
    void foreignTableIsLeftAlone() throws Exception {
        try (Connection c = admin(); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE foreign_things (id int primary key, note text)");
            s.execute("INSERT INTO foreign_things VALUES (1, 'keep me')");
        }
        String id = "65f0aa11bb22cc33dd44ee10";
        RagConfiguration config = kb("foreign", "m", 3, "foreign_things");

        assertThrows(IllegalArgumentException.class, () -> factory.removeKnowledgeBaseVectors(id, config));
        assertThrows(IllegalArgumentException.class, () -> factory.getOrCreateForIngestion(id, config));

        assertTrue(tableExists("foreign_things"));
        assertEquals(1, rowCount("foreign_things"));
        assertNotNull(factory.inspectPgVectorTable(id, config, id));
        assertFalse(factory.inspectPgVectorTable(id, config, id).layoutMatches());
        assertNull(factory.inspectPgVectorTable(id, config, id).columnDimension());
    }

    @Test
    @DisplayName("a node whose cached store points at a table another node dropped reads it as empty, not as an error")
    void staleCachedStoreReadsAsEmpty() throws Exception {
        String id = "65f0aa11bb22cc33dd44ee11";
        RagConfiguration config = kb("stale", "m", 3, null);
        addRow(factory.getOrCreateForIngestion(id, config), id, 1f, 2f, 3f);
        // The other node: its own factory, so its own cache — it has the store, and the
        // first node drops the table.
        EmbeddingStoreFactory otherNode = newFactory();
        EmbeddingStore<TextSegment> cachedThere = otherNode.getOrCreateForIngestion(id, config);
        assertEquals(1, count(cachedThere, 3));
        assertEquals(EmbeddingStoreFactory.RemovalOutcome.TABLE_DROPPED, factory.removeKnowledgeBaseVectors(id, config));
        assertFalse(tableExists("eddi_kbid_" + id));

        assertEquals(0, count(cachedThere, 3), "42P01 reads as an absent table");
        otherNode.clearCache();
    }

    @Test
    @DisplayName("a table that is in use is not dropped, and the outcome says it was busy")
    void busyTableIsNotDropped() throws Exception {
        String id = "65f0aa11bb22cc33dd44ee12";
        RagConfiguration config = kb("busy", "m", 3, null);
        addRow(factory.getOrCreateForIngestion(id, config), id, 1f, 2f, 3f);

        try (Connection reader = admin(); Statement s = reader.createStatement()) {
            reader.setAutoCommit(false);
            // An open transaction holding a read lock: the drop cannot take its exclusive
            // one.
            s.execute("SELECT count(*) FROM eddi_kbid_" + id);
            var outcome = factory.removeKnowledgeBaseVectors(id, config);

            assertEquals(EmbeddingStoreFactory.RemovalOutcome.TABLE_BUSY, outcome);
            assertTrue(tableExists("eddi_kbid_" + id));
            reader.rollback();
        }
        // Idle now, and empty (the chunks were deleted): the next attempt drops it.
        assertEquals(EmbeddingStoreFactory.RemovalOutcome.TABLE_DROPPED, factory.removeKnowledgeBaseVectors(id, config));
    }

    @Test
    @DisplayName("a table that has to change size is dropped whole while it holds only this knowledge base's rows")
    void ownTableIsDroppedWhole() throws Exception {
        String id = "65f0aa11bb22cc33dd44ee14";
        RagConfiguration config = kb("whole", "m", 3, null);
        addRow(factory.getOrCreateForIngestion(id, config), id, 1f, 2f, 3f);
        addRow(factory.getOrCreateForIngestion(id, config), id, 3f, 2f, 1f);

        assertEquals(EmbeddingStoreFactory.RemovalOutcome.TABLE_DROPPED, factory.dropKnowledgeBaseTable(id, config));

        assertFalse(tableExists("eddi_kbid_" + id));
        assertNull(factory.pgVectorColumnDimension(id, config), "nothing is remembered about the dropped table");
    }

    @Test
    @DisplayName("a table that has to change size but holds another knowledge base's rows is not touched at all")
    void sharedTableIsNotDroppedWhole() throws Exception {
        String a = "65f0aa11bb22cc33dd44ee15";
        String b = "65f0aa11bb22cc33dd44ee16";
        RagConfiguration configA = kb("a", "m", 3, "shared_whole");
        addRow(factory.getOrCreateForIngestion(a, configA), a, 1f, 2f, 3f);
        addRow(factory.getOrCreateForIngestion(b, kb("b", "m", 3, "shared_whole")), b, 1f, 2f, 3f);

        assertEquals(EmbeddingStoreFactory.RemovalOutcome.TABLE_KEPT_SHARED, factory.dropKnowledgeBaseTable(a, configA));

        assertEquals(2, rowCount("shared_whole"), "neither knowledge base lost a row");
    }

    @Test
    @DisplayName("a table that has to change size and stays busy past the lock timeout is not touched at all")
    void busyTableIsNotDroppedWhole() throws Exception {
        String id = "65f0aa11bb22cc33dd44ee17";
        RagConfiguration config = kb("busy-whole", "m", 3, null);
        addRow(factory.getOrCreateForIngestion(id, config), id, 1f, 2f, 3f);

        try (Connection reader = admin(); Statement s = reader.createStatement()) {
            reader.setAutoCommit(false);
            // Held for longer than the drop waits: the lock times out (55P03).
            s.execute("SELECT count(*) FROM eddi_kbid_" + id);
            assertEquals(EmbeddingStoreFactory.RemovalOutcome.TABLE_BUSY, factory.dropKnowledgeBaseTable(id, config));
            reader.rollback();
        }

        assertEquals(1, rowCount("eddi_kbid_" + id), "the busy table keeps its rows");
        assertEquals(EmbeddingStoreFactory.RemovalOutcome.TABLE_DROPPED, factory.dropKnowledgeBaseTable(id, config));
    }

    @Test
    @DisplayName("the column size is read from the catalogue and remembered per store: asking again costs no query")
    void columnSizeIsRememberedPerStore() throws Exception {
        String id = "65f0aa11bb22cc33dd44ee13";
        RagConfiguration config = kb("remembered", "m", 3, null);
        addRow(factory.getOrCreateForIngestion(id, config), id, 1f, 2f, 3f);

        assertEquals(3, factory.pgVectorColumnDimension(id, config));
        // A table of another size is not seen until the store is rebuilt — the cache is
        // by store, not per call.
        try (Connection c = admin(); Statement s = c.createStatement()) {
            s.execute("ALTER TABLE eddi_kbid_" + id + " RENAME TO moved_away_" + id);
        }
        assertEquals(3, factory.pgVectorColumnDimension(id, config));
        try (Connection c = admin(); Statement s = c.createStatement()) {
            s.execute("ALTER TABLE moved_away_" + id + " RENAME TO eddi_kbid_" + id);
        }
    }

    private static EmbeddingStoreFactory newFactory() {
        GlobalVariableResolver variables = mock(GlobalVariableResolver.class);
        SecretResolver secrets = mock(SecretResolver.class);
        when(variables.resolveAll(any())).thenAnswer(inv -> inv.getArgument(0));
        when(secrets.resolveSecrets(any())).thenAnswer(inv -> inv.getArgument(0));
        return new EmbeddingStoreFactory(variables, secrets);
    }

    private static int rowCount(String table) throws SQLException {
        try (Connection c = admin(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT count(*) FROM " + table)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    /** A guard over a map-backed record store and the real factory. */
    private static VectorSpaceGuard guard() {
        Map<String, String> records = new HashMap<>();
        IIngestionStateStore stateStore = mock(IIngestionStateStore.class);
        when(stateStore.storeFingerprint(anyString())).thenAnswer(call -> Optional.ofNullable(records.get(call.<String>getArgument(0))));
        doAnswer(call -> {
            records.put(call.getArgument(0), call.getArgument(1));
            return null;
        }).when(stateStore).recordStoreFingerprint(anyString(), anyString());
        GlobalVariableResolver variables = mock(GlobalVariableResolver.class);
        when(variables.resolveAll(any())).thenAnswer(inv -> inv.getArgument(0));
        VectorSpaceGuard guard = new VectorSpaceGuard(stateStore, variables);
        guard.useStoreFactory(factory);
        return guard;
    }
}
