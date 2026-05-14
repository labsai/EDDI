/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.memory.rest;

import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.configs.properties.IUserMemoryStore;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.memory.IAttachmentStorage;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.memory.descriptor.IConversationDescriptorStore;
import ai.labs.eddi.engine.memory.descriptor.model.ConversationDescriptor;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.memory.model.ConversationStatus;
import ai.labs.eddi.engine.runtime.IRuntime;
import jakarta.enterprise.inject.Instance;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link RestConversationStore}.
 */
class RestConversationStoreTest {

    private IDocumentDescriptorStore documentDescriptorStore;
    private IConversationDescriptorStore conversationDescriptorStore;
    private IConversationMemoryStore conversationMemoryStore;
    private IUserMemoryStore userMemoryStore;
    private IRuntime runtime;
    private Instance<IAttachmentStorage> attachmentStorageInstance;
    private RestConversationStore restConversationStore;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        documentDescriptorStore = mock(IDocumentDescriptorStore.class);
        conversationDescriptorStore = mock(IConversationDescriptorStore.class);
        conversationMemoryStore = mock(IConversationMemoryStore.class);
        userMemoryStore = mock(IUserMemoryStore.class);
        runtime = mock(IRuntime.class);
        attachmentStorageInstance = mock(Instance.class);
        when(attachmentStorageInstance.isResolvable()).thenReturn(false);

        restConversationStore = new RestConversationStore(
                documentDescriptorStore, conversationDescriptorStore,
                conversationMemoryStore, userMemoryStore, runtime,
                30, 90, attachmentStorageInstance);
    }

    @Nested
    @DisplayName("readRawConversationLog")
    class ReadRawConversationLog {

        @Test
        @DisplayName("should delegate to memory store")
        void delegatesToStore() throws Exception {
            var snapshot = new ConversationMemorySnapshot();
            snapshot.setConversationState(ConversationState.READY);
            when(conversationMemoryStore.loadConversationMemorySnapshot("conv-1"))
                    .thenReturn(snapshot);

            ConversationMemorySnapshot result = restConversationStore.readRawConversationLog("conv-1");

            assertEquals(ConversationState.READY, result.getConversationState());
        }

        @Test
        @DisplayName("should throw for null conversationId")
        void throwsForNull() {
            assertThrows(IllegalArgumentException.class,
                    () -> restConversationStore.readRawConversationLog(null));
        }
    }

    @Nested
    @DisplayName("deleteConversationLog")
    class DeleteConversationLog {

        @Test
        @DisplayName("should permanently delete when flag is true")
        void permanentDelete() throws Exception {
            restConversationStore.deleteConversationLog("conv-1", true);

            verify(conversationMemoryStore).deleteConversationMemorySnapshot("conv-1");
        }

        @Test
        @DisplayName("should not delete snapshot when flag is false")
        void nonPermanentDelete() throws Exception {
            restConversationStore.deleteConversationLog("conv-1", false);

            verify(conversationMemoryStore, never()).deleteConversationMemorySnapshot("conv-1");
        }

        @Test
        @DisplayName("should throw for null conversationId")
        void throwsForNull() {
            assertThrows(IllegalArgumentException.class,
                    () -> restConversationStore.deleteConversationLog(null, true));
        }
    }

    @Nested
    @DisplayName("permanentlyDeleteEndedConversationLogs")
    class PermanentlyDeleteEndedConversations {

        @Test
        @DisplayName("should return 0 when deleteOlderThanDays is null")
        void nullDays() throws Exception {
            Integer result = restConversationStore.permanentlyDeleteEndedConversationLogs(null);
            assertEquals(0, result);
        }

        @Test
        @DisplayName("should delete old ended conversations")
        void deletesOldConversations() throws Exception {
            when(conversationMemoryStore.getEndedConversationIds())
                    .thenReturn(List.of("conv-old"));
            var descriptor = new DocumentDescriptor();
            // Set to 100 days ago
            descriptor.setLastModifiedOn(new Date(System.currentTimeMillis() - 100L * 24 * 60 * 60 * 1000));
            when(documentDescriptorStore.readDescriptor("conv-old", 0)).thenReturn(descriptor);

            Integer result = restConversationStore.permanentlyDeleteEndedConversationLogs(30);

            assertEquals(1, result);
            verify(conversationMemoryStore).deleteConversationMemorySnapshot("conv-old");
            verify(documentDescriptorStore).deleteAllDescriptor("conv-old");
        }

        @Test
        @DisplayName("should not delete recent conversations")
        void doesNotDeleteRecent() throws Exception {
            when(conversationMemoryStore.getEndedConversationIds())
                    .thenReturn(List.of("conv-recent"));
            var descriptor = new DocumentDescriptor();
            descriptor.setLastModifiedOn(new Date()); // now
            when(documentDescriptorStore.readDescriptor("conv-recent", 0)).thenReturn(descriptor);

            Integer result = restConversationStore.permanentlyDeleteEndedConversationLogs(30);

            assertEquals(0, result);
            verify(conversationMemoryStore, never()).deleteConversationMemorySnapshot("conv-recent");
        }

        @Test
        @DisplayName("should handle orphaned conversations without descriptor")
        void handlesOrphaned() throws Exception {
            when(conversationMemoryStore.getEndedConversationIds())
                    .thenReturn(List.of("conv-orphan"));
            when(documentDescriptorStore.readDescriptor("conv-orphan", 0))
                    .thenThrow(new IResourceStore.ResourceNotFoundException("not found"));

            Integer result = restConversationStore.permanentlyDeleteEndedConversationLogs(30);

            assertEquals(0, result);
            verify(conversationMemoryStore).deleteConversationMemorySnapshot("conv-orphan");
        }
    }

    @Nested
    @DisplayName("getActiveConversations")
    class GetActiveConversations {

        @Test
        @DisplayName("should return active conversation statuses")
        void returnsStatuses() throws Exception {
            var snapshot = new ConversationMemorySnapshot();
            snapshot.setId("conv-1");
            snapshot.setConversationState(ConversationState.READY);
            when(conversationMemoryStore.loadActiveConversationMemorySnapshot("agent-1", 1))
                    .thenReturn(List.of(snapshot));
            var convDesc = new ConversationDescriptor();
            convDesc.setLastModifiedOn(new Date());
            when(conversationDescriptorStore.readDescriptor("conv-1", 0))
                    .thenReturn(convDesc);

            List<ConversationStatus> result = restConversationStore.getActiveConversations("agent-1", 1);

            assertEquals(1, result.size());
            assertEquals("conv-1", result.get(0).getConversationId());
            assertEquals(ConversationState.READY, result.get(0).getConversationState());
        }

        @Test
        @DisplayName("should throw for null agentId")
        void throwsForNull() {
            assertThrows(IllegalArgumentException.class,
                    () -> restConversationStore.getActiveConversations(null, 1));
        }
    }

    @Nested
    @DisplayName("endActiveConversations")
    class EndActiveConversations {

        @Test
        @DisplayName("should set state to ENDED for all conversations")
        void setsEndedState() throws Exception {
            var status = new ConversationStatus();
            status.setConversationId("conv-1");
            var convDesc = new ConversationDescriptor();
            when(conversationDescriptorStore.readDescriptor("conv-1", 0)).thenReturn(convDesc);

            Response response = restConversationStore.endActiveConversations(List.of(status));

            assertEquals(200, response.getStatus());
            verify(conversationMemoryStore).setConversationState("conv-1", ConversationState.ENDED);
            verify(conversationDescriptorStore).setDescriptor(eq("conv-1"), eq(0), any());
        }
    }

    @Nested
    @DisplayName("cleanupOldUserMemories")
    class CleanupOldUserMemories {

        @Test
        @DisplayName("should skip when deleteMemoriesOlderThanDays is 0")
        void skipsWhenZero() {
            var store = new RestConversationStore(
                    documentDescriptorStore, conversationDescriptorStore,
                    conversationMemoryStore, userMemoryStore, runtime,
                    30, 0, attachmentStorageInstance);

            // Should not throw
            store.cleanupOldUserMemories();

            verify(runtime, never()).submitCallable(any(), any());
        }
    }

    @Nested
    @DisplayName("pagination parameter sanitization (CodeQL fix)")
    class PaginationParameterSanitization {

        @Test
        @DisplayName("should clamp null index to 0")
        void nullIndex() throws Exception {
            when(conversationDescriptorStore.readDescriptors(anyString(), any(), anyInt(), anyInt(), anyBoolean()))
                    .thenReturn(List.of());

            List<ConversationDescriptor> result = restConversationStore.readConversationDescriptors(
                    null, 10, null, null, null, null, null, null);

            // Should not throw, empty result is fine
            assertNotNull(result);
            // Verify it called with index=0 (clamped from null)
            verify(conversationDescriptorStore).readDescriptors(anyString(), any(), eq(0), eq(10), anyBoolean());
        }

        @Test
        @DisplayName("should clamp negative index to 0")
        void negativeIndex() throws Exception {
            when(conversationDescriptorStore.readDescriptors(anyString(), any(), anyInt(), anyInt(), anyBoolean()))
                    .thenReturn(List.of());

            List<ConversationDescriptor> result = restConversationStore.readConversationDescriptors(
                    -5, 10, null, null, null, null, null, null);

            assertNotNull(result);
            verify(conversationDescriptorStore).readDescriptors(anyString(), any(), eq(0), eq(10), anyBoolean());
        }

        @Test
        @DisplayName("should clamp null limit to 20")
        void nullLimit() throws Exception {
            when(conversationDescriptorStore.readDescriptors(anyString(), any(), anyInt(), anyInt(), anyBoolean()))
                    .thenReturn(List.of());

            restConversationStore.readConversationDescriptors(
                    0, null, null, null, null, null, null, null);

            verify(conversationDescriptorStore).readDescriptors(anyString(), any(), eq(0), eq(20), anyBoolean());
        }

        @Test
        @DisplayName("should clamp limit > 100 to 100")
        void excessiveLimit() throws Exception {
            when(conversationDescriptorStore.readDescriptors(anyString(), any(), anyInt(), anyInt(), anyBoolean()))
                    .thenReturn(List.of());

            restConversationStore.readConversationDescriptors(
                    0, 500, null, null, null, null, null, null);

            verify(conversationDescriptorStore).readDescriptors(anyString(), any(), eq(0), eq(100), anyBoolean());
        }

        @Test
        @DisplayName("should accept valid limit within bounds")
        void validLimit() throws Exception {
            when(conversationDescriptorStore.readDescriptors(anyString(), any(), anyInt(), anyInt(), anyBoolean()))
                    .thenReturn(List.of());

            restConversationStore.readConversationDescriptors(
                    0, 50, null, null, null, null, null, null);

            verify(conversationDescriptorStore).readDescriptors(anyString(), any(), eq(0), eq(50), anyBoolean());
        }
    }
}
