/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.memory;

import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.agenttests.AgentTestConversations;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.attachments.IAttachmentStore;
import ai.labs.eddi.engine.hitl.tools.IHitlToolJournalStore;
import ai.labs.eddi.engine.memory.descriptor.IConversationDescriptorStore;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.secrets.AutoVaultedSecrets;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@DisplayName("ConversationPurgeService")
class ConversationPurgeServiceTest {

    private static final String CONVERSATION_ID = "0123456789abcdef01234567";
    private static final String RUN_ID = "aaaaaaaaaaaaaaaaaaaaaaaa";

    private IConversationMemoryStore memoryStore;
    private IConversationDescriptorStore descriptorStore;
    private IConversationService conversationService;
    private ConversationPurgeService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        memoryStore = mock(IConversationMemoryStore.class);
        descriptorStore = mock(IConversationDescriptorStore.class);
        conversationService = mock(IConversationService.class);
        Instance<AutoVaultedSecrets> vault = mock(Instance.class);
        when(vault.isResolvable()).thenReturn(false);
        Instance<IAttachmentStore> attachments = mock(Instance.class);
        when(attachments.isResolvable()).thenReturn(false);
        service = new ConversationPurgeService(memoryStore, descriptorStore, conversationService, vault, attachments);
    }

    private void storedAs(String userId) throws Exception {
        var snapshot = new ConversationMemorySnapshot();
        snapshot.setUserId(userId);
        when(memoryStore.loadConversationMemorySnapshot(CONVERSATION_ID)).thenReturn(snapshot);
    }

    @Test
    @DisplayName("the runner may delete its own test conversation")
    void purgesTheRunsOwnConversation() throws Exception {
        storedAs(AgentTestConversations.userIdFor(RUN_ID, 0, 1));

        service.purgeAgentTestConversation(CONVERSATION_ID, RUN_ID);

        verify(memoryStore).deleteConversationMemorySnapshot(CONVERSATION_ID);
        verify(descriptorStore).deleteAllDescriptor(CONVERSATION_ID);
    }

    @Test
    @DisplayName("a real user's conversation is refused and nothing is deleted")
    void refusesARealUsersConversation() throws Exception {
        storedAs("alice");

        assertThrows(ConversationPurgeService.PurgeRefusedException.class, () -> service.purgeAgentTestConversation(CONVERSATION_ID, RUN_ID));

        verify(memoryStore, never()).deleteConversationMemorySnapshot(anyString());
        verify(descriptorStore, never()).deleteAllDescriptor(anyString());
    }

    @Test
    @DisplayName("another run's test conversation is refused too")
    void refusesAnotherRunsConversation() throws Exception {
        storedAs(AgentTestConversations.userIdFor("bbbbbbbbbbbbbbbbbbbbbbbb", 0, 1));

        assertThrows(ConversationPurgeService.PurgeRefusedException.class, () -> service.purgeAgentTestConversation(CONVERSATION_ID, RUN_ID));
        verify(memoryStore, never()).deleteConversationMemorySnapshot(anyString());
    }

    @Test
    @DisplayName("a run id that is a prefix of another does not match it")
    void runIdPrefixDoesNotMatch() throws Exception {
        storedAs(AgentTestConversations.userIdFor(RUN_ID + "ff", 0, 1));

        assertThrows(ConversationPurgeService.PurgeRefusedException.class, () -> service.purgeAgentTestConversation(CONVERSATION_ID, RUN_ID));
    }

    @Test
    void anAlreadyDeletedConversationIsANoOp() throws Exception {
        when(memoryStore.loadConversationMemorySnapshot(CONVERSATION_ID)).thenThrow(new IResourceStore.ResourceNotFoundException("gone"));

        assertDoesNotThrow(() -> service.purgeAgentTestConversation(CONVERSATION_ID, RUN_ID));
        verify(memoryStore, never()).deleteConversationMemorySnapshot(anyString());
    }

    /**
     * The runner purges from a virtual thread with no request scope and no security
     * identity. Nothing in the delete path may need one: a pending approval is
     * ended as a system actor, and the stores are called directly.
     */
    @Test
    @DisplayName("works on a thread with no request identity, ending a pending approval as a system actor")
    void worksWithoutARequestIdentity() throws Exception {
        storedAs(AgentTestConversations.userIdFor(RUN_ID, 0, 1));
        when(memoryStore.getConversationState(CONVERSATION_ID)).thenReturn(ConversationState.AWAITING_HUMAN);
        var failure = new AtomicReference<Throwable>();

        Thread thread = Thread.ofVirtual().start(() -> {
            try {
                service.purgeAgentTestConversation(CONVERSATION_ID, RUN_ID);
            } catch (Throwable t) {
                failure.set(t);
            }
        });
        thread.join(10_000);

        assertNull(failure.get(), () -> "purge failed off the request thread: " + failure.get());
        verify(conversationService).endConversation(CONVERSATION_ID, "system:admin-end");
        verify(memoryStore).deleteConversationMemorySnapshot(CONVERSATION_ID);
    }

    /**
     * The tool loop checkpoints memory before every tool call (mocked test calls
     * included); a permanent delete that left those copies behind did not delete
     * the conversation. Only GDPR erasure used to remove them.
     */
    @Test
    @DisplayName("a permanent delete removes the conversation's memory checkpoints and HITL tool journal too")
    @SuppressWarnings("unchecked")
    void purgeDeletesCheckpoints() throws Exception {
        IConversationCheckpointStore checkpoints = mock(IConversationCheckpointStore.class);
        Instance<IConversationCheckpointStore> checkpointInstance = mock(Instance.class);
        when(checkpointInstance.isResolvable()).thenReturn(true);
        when(checkpointInstance.get()).thenReturn(checkpoints);
        Instance<AutoVaultedSecrets> vault = mock(Instance.class);
        Instance<IAttachmentStore> attachments = mock(Instance.class);
        IHitlToolJournalStore journal = mock(IHitlToolJournalStore.class);
        Instance<IHitlToolJournalStore> journalInstance = mock(Instance.class);
        when(journalInstance.isResolvable()).thenReturn(true);
        when(journalInstance.get()).thenReturn(journal);
        var withCheckpoints = new ConversationPurgeService(memoryStore, descriptorStore, conversationService, vault, attachments,
                checkpointInstance, journalInstance);

        withCheckpoints.purge(CONVERSATION_ID);

        verify(checkpoints).deleteByConversationId(CONVERSATION_ID);
        verify(journal).deleteByConversationId(CONVERSATION_ID);
        verify(memoryStore).deleteConversationMemorySnapshot(CONVERSATION_ID);
    }

    @Test
    @DisplayName("a failing checkpoint or tool-journal store does not stop the delete")
    @SuppressWarnings("unchecked")
    void checkpointFailureDoesNotStopThePurge() throws Exception {
        IConversationCheckpointStore checkpoints = mock(IConversationCheckpointStore.class);
        when(checkpoints.deleteByConversationId(CONVERSATION_ID)).thenThrow(new IllegalStateException("db down"));
        Instance<IConversationCheckpointStore> checkpointInstance = mock(Instance.class);
        when(checkpointInstance.isResolvable()).thenReturn(true);
        when(checkpointInstance.get()).thenReturn(checkpoints);
        IHitlToolJournalStore journal = mock(IHitlToolJournalStore.class);
        when(journal.deleteByConversationId(CONVERSATION_ID)).thenThrow(new IllegalStateException("db down"));
        Instance<IHitlToolJournalStore> journalInstance = mock(Instance.class);
        when(journalInstance.isResolvable()).thenReturn(true);
        when(journalInstance.get()).thenReturn(journal);
        var withCheckpoints = new ConversationPurgeService(memoryStore, descriptorStore, conversationService, mock(Instance.class),
                mock(Instance.class), checkpointInstance, journalInstance);

        assertDoesNotThrow(() -> withCheckpoints.purge(CONVERSATION_ID));
        verify(memoryStore).deleteConversationMemorySnapshot(CONVERSATION_ID);
    }

    @Test
    @DisplayName("the store's null for a missing conversation means already gone, not a refusal")
    void aNullSnapshotIsANoOp() throws Exception {
        when(memoryStore.loadConversationMemorySnapshot(CONVERSATION_ID)).thenReturn(null);

        assertDoesNotThrow(() -> service.purgeAgentTestConversation(CONVERSATION_ID, RUN_ID));
        verify(memoryStore, never()).deleteConversationMemorySnapshot(anyString());
    }
}
