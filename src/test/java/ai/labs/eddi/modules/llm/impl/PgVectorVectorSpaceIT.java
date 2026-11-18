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

        boolean dropped = factory.removeKnowledgeBaseVectors(id, three);

        assertTrue(dropped);
        assertFalse(tableExists(table));
        // A node whose cache still points at the dropped table reads it as empty.
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

        boolean dropped = factory.removeKnowledgeBaseVectors(a, configA);

        assertFalse(dropped);
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
        when(storageGuard.sharersOf(anyString(), any())).thenReturn(List.of(new KnowledgeBaseStorageGuard.Sharer(b, b)));
        var service = new KnowledgeBaseReembedService(pipeline, mock(RagSourceIngestionService.class), stateStore, factory,
                mock(VectorSpaceGuard.class), storageGuard);

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
