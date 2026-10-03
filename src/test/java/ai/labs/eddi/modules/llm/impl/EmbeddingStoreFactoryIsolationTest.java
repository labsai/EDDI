/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.rag.model.RagConfiguration;
import ai.labs.eddi.configs.variables.GlobalVariableResolver;
import ai.labs.eddi.secrets.SecretResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

@DisplayName("EmbeddingStoreFactory addresses stores by knowledge base id")
class EmbeddingStoreFactoryIsolationTest {

    private static final String ALPHA_ID = "65f0aa11bb22cc33dd44ee55";
    private static final String BETA_ID = "65f0aa11bb22cc33dd44ee66";

    private final EmbeddingStoreFactory factory = new EmbeddingStoreFactory(mock(GlobalVariableResolver.class), mock(SecretResolver.class));

    private static RagConfiguration kb(String name, String namespace) {
        var config = new RagConfiguration();
        config.setName(name);
        config.setStoreType("in-memory");
        config.setStoreNamespace(namespace);
        return config;
    }

    @Test
    @DisplayName("two knowledge bases with the same name get two stores — in either layout")
    void sameNameDifferentIds() {
        assertNotSame(factory.getOrCreate(ALPHA_ID, kb("same", "id")), factory.getOrCreate(BETA_ID, kb("same", "id")));
        assertNotSame(factory.getOrCreate(ALPHA_ID, kb("same", null)), factory.getOrCreate(BETA_ID, kb("same", null)));
    }

    @Test
    @DisplayName("versions of one knowledge base share its store")
    void sameIdSameStore() {
        assertSame(factory.getOrCreate(ALPHA_ID, kb("alpha", "id")), factory.getOrCreate(ALPHA_ID, kb("alpha", "id")));
    }

    @Test
    @DisplayName("switching layout is switching store")
    void layoutIsPartOfTheKey() {
        assertNotSame(factory.getOrCreate(ALPHA_ID, kb("alpha", null)), factory.getOrCreate(ALPHA_ID, kb("alpha", "id")));
    }

    @Test
    void noIdNoStore() {
        assertThrows(IllegalArgumentException.class, () -> factory.getOrCreate(null, kb("alpha", "id")));
        assertThrows(IllegalArgumentException.class, () -> factory.getOrCreate(" ", kb("alpha", null)));
    }

    private static RagConfiguration pg(String rawTable) {
        var config = kb("alpha", "id");
        config.setStoreType("pgvector");
        config.setStoreParameters(rawTable == null ? Map.of() : Map.of("table", rawTable));
        return config;
    }

    /**
     * PR #945 review: what a stored ${vars:...} table resolves to is checked when
     * the store is built, because a global variable can be changed by an editor
     * after the knowledge base was saved.
     */
    @Test
    @DisplayName("a pgvector table that resolves to anything but a plain identifier is refused before it reaches SQL")
    void resolvedTableMustBeAPlainIdentifier() {
        var config = pg("${vars:t}");
        var e = assertThrows(IllegalArgumentException.class,
                () -> EmbeddingStoreFactory.physicalName(Map.of("table", "t; DROP TABLE y --"), "table", ALPHA_ID, config));
        assertTrue(e.getMessage().contains("plain table name"), e.getMessage());
        assertEquals("team_docs", EmbeddingStoreFactory.physicalName(Map.of("table", "team_docs"), "table", ALPHA_ID, config));
    }

    @Test
    @DisplayName("a reference may not resolve onto another knowledge base's default store")
    void referenceMayNotResolveIntoTheReservedNamespace() {
        assertThrows(IllegalArgumentException.class,
                () -> EmbeddingStoreFactory.physicalName(Map.of("table", "eddi_kb_alpha"), "table", ALPHA_ID, pg("${vars:t}")));
        // A literal reserved table stored before the save-time rule keeps working,
        // and so do the defaults, which are reserved by construction.
        assertEquals("eddi_kb_alpha", EmbeddingStoreFactory.physicalName(Map.of("table", "eddi_kb_alpha"), "table", ALPHA_ID, pg("eddi_kb_alpha")));
        assertEquals("eddi_kbid_" + ALPHA_ID, EmbeddingStoreFactory.physicalName(Map.of(), "table", ALPHA_ID, pg(null)));
    }

    @Test
    @DisplayName("retrieval is filtered by id only in the id layout")
    void retrievalFilter() {
        assertNotNull(EmbeddingStoreFactory.retrievalFilter(ALPHA_ID, kb("alpha", "id")));
        assertNull(EmbeddingStoreFactory.retrievalFilter(ALPHA_ID, kb("alpha", null)));
    }
}
