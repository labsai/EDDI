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

import java.net.URI;
import java.util.ArrayList;
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
    private IAttachmentStorage attachmentStorage;
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
        attachmentStorage = mock(IAttachmentStorage.class);
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
    @DisplayName("readSimpleConversationLog")
    class ReadSimpleConversationLog {

        @Test
        @DisplayName("should throw for null conversationId")
        void throwsForNullConversationId() {
            assertThrows(IllegalArgumentException.class,
                    () -> restConversationStore.readSimpleConversationLog(null, true, false, null));
        }

        @Test
        @DisplayName("should throw for null returnDetailed")
        void throwsForNullReturnDetailed() {
            assertThrows(IllegalArgumentException.class,
                    () -> restConversationStore.readSimpleConversationLog("conv-1", null, false, null));
        }

        @Test
        @DisplayName("should throw for null returnCurrentStepOnly")
        void throwsForNullReturnCurrentStepOnly() {
            assertThrows(IllegalArgumentException.class,
                    () -> restConversationStore.readSimpleConversationLog("conv-1", true, null, null));
        }

        @Test
        @DisplayName("should delegate to memory store and convert")
        void delegatesAndConverts() throws Exception {
            var snapshot = new ConversationMemorySnapshot();
            snapshot.setConversationState(ConversationState.READY);
            snapshot.setConversationSteps(new ArrayList<>());
            when(conversationMemoryStore.loadConversationMemorySnapshot("conv-1"))
                    .thenReturn(snapshot);

            var result = restConversationStore.readSimpleConversationLog("conv-1", false, false, null);

            assertNotNull(result);
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

        @Test
        @DisplayName("should delete attachments when storage is resolvable and permanently deleting")
        void deletesAttachments() throws Exception {
            when(attachmentStorageInstance.isResolvable()).thenReturn(true);
            when(attachmentStorageInstance.get()).thenReturn(attachmentStorage);
            when(attachmentStorage.deleteByConversation("conv-1")).thenReturn(3L);

            var store = new RestConversationStore(
                    documentDescriptorStore, conversationDescriptorStore,
                    conversationMemoryStore, userMemoryStore, runtime,
                    30, 90, attachmentStorageInstance);

            store.deleteConversationLog("conv-1", true);

            verify(attachmentStorage).deleteByConversation("conv-1");
            verify(conversationMemoryStore).deleteConversationMemorySnapshot("conv-1");
        }

        @Test
        @DisplayName("should handle attachment deletion failure gracefully")
        void handlesAttachmentDeletionFailure() throws Exception {
            when(attachmentStorageInstance.isResolvable()).thenReturn(true);
            when(attachmentStorageInstance.get()).thenReturn(attachmentStorage);
            when(attachmentStorage.deleteByConversation("conv-1")).thenThrow(new RuntimeException("Storage error"));

            var store = new RestConversationStore(
                    documentDescriptorStore, conversationDescriptorStore,
                    conversationMemoryStore, userMemoryStore, runtime,
                    30, 90, attachmentStorageInstance);

            // Should not throw — logs warning and continues
            assertDoesNotThrow(() -> store.deleteConversationLog("conv-1", true));
            verify(conversationMemoryStore).deleteConversationMemorySnapshot("conv-1");
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
        @DisplayName("should return 0 when deleteOlderThanDays is -1")
        void negativeDays() throws Exception {
            Integer result = restConversationStore.permanentlyDeleteEndedConversationLogs(-1);
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

        @Test
        @DisplayName("should work with deleteOlderThanDays=0 (delete all)")
        void zeroDeletes() throws Exception {
            when(conversationMemoryStore.getEndedConversationIds())
                    .thenReturn(List.of("conv-1"));
            var descriptor = new DocumentDescriptor();
            // Set to 2 days ago so it's reliably older than any threshold
            descriptor.setLastModifiedOn(new Date(System.currentTimeMillis() - 2 * 86400_000L));
            when(documentDescriptorStore.readDescriptor("conv-1", 0)).thenReturn(descriptor);

            // deleteOlderThanDays=0 means "delete conversations older than 0 days ago"
            Integer result = restConversationStore.permanentlyDeleteEndedConversationLogs(0);
            assertEquals(1, result);
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

        @Test
        @DisplayName("should throw for null agentVersion")
        void throwsForNullVersion() {
            assertThrows(IllegalArgumentException.class,
                    () -> restConversationStore.getActiveConversations("agent-1", null));
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

        @Test
        @DisplayName("should handle multiple conversation statuses")
        void multipleStatuses() throws Exception {
            var status1 = new ConversationStatus();
            status1.setConversationId("conv-1");
            var status2 = new ConversationStatus();
            status2.setConversationId("conv-2");

            when(conversationDescriptorStore.readDescriptor("conv-1", 0)).thenReturn(new ConversationDescriptor());
            when(conversationDescriptorStore.readDescriptor("conv-2", 0)).thenReturn(new ConversationDescriptor());

            Response response = restConversationStore.endActiveConversations(List.of(status1, status2));

            assertEquals(200, response.getStatus());
            verify(conversationMemoryStore).setConversationState("conv-1", ConversationState.ENDED);
            verify(conversationMemoryStore).setConversationState("conv-2", ConversationState.ENDED);
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

        @Test
        @DisplayName("should skip when deleteMemoriesOlderThanDays is null")
        void skipsWhenNull() {
            var store = new RestConversationStore(
                    documentDescriptorStore, conversationDescriptorStore,
                    conversationMemoryStore, userMemoryStore, runtime,
                    30, null, attachmentStorageInstance);

            store.cleanupOldUserMemories();

            verify(runtime, never()).submitCallable(any(), any());
        }

        @Test
        @DisplayName("should submit cleanup task when deleteMemoriesOlderThanDays is positive")
        void submitsTaskWhenPositive() {
            var store = new RestConversationStore(
                    documentDescriptorStore, conversationDescriptorStore,
                    conversationMemoryStore, userMemoryStore, runtime,
                    30, 90, attachmentStorageInstance);

            store.cleanupOldUserMemories();

            verify(runtime).submitCallable(any(), any());
        }
    }

    @Nested
    @DisplayName("readConversationDescriptors with conversation state filter")
    class DescriptorsStateFilter {

        @Test
        @DisplayName("should filter by conversation state")
        void filtersConversationState() throws Exception {
            var descriptor = new ConversationDescriptor();
            descriptor.setResource(URI.create("eddi://conv/conversationstore/conversations/111111111111111111111111?version=1"));
            descriptor.setLastModifiedOn(new Date());

            when(conversationDescriptorStore.readDescriptors(anyString(), any(), eq(0), eq(20), anyBoolean()))
                    .thenReturn(List.of(descriptor));

            var snapshot = new ConversationMemorySnapshot();
            snapshot.setConversationState(ConversationState.ENDED);
            snapshot.setAgentId("212121212121212121212121");
            snapshot.setAgentVersion(1);
            snapshot.setConversationSteps(new ArrayList<>());
            when(conversationMemoryStore.loadConversationMemorySnapshot("111111111111111111111111"))
                    .thenReturn(snapshot);

            var docDesc = new DocumentDescriptor();
            docDesc.setName("Agent Name");
            when(documentDescriptorStore.readDescriptor("212121212121212121212121", 1)).thenReturn(docDesc);

            // Filter by READY state — snapshot is ENDED, so it should be excluded
            List<ConversationDescriptor> result = restConversationStore.readConversationDescriptors(
                    0, 20, null, null, null, null, ConversationState.READY, null);

            assertEquals(0, result.size());
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

            assertNotNull(result);
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

        @Test
        @DisplayName("should clamp zero limit to 20")
        void zeroLimit() throws Exception {
            when(conversationDescriptorStore.readDescriptors(anyString(), any(), anyInt(), anyInt(), anyBoolean()))
                    .thenReturn(List.of());

            restConversationStore.readConversationDescriptors(
                    0, 0, null, null, null, null, null, null);

            verify(conversationDescriptorStore).readDescriptors(anyString(), any(), eq(0), eq(20), anyBoolean());
        }
    }

    @Nested
    @DisplayName("deleteEndedConversationsOlderThanXDays scheduled task")
    class ScheduledDeletion {

        @Test
        @DisplayName("should submit callable to runtime")
        void submitsCallable() {
            restConversationStore.deleteEndedConversationsOlderThanXDays();

            verify(runtime).submitCallable(any(), any());
        }
    }
    @Nested
    @DisplayName("readConversationDescriptors with agentId filter")
    class DescriptorsAgentIdFilter {

        @Test
        @DisplayName("should include descriptor with matching agentId")
        void includesMatchingAgentId() throws Exception {
            var descriptor = new ConversationDescriptor();
            descriptor.setResource(URI.create("eddi://conv/conversationstore/conversations/111111111111111111111111?version=1"));
            descriptor.setAgentResource(URI.create("eddi://ai.labs.agent/agentstore/agents/212121212121212121212121?version=1"));
            descriptor.setLastModifiedOn(new Date());

            when(conversationDescriptorStore.readDescriptors(anyString(), any(), eq(0), eq(20), anyBoolean()))
                    .thenReturn(List.of(descriptor));

            var snapshot = new ConversationMemorySnapshot();
            snapshot.setConversationState(ConversationState.READY);
            snapshot.setAgentId("212121212121212121212121");
            snapshot.setAgentVersion(1);
            snapshot.setConversationSteps(new ArrayList<>());
            when(conversationMemoryStore.loadConversationMemorySnapshot("111111111111111111111111"))
                    .thenReturn(snapshot);

            var docDesc = new DocumentDescriptor();
            docDesc.setName("Test Agent");
            when(documentDescriptorStore.readDescriptor("212121212121212121212121", 1)).thenReturn(docDesc);

            List<ConversationDescriptor> result = restConversationStore.readConversationDescriptors(
                    0, 20, null, null, "212121212121212121212121", null, null, null);

            assertEquals(1, result.size());
        }

        @Test
        @DisplayName("should exclude descriptor with non-matching agentId")
        void excludesNonMatchingAgentId() throws Exception {
            var descriptor = new ConversationDescriptor();
            descriptor.setResource(URI.create("eddi://conv/conversationstore/conversations/111111111111111111111111?version=1"));
            descriptor.setAgentResource(URI.create("eddi://ai.labs.agent/agentstore/agents/222222222222222222222222?version=1"));
            descriptor.setLastModifiedOn(new Date());

            when(conversationDescriptorStore.readDescriptors(anyString(), any(), eq(0), eq(20), anyBoolean()))
                    .thenReturn(List.of(descriptor))
                    .thenReturn(List.of());

            var snapshot = new ConversationMemorySnapshot();
            snapshot.setConversationState(ConversationState.READY);
            snapshot.setAgentId("222222222222222222222222");
            snapshot.setAgentVersion(1);
            snapshot.setConversationSteps(new ArrayList<>());
            when(conversationMemoryStore.loadConversationMemorySnapshot("111111111111111111111111"))
                    .thenReturn(snapshot);

            var docDesc = new DocumentDescriptor();
            docDesc.setName("Agent 2");
            when(documentDescriptorStore.readDescriptor("222222222222222222222222", 1)).thenReturn(docDesc);

            List<ConversationDescriptor> result = restConversationStore.readConversationDescriptors(
                    0, 20, null, null, "212121212121212121212121", null, null, null);

            assertEquals(0, result.size());
        }

        @Test
        @DisplayName("should filter by agentVersion when provided")
        void filtersByAgentVersion() throws Exception {
            var descriptor = new ConversationDescriptor();
            descriptor.setResource(URI.create("eddi://conv/conversationstore/conversations/111111111111111111111111?version=1"));
            descriptor.setAgentResource(URI.create("eddi://ai.labs.agent/agentstore/agents/212121212121212121212121?version=2"));
            descriptor.setLastModifiedOn(new Date());

            when(conversationDescriptorStore.readDescriptors(anyString(), any(), eq(0), eq(20), anyBoolean()))
                    .thenReturn(List.of(descriptor))
                    .thenReturn(List.of());

            var snapshot = new ConversationMemorySnapshot();
            snapshot.setConversationState(ConversationState.READY);
            snapshot.setAgentId("212121212121212121212121");
            snapshot.setAgentVersion(2);
            snapshot.setConversationSteps(new ArrayList<>());
            when(conversationMemoryStore.loadConversationMemorySnapshot("111111111111111111111111"))
                    .thenReturn(snapshot);

            var docDesc = new DocumentDescriptor();
            docDesc.setName("Agent 1");
            when(documentDescriptorStore.readDescriptor("212121212121212121212121", 2)).thenReturn(docDesc);

            // Request version 1, but descriptor has version 2 — should be excluded
            List<ConversationDescriptor> result = restConversationStore.readConversationDescriptors(
                    0, 20, null, null, "212121212121212121212121", 1, null, null);

            assertEquals(0, result.size());
        }
    }

    @Nested
    @DisplayName("readConversationDescriptors with viewState filter")
    class DescriptorsViewStateFilter {

        @Test
        @DisplayName("should exclude descriptor with non-matching viewState")
        void excludesNonMatchingViewState() throws Exception {
            var descriptor = new ConversationDescriptor();
            descriptor.setResource(URI.create("eddi://conv/conversationstore/conversations/111111111111111111111111?version=1"));
            descriptor.setViewState(ConversationDescriptor.ViewState.UNSEEN);
            descriptor.setLastModifiedOn(new Date());

            when(conversationDescriptorStore.readDescriptors(anyString(), any(), eq(0), eq(20), anyBoolean()))
                    .thenReturn(List.of(descriptor))
                    .thenReturn(List.of());

            var snapshot = new ConversationMemorySnapshot();
            snapshot.setConversationState(ConversationState.READY);
            snapshot.setAgentId("212121212121212121212121");
            snapshot.setAgentVersion(1);
            snapshot.setConversationSteps(new ArrayList<>());
            when(conversationMemoryStore.loadConversationMemorySnapshot("111111111111111111111111"))
                    .thenReturn(snapshot);

            var docDesc = new DocumentDescriptor();
            docDesc.setName("Test Agent");
            when(documentDescriptorStore.readDescriptor("212121212121212121212121", 1)).thenReturn(docDesc);

            List<ConversationDescriptor> result = restConversationStore.readConversationDescriptors(
                    0, 20, null, null, null, null, null, ConversationDescriptor.ViewState.SEEN);

            assertEquals(0, result.size());
        }
    }

    @Nested
    @DisplayName("readConversationDescriptors filter fallback")
    class DescriptorsFilterFallback {

        @Test
        @DisplayName("should retry with null filter when initial filter returns empty at index 0")
        void retriesWithNullFilter() throws Exception {
            // First call with filter returns empty, second with null returns descriptors
            when(conversationDescriptorStore.readDescriptors(anyString(), eq("search-term"), eq(0), eq(20), anyBoolean()))
                    .thenReturn(List.of());

            var descriptor = new ConversationDescriptor();
            descriptor.setResource(URI.create("eddi://conv/conversationstore/conversations/111111111111111111111111?version=1"));
            descriptor.setLastModifiedOn(new Date());

            when(conversationDescriptorStore.readDescriptors(anyString(), isNull(), eq(0), eq(20), anyBoolean()))
                    .thenReturn(List.of(descriptor))
                    .thenReturn(List.of());

            var snapshot = new ConversationMemorySnapshot();
            snapshot.setConversationState(ConversationState.READY);
            snapshot.setAgentId("212121212121212121212121");
            snapshot.setAgentVersion(1);
            snapshot.setConversationSteps(new ArrayList<>());
            when(conversationMemoryStore.loadConversationMemorySnapshot("111111111111111111111111"))
                    .thenReturn(snapshot);

            var docDesc = new DocumentDescriptor();
            docDesc.setName("Test Agent");
            when(documentDescriptorStore.readDescriptor("212121212121212121212121", 1)).thenReturn(docDesc);

            List<ConversationDescriptor> result = restConversationStore.readConversationDescriptors(
                    0, 20, "search-term", null, null, null, null, null);

            assertEquals(1, result.size());
        }
    }

    @Nested
    @DisplayName("populateDataToDescriptor edge cases")
    class PopulateDataEdgeCases {

        @Test
        @DisplayName("should set userId from snapshot when descriptor userId is null")
        void setsUserIdFromSnapshot() throws Exception {
            var descriptor = new ConversationDescriptor();
            descriptor.setResource(URI.create("eddi://conv/conversationstore/conversations/111111111111111111111111?version=1"));
            descriptor.setLastModifiedOn(new Date());
            descriptor.setUserId(null); // null userId triggers fallback

            when(conversationDescriptorStore.readDescriptors(anyString(), any(), eq(0), eq(20), anyBoolean()))
                    .thenReturn(List.of(descriptor))
                    .thenReturn(List.of());

            var snapshot = new ConversationMemorySnapshot();
            snapshot.setConversationState(ConversationState.READY);
            snapshot.setAgentId("212121212121212121212121");
            snapshot.setAgentVersion(1);
            snapshot.setUserId("fallback-user-id");
            snapshot.setConversationSteps(new ArrayList<>());
            when(conversationMemoryStore.loadConversationMemorySnapshot("111111111111111111111111"))
                    .thenReturn(snapshot);

            var docDesc = new DocumentDescriptor();
            docDesc.setName("Test Agent");
            when(documentDescriptorStore.readDescriptor("212121212121212121212121", 1)).thenReturn(docDesc);

            List<ConversationDescriptor> result = restConversationStore.readConversationDescriptors(
                    0, 20, null, null, null, null, null, null);

            assertEquals(1, result.size());
            assertEquals("fallback-user-id", result.get(0).getUserId());
        }

        @Test
        @DisplayName("should set agentName from document descriptor when empty")
        void setsAgentNameFromDocDescriptor() throws Exception {
            var descriptor = new ConversationDescriptor();
            descriptor.setResource(URI.create("eddi://conv/conversationstore/conversations/111111111111111111111111?version=1"));
            descriptor.setLastModifiedOn(new Date());
            descriptor.setAgentName(null); // empty triggers lookup

            when(conversationDescriptorStore.readDescriptors(anyString(), any(), eq(0), eq(20), anyBoolean()))
                    .thenReturn(List.of(descriptor))
                    .thenReturn(List.of());

            var snapshot = new ConversationMemorySnapshot();
            snapshot.setConversationState(ConversationState.READY);
            snapshot.setAgentId("212121212121212121212121");
            snapshot.setAgentVersion(1);
            snapshot.setConversationSteps(new ArrayList<>());
            when(conversationMemoryStore.loadConversationMemorySnapshot("111111111111111111111111"))
                    .thenReturn(snapshot);

            var docDesc = new DocumentDescriptor();
            docDesc.setName("My Cool Agent");
            when(documentDescriptorStore.readDescriptor("212121212121212121212121", 1)).thenReturn(docDesc);

            List<ConversationDescriptor> result = restConversationStore.readConversationDescriptors(
                    0, 20, null, null, null, null, null, null);

            assertEquals(1, result.size());
            assertEquals("My Cool Agent", result.get(0).getAgentName());
        }
    }

    @Nested
    @DisplayName("getActiveConversations empty list")
    class GetActiveConversationsEmpty {

        @Test
        @DisplayName("should return empty list when no active conversations")
        void returnsEmptyList() throws Exception {
            when(conversationMemoryStore.loadActiveConversationMemorySnapshot("agent-1", 1))
                    .thenReturn(List.of());

            List<ConversationStatus> result = restConversationStore.getActiveConversations("agent-1", 1);

            assertTrue(result.isEmpty());
        }
    }

    @Nested
    @DisplayName("permanentlyDeleteEndedConversationLogs with attachments")
    class DeleteWithAttachments {

        @Test
        @DisplayName("should delete attachments when storage is resolvable during permanent delete")
        void deletesAttachmentsDuringCleanup() throws Exception {
            when(attachmentStorageInstance.isResolvable()).thenReturn(true);
            when(attachmentStorageInstance.get()).thenReturn(attachmentStorage);
            when(attachmentStorage.deleteByConversation("conv-old")).thenReturn(2L);

            var store = new RestConversationStore(
                    documentDescriptorStore, conversationDescriptorStore,
                    conversationMemoryStore, userMemoryStore, runtime,
                    30, 90, attachmentStorageInstance);

            when(conversationMemoryStore.getEndedConversationIds())
                    .thenReturn(List.of("conv-old"));
            var docDesc = new DocumentDescriptor();
            docDesc.setLastModifiedOn(new Date(System.currentTimeMillis() - 100L * 24 * 60 * 60 * 1000));
            when(documentDescriptorStore.readDescriptor("conv-old", 0)).thenReturn(docDesc);

            Integer result = store.permanentlyDeleteEndedConversationLogs(30);

            assertEquals(1, result);
            verify(attachmentStorage).deleteByConversation("conv-old");
        }
    }
}
