/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.rag;

import ai.labs.eddi.configs.rag.model.RagConfiguration;
import ai.labs.eddi.configs.rag.model.VectorSpace;
import ai.labs.eddi.configs.variables.GlobalVariableResolver;
import ai.labs.eddi.modules.ingestion.IIngestionStateStore;
import ai.labs.eddi.modules.llm.impl.EmbeddingStoreFactory;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("VectorSpaceGuard")
class VectorSpaceGuardTest {

    private static final String ID = "kb1";

    private IIngestionStateStore stateStore;
    private GlobalVariableResolver variables;
    private Map<String, String> records;
    private VectorSpaceGuard guard;
    private SimpleMeterRegistry meters;

    @BeforeEach
    void setUp() {
        stateStore = mock(IIngestionStateStore.class);
        records = new HashMap<>();
        when(stateStore.storeFingerprint(anyString())).thenAnswer(call -> Optional.ofNullable(records.get(call.<String>getArgument(0))));
        doAnswer(call -> {
            records.put(call.getArgument(0), call.getArgument(1));
            return null;
        }).when(stateStore).recordStoreFingerprint(anyString(), anyString());
        variables = mock(GlobalVariableResolver.class);
        when(variables.resolveAll(any())).thenAnswer(call -> call.getArgument(0));
        guard = new VectorSpaceGuard(stateStore, variables);
        meters = new SimpleMeterRegistry();
        guard.meterRegistry = meters;
    }

    private static RagConfiguration pgvector(String model, Map<String, String> extraStore) {
        RagConfiguration config = new RagConfiguration();
        config.setStoreNamespace("id");
        config.setStoreType("pgvector");
        config.setEmbeddingProvider("openai");
        config.setEmbeddingParameters(Map.of("model", model));
        Map<String, String> store = new HashMap<>(Map.of("host", "secret-host.internal", "password", "x"));
        store.putAll(extraStore);
        config.setStoreParameters(store);
        return config;
    }

    private static RagConfiguration qdrant(String model) {
        RagConfiguration config = new RagConfiguration();
        config.setStoreNamespace("id");
        config.setStoreType("qdrant");
        config.setEmbeddingProvider("openai");
        config.setEmbeddingParameters(Map.of("model", model));
        config.setStoreParameters(Map.of("host", "qdrant.internal", "collectionName", "shared"));
        return config;
    }

    private long modelRecords() {
        return records.keySet().stream().filter(key -> key.startsWith(VectorSpaceGuard.MODEL_PREFIX)).count();
    }

    @Test
    @DisplayName("a knowledge base with no record adopts the configured space on first use, and keeps working")
    void adoptsOnFirstUse() {
        RagConfiguration config = pgvector("emb-8", Map.of());

        guard.requireCompatible(ID, config);

        assertEquals(1, records.size());
        assertTrue(records.keySet().iterator().next().startsWith(VectorSpaceGuard.MODEL_PREFIX));
        guard.requireCompatible(ID, config);
        assertEquals(1, records.size());
    }

    @Test
    @DisplayName("a changed model is refused, naming the stored and the configured space and the way out")
    void refusesAChangedModel() {
        guard.requireCompatible(ID, pgvector("emb-8", Map.of()));

        VectorSpaceMismatchException refused = assertThrows(VectorSpaceMismatchException.class,
                () -> guard.requireCompatible(ID, pgvector("other-8", Map.of())));

        assertTrue(refused.getMessage().contains("emb-8"), refused.getMessage());
        assertTrue(refused.getMessage().contains("other-8"), refused.getMessage());
        assertTrue(refused.getMessage().contains("/ragstore/rags/kb1/reembed"), refused.getMessage());
        assertFalse(refused.getMessage().contains("secret-host"), "no connection detail in the message");
    }

    @Test
    @DisplayName("two knowledge bases on one table hold different models without refusing each other")
    void differentModelsOnOneTableDoNotRefuseEachOther() {
        RagConfiguration a = pgvector("emb-8", Map.of("table", "shared"));
        RagConfiguration b = pgvector("other-8", Map.of("table", "shared"));

        guard.requireCompatible("kbA", a);
        guard.requireCompatible("kbB", b);

        // Each has its own record, and each is still accepted.
        assertEquals(2, modelRecords());
        assertDoesNotThrow(() -> guard.requireCompatible("kbA", a));
        assertDoesNotThrow(() -> guard.requireCompatible("kbB", b));
    }

    @Test
    @DisplayName("one knowledge base's adoption never makes another one be told to re-embed")
    void adoptionIsPerKnowledgeBase() {
        guard.requireCompatible("kbA", qdrant("emb-8"));
        guard.requireCompatible("kbB", qdrant("other-8"));

        assertTrue(guard.status("kbA", qdrant("emb-8")).matches());
        assertTrue(guard.status("kbB", qdrant("other-8")).matches());
    }

    @Test
    @DisplayName("re-embedding one knowledge base leaves the other's record alone")
    void resetIsPerKnowledgeBase() {
        guard.requireCompatible("kbA", qdrant("emb-8"));
        guard.requireCompatible("kbB", qdrant("other-8"));

        guard.reset("kbA", qdrant("third-8"), false);

        assertTrue(guard.status("kbA", qdrant("third-8")).matches());
        assertTrue(guard.status("kbB", qdrant("other-8")).matches());
    }

    @Test
    @DisplayName("the vector size belongs to the location: another model of another size is refused on a shared collection")
    void sizeIsSharedByTheLocation() {
        guard.requireCompatible("kbA", qdrant("emb-8"));
        guard.verifyDimension("kbA", qdrant("emb-8"), 8, true);
        guard.requireCompatible("kbB", qdrant("other-8"));

        assertDoesNotThrow(() -> guard.verifyDimension("kbB", qdrant("other-8"), 8, true));
        var refused = assertThrows(VectorSpaceMismatchException.class, () -> guard.verifyDimension("kbB", qdrant("other-8"), 16, true));
        assertTrue(refused.getMessage().contains("vectors of 8"), refused.getMessage());
        assertTrue(refused.getMessage().contains("/reembed"), refused.getMessage());
    }

    @Test
    @DisplayName("an in-memory knowledge base is not tracked")
    void inMemoryIsNotTracked() {
        RagConfiguration config = new RagConfiguration();
        config.setStoreType("in-memory");

        guard.requireCompatible(ID, config);

        assertTrue(records.isEmpty());
        assertFalse(VectorSpaceGuard.applies(ID, config));
        assertFalse(guard.status(ID, config).applicable());
    }

    @Test
    @DisplayName("the first embedding's size is recorded; a query is checked but never writes it")
    void dimensionIsRecordedByIngestionOnly() {
        RagConfiguration config = qdrant("emb-8");
        guard.requireCompatible(ID, config);

        guard.verifyDimension(ID, config, 8, false);
        assertNull(guard.status(ID, config).stored().dimension());

        guard.verifyDimension(ID, config, 8, true);
        assertEquals(8, guard.status(ID, config).stored().dimension());
    }

    @Test
    @DisplayName("a pgvector table made for another size than configured is refused with the parameter to change, not a driver error")
    void pgvectorDeclaredSize() {
        RagConfiguration config = pgvector("emb-16", Map.of("dimension", "8"));

        VectorSpaceMismatchException refused = assertThrows(VectorSpaceMismatchException.class,
                () -> guard.verifyDimension(ID, config, 16, true));

        assertTrue(refused.getMessage().contains("storeParameters.dimension"), refused.getMessage());
        assertTrue(refused.getMessage().contains("16") && refused.getMessage().contains("8"), refused.getMessage());
    }

    @Test
    @DisplayName("the table's real column size wins over the configured one")
    void pgvectorRealColumnSize() {
        EmbeddingStoreFactory factory = mock(EmbeddingStoreFactory.class);
        when(factory.inspectPgVectorTable(anyString(), any(), anyString()))
                .thenReturn(new EmbeddingStoreFactory.PgVectorTableInfo(true, true, 1536, false));
        guard.storeFactory = factory;
        // Configured 3072; the legacy table was made for 1536.
        RagConfiguration config = pgvector("big", Map.of("dimension", "3072"));

        var refused = assertThrows(VectorSpaceMismatchException.class, () -> guard.verifyDimension(ID, config, 3072, true));
        assertTrue(refused.getMessage().contains("was made for 1536"), refused.getMessage());
        // A model that fits the real column passes, even though the setting says
        // otherwise.
        assertDoesNotThrow(() -> guard.verifyDimension(ID, config, 1536, true));
    }

    @Test
    @DisplayName("pgvector's default table size, 1536, is checked when none is configured and no table could be read")
    void pgvectorDefaultSize() {
        RagConfiguration config = pgvector("small", Map.of());

        assertThrows(VectorSpaceMismatchException.class, () -> guard.verifyDimension(ID, config, 768, true));
        guard.verifyDimension(ID, config, 1536, true);
    }

    @Test
    @DisplayName("reset records the configured space with its size unknown, so a new model can be written")
    void resetAcceptsTheNewSpace() {
        guard.requireCompatible(ID, pgvector("emb-8", Map.of()));
        RagConfiguration after = pgvector("other-16", Map.of());
        assertThrows(VectorSpaceMismatchException.class, () -> guard.requireCompatible(ID, after));

        guard.reset(ID, after, true);

        guard.requireCompatible(ID, after);
        VectorSpace stored = guard.status(ID, after).stored();
        assertEquals("other-16", stored.model());
        assertNull(stored.dimension());
    }

    @Test
    @DisplayName("an unresolvable global variable refuses the call and records nothing — retryable")
    void unresolvedVariablesAreNeverAdoptedOrReset() {
        RagConfiguration config = qdrant("${vars:model}");
        // doThrow/doAnswer: re-stubbing with when() would call the previous stub.
        doThrow(new IllegalStateException("variable store down")).when(variables).resolveAll(any());

        assertThrows(OperatorFailureException.class, () -> guard.requireCompatible(ID, config));
        assertThrows(OperatorFailureException.class, () -> guard.reset(ID, config, true));
        assertTrue(records.isEmpty());

        // A reference that comes back unresolved is the same.
        doAnswer(call -> call.getArgument(0)).when(variables).resolveAll(any());
        assertThrows(OperatorFailureException.class, () -> guard.requireCompatible(ID, config));
        assertTrue(records.isEmpty());

        // Resolved, it works.
        doAnswer(call -> Map.of("model", "emb-8")).when(variables).resolveAll(any());
        assertDoesNotThrow(() -> guard.requireCompatible(ID, config));
        assertEquals(1, records.size());
    }

    @Test
    @DisplayName("while a knowledge base is being re-embedded, search and ingest wait")
    void maintenanceRefuses() {
        RagConfiguration config = qdrant("emb-8");
        guard.requireCompatible(ID, config);

        guard.beginMaintenance(ID);
        var refused = assertThrows(VectorSpaceMismatchException.class, () -> guard.requireCompatible(ID, config));
        assertTrue(refused.getMessage().contains("being re-embedded"), refused.getMessage());

        guard.endMaintenance(ID);
        assertDoesNotThrow(() -> guard.requireCompatible(ID, config));
    }

    @Test
    @DisplayName("every refusal is counted by reason, with no knowledge base in the tags")
    void refusalsAreCounted() {
        guard.requireCompatible(ID, qdrant("emb-8"));
        assertThrows(VectorSpaceMismatchException.class, () -> guard.requireCompatible(ID, qdrant("other-8")));
        guard.verifyDimension(ID, qdrant("emb-8"), 8, true);
        assertThrows(VectorSpaceMismatchException.class, () -> guard.verifyDimension(ID, qdrant("emb-8"), 9, true));

        assertEquals(1.0, meters.counter(VectorSpaceGuard.REFUSED_METRIC, "reason", "space").count());
        assertEquals(1.0, meters.counter(VectorSpaceGuard.REFUSED_METRIC, "reason", "dimension").count());
        assertTrue(meters.getMeters().stream().allMatch(meter -> meter.getId().getTags().stream().noneMatch(tag -> tag.getValue().contains(ID))));
    }

    @Test
    @DisplayName("an unreadable record lets the call through instead of taking retrieval down")
    void failsOpenWhenTheRecordCannotBeRead() {
        when(stateStore.storeFingerprint(anyString())).thenThrow(new IllegalStateException("db down"));

        guard.requireCompatible(ID, pgvector("emb-8", Map.of()));

        verify(stateStore, never()).recordStoreFingerprint(anyString(), anyString());
    }

    @Test
    @DisplayName("the status reports both spaces and whether they match")
    void status() {
        guard.requireCompatible(ID, pgvector("emb-8", Map.of()));

        VectorSpaceGuard.Status same = guard.status(ID, pgvector("emb-8", Map.of()));
        assertTrue(same.applicable() && same.matches());

        VectorSpaceGuard.Status changed = guard.status(ID, pgvector("other-8", Map.of()));
        assertFalse(changed.matches());
        assertEquals("emb-8", changed.stored().model());
        assertEquals("other-8", changed.configured().model());
    }

    @Test
    @DisplayName("the records' keys are digests: no connection detail appears in them")
    void keysAreDigests() {
        RagConfiguration config = pgvector("emb-8", Map.of("password", "hunter2"));
        guard.requireCompatible(ID, config);
        guard.verifyDimension(ID, config, 1536, true);

        for (String key : records.keySet()) {
            assertFalse(key.contains("hunter2") || key.contains("secret-host"), key);
            assertTrue(key.matches("vector-space:(model|size):[0-9a-f]{40}"), key);
        }
        assertEquals(2, records.size());
    }

    @Test
    @DisplayName("a refusal is logged without raising, however often it repeats")
    void logRefusalIsSafeToRepeat() {
        for (int i = 0; i < 3; i++) {
            assertDoesNotThrow(() -> guard.logRefusal(ID, "docs", new VectorSpaceMismatchException("refused")));
        }
    }
}
