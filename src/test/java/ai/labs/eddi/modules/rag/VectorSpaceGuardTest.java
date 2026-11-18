/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.rag;

import ai.labs.eddi.configs.rag.model.RagConfiguration;
import ai.labs.eddi.configs.rag.model.VectorSpace;
import ai.labs.eddi.configs.variables.GlobalVariableResolver;
import ai.labs.eddi.modules.ingestion.IIngestionStateStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.ArgumentMatchers.any;

@DisplayName("VectorSpaceGuard")
class VectorSpaceGuardTest {

    private static final String ID = "kb1";

    private IIngestionStateStore stateStore;
    private Map<String, String> records;
    private VectorSpaceGuard guard;

    @BeforeEach
    void setUp() {
        stateStore = mock(IIngestionStateStore.class);
        records = new HashMap<>();
        when(stateStore.storeFingerprint(anyString())).thenAnswer(call -> Optional.ofNullable(records.get(call.<String>getArgument(0))));
        doAnswer(call -> {
            records.put(call.getArgument(0), call.getArgument(1));
            return null;
        }).when(stateStore).recordStoreFingerprint(anyString(), anyString());
        GlobalVariableResolver variables = mock(GlobalVariableResolver.class);
        when(variables.resolveAll(any())).thenAnswer(call -> call.getArgument(0));
        guard = new VectorSpaceGuard(stateStore, variables);
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
        config.setStoreParameters(Map.of("host", "qdrant.internal"));
        return config;
    }

    @Test
    @DisplayName("a knowledge base with no record adopts the configured space on first use, and keeps working")
    void adoptsOnFirstUse() {
        RagConfiguration config = pgvector("emb-8", Map.of());

        guard.requireCompatible(ID, config);

        assertEquals(1, records.size());
        assertTrue(records.keySet().iterator().next().startsWith(VectorSpaceGuard.KEY_PREFIX));
        // And again: the record is now what is compared.
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
    }

    @Test
    @DisplayName("the refusal carries no connection detail")
    void refusalIsClientSafe() {
        guard.requireCompatible(ID, pgvector("emb-8", Map.of()));
        String message = assertThrows(VectorSpaceMismatchException.class,
                () -> guard.requireCompatible(ID, pgvector("other-8", Map.of()))).getMessage();
        assertFalse(message.contains("secret-host"), "the host is not part of the message: " + message);
    }

    @Test
    @DisplayName("two knowledge bases on one table share one record; another table has its own")
    void keyedByLocation() {
        guard.requireCompatible("kbA", pgvector("emb-8", Map.of("table", "shared")));
        assertThrows(VectorSpaceMismatchException.class, () -> guard.requireCompatible("kbB", pgvector("other-8", Map.of("table", "shared"))));

        guard.requireCompatible("kbB", pgvector("other-8", Map.of("table", "elsewhere")));
        assertEquals(2, records.size());
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
    @DisplayName("the first embedding's size is recorded; a later embedding of another size is refused")
    void dimensionIsRecordedThenChecked() {
        RagConfiguration config = qdrant("emb-8");
        guard.requireCompatible(ID, config);

        guard.verifyDimension(ID, config, 8, true);
        assertEquals(8, guard.status(ID, config).stored().dimension());

        // The same model name, vectors of another size: a changed "dimensions" setting
        // the configuration does not state.
        VectorSpaceMismatchException refused = assertThrows(VectorSpaceMismatchException.class,
                () -> guard.verifyDimension(ID, config, 16, false));
        assertTrue(refused.getMessage().contains("16"), refused.getMessage());
        assertTrue(refused.getMessage().contains("8 dimensions"), refused.getMessage());
        assertTrue(refused.getMessage().contains("/reembed"), refused.getMessage());
    }

    @Test
    @DisplayName("a query is checked but never writes the size down")
    void aQueryDoesNotRecord() {
        RagConfiguration config = qdrant("emb-8");
        guard.requireCompatible(ID, config);

        guard.verifyDimension(ID, config, 8, false);

        assertNull(guard.status(ID, config).stored().dimension());
    }

    @Test
    @DisplayName("a pgvector table made for another size is refused with the parameter to change, not a driver error")
    void pgvectorTableSize() {
        RagConfiguration config = pgvector("emb-16", Map.of("dimension", "8"));

        VectorSpaceMismatchException refused = assertThrows(VectorSpaceMismatchException.class,
                () -> guard.verifyDimension(ID, config, 16, true));

        assertTrue(refused.getMessage().contains("storeParameters.dimension"), refused.getMessage());
        assertTrue(refused.getMessage().contains("16") && refused.getMessage().contains("8"), refused.getMessage());
    }

    @Test
    @DisplayName("pgvector's default table size, 1536, is checked when none is configured")
    void pgvectorDefaultSize() {
        RagConfiguration config = pgvector("small", Map.of());

        assertThrows(VectorSpaceMismatchException.class, () -> guard.verifyDimension(ID, config, 768, true));
        guard.verifyDimension(ID, config, 1536, true);
    }

    @Test
    @DisplayName("reset records the configured space with its size unknown, so a new model can be written")
    void resetAcceptsTheNewSpace() {
        RagConfiguration before = pgvector("emb-8", Map.of());
        guard.requireCompatible(ID, before);
        RagConfiguration after = pgvector("other-16", Map.of());
        assertThrows(VectorSpaceMismatchException.class, () -> guard.requireCompatible(ID, after));

        guard.reset(ID, after);

        guard.requireCompatible(ID, after);
        VectorSpace stored = guard.status(ID, after).stored();
        assertEquals("other-16", stored.model());
        assertNull(stored.dimension());
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
    @DisplayName("the record's key is a digest: no connection detail appears in it")
    void keyIsADigest() {
        guard.requireCompatible(ID, pgvector("emb-8", Map.of("password", "hunter2")));

        String key = records.keySet().iterator().next();
        assertFalse(key.contains("hunter2"));
        assertTrue(key.matches(VectorSpaceGuard.KEY_PREFIX + "[0-9a-f]{40}"), key);
    }
}
