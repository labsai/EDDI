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

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    @Test
    @DisplayName("retrieval is filtered by id only in the id layout")
    void retrievalFilter() {
        assertNotNull(EmbeddingStoreFactory.retrievalFilter(ALPHA_ID, kb("alpha", "id")));
        assertNull(EmbeddingStoreFactory.retrievalFilter(ALPHA_ID, kb("alpha", null)));
    }
}
