/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.documents.index;

import ai.labs.eddi.configs.rag.IRagStore;
import ai.labs.eddi.configs.rag.model.RagConfiguration;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.documents.DocumentSettings;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code eddi.documents.index.default-rag} cannot be refused at save time, so a
 * deployment whose default names no eligible document index does not start.
 */
@DisplayName("DocumentIndexDefaultCheck — the default index is validated at startup")
class DocumentIndexDefaultCheckTest {

    private static final String INDEX_ID = "65f0aa11bb22cc33dd44ee77";

    private DocumentSettings settings;
    private IRagStore ragStore;
    private DocumentIndexDefaultCheck check;

    @BeforeEach
    void setUp() throws Exception {
        settings = mock(DocumentSettings.class);
        ragStore = mock(IRagStore.class);
        IResourceStore.IResourceId current = mock(IResourceStore.IResourceId.class);
        when(current.getVersion()).thenReturn(3);
        when(ragStore.getCurrentResourceId(INDEX_ID)).thenReturn(current);
        check = new DocumentIndexDefaultCheck(settings, ragStore);
    }

    private static RagConfiguration rag(Boolean documentIndex) {
        var config = new RagConfiguration();
        config.setName("documents");
        config.setStoreType("pgvector");
        config.setStoreNamespace("id");
        config.setDocumentIndex(documentIndex);
        return config;
    }

    @Test
    @DisplayName("unset: nothing is read")
    void unsetReadsNothing() throws Exception {
        when(settings.indexDefaultRag()).thenReturn(Optional.empty());
        assertDoesNotThrow(check::check);
        verify(ragStore, never()).read(anyString(), any());
    }

    @Test
    void eligibleDefaultStarts() throws Exception {
        when(settings.indexDefaultRag()).thenReturn(Optional.of(INDEX_ID));
        when(ragStore.read(INDEX_ID, 3)).thenReturn(rag(true));
        assertDoesNotThrow(check::check);
    }

    @Test
    @DisplayName("naming an ordinary knowledge base fails startup, with the property and the reason")
    void ordinaryKnowledgeBaseFailsStartup() throws Exception {
        when(settings.indexDefaultRag()).thenReturn(Optional.of("eddi://ai.labs.rag/ragstore/rags/" + INDEX_ID + "?version=3"));
        when(ragStore.read(INDEX_ID, 3)).thenReturn(rag(null));
        var e = assertThrows(IllegalStateException.class, check::check);
        assertTrue(e.getMessage().startsWith(DocumentSettings.INDEX_DEFAULT_RAG + " must name a document index"), e.getMessage());
    }

    @Test
    void missingConfigurationFailsStartup() throws Exception {
        when(settings.indexDefaultRag()).thenReturn(Optional.of(INDEX_ID));
        when(ragStore.read(INDEX_ID, 3)).thenThrow(new IResourceStore.ResourceNotFoundException("gone"));
        var e = assertThrows(IllegalStateException.class, check::check);
        assertTrue(e.getMessage().contains("does not exist"), e.getMessage());
    }

    @Test
    void malformedReferenceFailsStartup() {
        when(settings.indexDefaultRag()).thenReturn(Optional.of("knowledge-base-name"));
        assertThrows(IllegalStateException.class, check::check);
    }
}
