/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.llm.mongo;

import ai.labs.eddi.datastore.IResourceStorage;
import ai.labs.eddi.datastore.IResourceStorageFactory;
import ai.labs.eddi.datastore.serialization.IDocumentBuilder;
import ai.labs.eddi.modules.llm.model.DocumentsConfig;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * A {@code documents} key that is not available in this version cannot be
 * stored — through create or update (a JSON patch ends in update;
 * RestLlmStoreDocumentsPatchTest) — and never reaches the storage; a
 * configuration without the block is unaffected.
 */
@DisplayName("LlmStore — documents validation")
class LlmStoreDocumentsValidationTest {

    private static final String ID = "5f1c0f3a9d8e4b2a7c6d1e0f";
    private static final String NOT_LIVE = "langchain.task[0].documents.sharing.delegation is not available in this version";

    private LlmStore store;
    private IResourceStorage<LlmConfiguration> storage;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        storage = mock(IResourceStorage.class);
        IResourceStorageFactory factory = mock(IResourceStorageFactory.class);
        when(factory.<LlmConfiguration>create(anyString(), any(), any(Class.class))).thenReturn(storage);
        store = new LlmStore(factory, mock(IDocumentBuilder.class));
    }

    private static LlmConfiguration withDelegation() {
        var sharing = new DocumentsConfig.Sharing();
        sharing.setDelegation(true);
        var documents = new DocumentsConfig();
        documents.setSharing(sharing);
        var task = new LlmConfiguration.Task();
        task.setId("t1");
        task.setDocuments(documents);
        return new LlmConfiguration(List.of(task));
    }

    @Test
    @DisplayName("create refuses a key that is not available")
    void createRefuses() {
        var e = assertThrows(IllegalArgumentException.class, () -> store.create(withDelegation()));
        assertEquals(NOT_LIVE, e.getMessage());
        verifyNoInteractions(storage);
    }

    @Test
    @DisplayName("update refuses a key that is not available")
    void updateRefuses() {
        var e = assertThrows(IllegalArgumentException.class, () -> store.update(ID, 1, withDelegation()));
        assertEquals(NOT_LIVE, e.getMessage());
        verifyNoInteractions(storage);
    }

    @Test
    @DisplayName("an empty documents block saves")
    @SuppressWarnings("unchecked")
    void emptyBlockSaves() throws Exception {
        var task = new LlmConfiguration.Task();
        task.setId("t1");
        task.setDocuments(new DocumentsConfig());
        var config = new LlmConfiguration(List.of(task));
        IResourceStorage.IResource<LlmConfiguration> resource = mock(IResourceStorage.IResource.class);
        when(storage.newResource(config)).thenReturn(resource);

        assertSame(resource, store.create(config));
        verify(storage).store(resource);
    }
}
