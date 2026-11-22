/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.rag.rest;

import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.descriptors.model.AccessLevel;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.configs.rag.IRagStore;
import ai.labs.eddi.configs.rag.model.IngestionSource;
import ai.labs.eddi.configs.rag.model.RagConfiguration;
import ai.labs.eddi.configs.workflows.IWorkflowStore;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import jakarta.ws.rs.BadRequestException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The RAG side of the document-index separation: a flagged configuration must
 * be eligible, stays a document index once it is one, keeps its storage, is not
 * turned on while a workflow uses it, and shares its location with nobody.
 */
@DisplayName("KnowledgeBaseStorageGuard — document index")
class KnowledgeBaseStorageGuardDocumentIndexTest {

    private static final String SELF = "65f0aa11bb22cc33dd44ee55";
    private static final String OTHER = "65f0aa11bb22cc33dd44ee66";
    private static final String WORKFLOW = "65f0aa11bb22cc33dd44ee88";

    private IRagStore ragStore;
    private IDocumentDescriptorStore descriptorStore;
    private ResourceAccessGuard accessGuard;
    private IWorkflowStore workflowStore;
    private KnowledgeBaseStorageGuard guard;

    @BeforeEach
    void setUp() throws Exception {
        ragStore = mock(IRagStore.class);
        descriptorStore = mock(IDocumentDescriptorStore.class);
        accessGuard = mock(ResourceAccessGuard.class);
        workflowStore = mock(IWorkflowStore.class);
        when(descriptorStore.readDescriptors(eq("ai.labs.rag"), isNull(), anyInt(), anyInt(), anyBoolean())).thenReturn(List.of());
        when(accessGuard.hasAccess(anyString(), eq(AccessLevel.EDIT))).thenReturn(true);
        IResourceStore.IResourceId current = mock(IResourceStore.IResourceId.class);
        when(current.getVersion()).thenReturn(4);
        when(ragStore.getCurrentResourceId(SELF)).thenReturn(current);
        when(workflowStore.getWorkflowDescriptorsContainingResource(anyString(), anyBoolean())).thenReturn(List.of());
        guard = new KnowledgeBaseStorageGuard(ragStore, descriptorStore, accessGuard);
        guard.useWorkflowStore(() -> workflowStore);
    }

    private static RagConfiguration kb(Boolean documentIndex, Map<String, String> storeParameters) {
        var config = new RagConfiguration();
        config.setName("documents");
        config.setStoreType("pgvector");
        config.setStoreNamespace("id");
        config.setStoreParameters(storeParameters == null ? null : new HashMap<>(storeParameters));
        config.setDocumentIndex(documentIndex);
        return config;
    }

    private void otherKnowledgeBaseUsesTable(String table, Boolean otherIsIndex) throws Exception {
        var descriptor = new DocumentDescriptor();
        descriptor.setResource(URI.create("eddi://ai.labs.rag/ragstore/rags/" + OTHER + "?version=2"));
        when(descriptorStore.readDescriptors(eq("ai.labs.rag"), isNull(), eq(0), anyInt(), anyBoolean())).thenReturn(List.of(descriptor));
        when(ragStore.read(OTHER, 2)).thenReturn(kb(otherIsIndex, Map.of("table", table)));
    }

    @Nested
    @DisplayName("created")
    class Created {

        @Test
        void eligibleIndexIsAccepted() {
            assertDoesNotThrow(() -> guard.prepareNew(kb(true, null), true));
        }

        @Test
        void ineligibleIndexIsRefused() {
            var withSources = kb(true, null);
            var source = new IngestionSource();
            source.setName("crawler");
            withSources.setSources(List.of(source));
            var refused = assertThrows(BadRequestException.class, () -> guard.prepareNew(withSources, true));
            assertTrue(refused.getMessage().contains("documentIndex"), refused.getMessage());

            var atlasDefault = kb(true, null);
            atlasDefault.setStoreType("mongodb-atlas");
            assertThrows(BadRequestException.class, () -> guard.prepareNew(atlasDefault, false));
        }

        @Test
        @DisplayName("an index may not take a location another knowledge base uses, even one the caller may edit")
        void indexDoesNotShareALocation() throws Exception {
            otherKnowledgeBaseUsesTable("shared_chunks", null);
            assertThrows(BadRequestException.class, () -> guard.prepareNew(kb(true, Map.of("table", "shared_chunks")), true));
            assertDoesNotThrow(() -> guard.prepareNew(kb(null, Map.of("table", "shared_chunks")), true));
        }

        @Test
        @DisplayName("no knowledge base may take a location a document index uses")
        void nobodySharesAnIndexLocation() throws Exception {
            otherKnowledgeBaseUsesTable("index_chunks", true);
            assertThrows(BadRequestException.class, () -> guard.prepareNew(kb(null, Map.of("table", "index_chunks")), true));
        }
    }

    @Nested
    @DisplayName("updated")
    class Updated {

        @Test
        @DisplayName("documentIndex cannot be switched off")
        void cannotBeSwitchedOff() {
            assertThrows(BadRequestException.class, () -> guard.prepareUpdate(SELF, kb(true, null), kb(false, null)));
            assertThrows(BadRequestException.class, () -> guard.prepareUpdate(SELF, kb(true, null), kb(null, null)));
        }

        @Test
        @DisplayName("an index keeps its storage")
        void storageCannotMove() {
            var otherTable = kb(true, Map.of("table", "elsewhere"));
            assertThrows(BadRequestException.class, () -> guard.prepareUpdate(SELF, kb(true, null), otherTable));

            var otherType = kb(true, null);
            otherType.setStoreType("qdrant");
            assertThrows(BadRequestException.class, () -> guard.prepareUpdate(SELF, kb(true, null), otherType));

            var otherServer = kb(true, Map.of("host", "db2.internal"));
            assertThrows(BadRequestException.class, () -> guard.prepareUpdate(SELF, kb(true, null), otherServer));
        }

        @Test
        @DisplayName("an unchanged index may be saved — renamed, retuned — and stays eligible")
        void unchangedIndexSaves() {
            var renamed = kb(true, null);
            renamed.setName("team documents");
            renamed.setMaxResults(8);
            assertDoesNotThrow(() -> guard.prepareUpdate(SELF, kb(true, null), renamed));
        }

        @Test
        @DisplayName("an index may not gain ingestion sources")
        void cannotBecomeIneligible() {
            var withSources = kb(true, null);
            var source = new IngestionSource();
            source.setName("crawler");
            withSources.setSources(List.of(source));
            assertThrows(BadRequestException.class, () -> guard.prepareUpdate(SELF, kb(true, null), withSources));
        }

        @Test
        @DisplayName("turned on while a workflow uses it as a RAG step: refused, naming the workflow")
        void cannotBeTurnedOnWhileAWorkflowUsesIt() throws Exception {
            var descriptor = new DocumentDescriptor();
            descriptor.setName("support workflow");
            descriptor.setResource(URI.create("eddi://ai.labs.workflow/workflowstore/workflows/" + WORKFLOW + "?version=1"));
            when(workflowStore.getWorkflowDescriptorsContainingResource("eddi://ai.labs.rag/ragstore/rags/" + SELF + "?version=4", true))
                    .thenReturn(List.of(descriptor));

            var refused = assertThrows(BadRequestException.class, () -> guard.prepareUpdate(SELF, kb(null, null), kb(true, null)));
            assertTrue(refused.getMessage().contains("support workflow"), refused.getMessage());
        }

        @Test
        void canBeTurnedOnWhenNoWorkflowUsesIt() {
            assertDoesNotThrow(() -> guard.prepareUpdate(SELF, kb(null, null), kb(true, null)));
        }

        @Test
        @DisplayName("turned on where the workflows cannot be checked: refused, not passed unchecked")
        void cannotBeTurnedOnWithoutTheWorkflowLookup() throws Exception {
            var unchecked = new KnowledgeBaseStorageGuard(ragStore, descriptorStore, accessGuard);
            assertThrows(BadRequestException.class, () -> unchecked.prepareUpdate(SELF, kb(null, null), kb(true, null)));

            when(workflowStore.getWorkflowDescriptorsContainingResource(anyString(), anyBoolean()))
                    .thenThrow(new IResourceStore.ResourceStoreException("down"));
            assertThrows(IllegalStateException.class, () -> guard.prepareUpdate(SELF, kb(null, null), kb(true, null)));
        }

        @Test
        @DisplayName("turned on with an unchanged explicit location another knowledge base shares: refused")
        void turningOnRechecksASharedLocation() throws Exception {
            otherKnowledgeBaseUsesTable("shared_chunks", null);
            assertThrows(BadRequestException.class,
                    () -> guard.prepareUpdate(SELF, kb(null, Map.of("table", "shared_chunks")), kb(true, Map.of("table", "shared_chunks"))));
        }

        @Test
        @DisplayName("an ordinary knowledge base's update is untouched by the index rules")
        void ordinaryUpdateIsUnaffected() {
            var moved = kb(null, Map.of("table", "elsewhere"));
            assertDoesNotThrow(() -> guard.prepareUpdate(SELF, kb(null, null), moved));
        }
    }
}
