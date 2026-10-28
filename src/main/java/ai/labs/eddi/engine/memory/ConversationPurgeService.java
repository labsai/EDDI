/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.memory;

import ai.labs.eddi.datastore.IResourceStore.ResourceNotFoundException;
import ai.labs.eddi.datastore.IResourceStore.ResourceStoreException;
import ai.labs.eddi.engine.agenttests.AgentTestConversations;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.attachments.IAttachmentStore;
import ai.labs.eddi.engine.hitl.tools.IHitlToolJournalStore;
import ai.labs.eddi.engine.memory.descriptor.IConversationDescriptorStore;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.secrets.AutoVaultedSecrets;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;
import static ai.labs.eddi.utils.RuntimeUtilities.checkNotNull;

/**
 * Permanently deletes a conversation: resolves a pending approval, then removes
 * its vault slots, attachments, memory checkpoints, HITL tool-journal entries,
 * memory and descriptor.
 * <p>
 * <b>This service checks nobody's rights.</b> It deliberately lives outside the
 * REST layer, so that the resource class exposes only caller-checked methods:
 * {@code RestConversationStore} calls {@link #purge} after its owner check, and
 * the agent-test runner calls {@link #purgeAgentTestConversation}, which
 * refuses everything but the runner's own conversations.
 * <p>
 * Nothing here reads a request identity — the HITL end is attributed to a
 * {@code system:*} actor and the stores are called directly — so it runs on a
 * background thread with authorization enabled, which is where the runner calls
 * it.
 *
 * @since 6.5.0
 */
@ApplicationScoped
public class ConversationPurgeService {

    private static final Logger LOGGER = Logger.getLogger(ConversationPurgeService.class);

    private final IConversationMemoryStore conversationMemoryStore;
    private final IConversationDescriptorStore conversationDescriptorStore;
    private final IConversationService conversationService;
    private final Instance<AutoVaultedSecrets> autoVaultedSecrets;
    private final Instance<IAttachmentStore> attachmentStore;
    private final Instance<IConversationCheckpointStore> checkpointStore;
    private final Instance<IHitlToolJournalStore> toolJournalStore;

    @Inject
    public ConversationPurgeService(IConversationMemoryStore conversationMemoryStore, IConversationDescriptorStore conversationDescriptorStore,
            IConversationService conversationService, Instance<AutoVaultedSecrets> autoVaultedSecrets, Instance<IAttachmentStore> attachmentStore,
            Instance<IConversationCheckpointStore> checkpointStore, Instance<IHitlToolJournalStore> toolJournalStore) {
        this.conversationMemoryStore = conversationMemoryStore;
        this.conversationDescriptorStore = conversationDescriptorStore;
        this.conversationService = conversationService;
        this.autoVaultedSecrets = autoVaultedSecrets;
        this.attachmentStore = attachmentStore;
        this.checkpointStore = checkpointStore;
        this.toolJournalStore = toolJournalStore;
    }

    /** Without a checkpoint store: for callers that build the service by hand. */
    public ConversationPurgeService(IConversationMemoryStore conversationMemoryStore, IConversationDescriptorStore conversationDescriptorStore,
            IConversationService conversationService, Instance<AutoVaultedSecrets> autoVaultedSecrets, Instance<IAttachmentStore> attachmentStore) {
        this(conversationMemoryStore, conversationDescriptorStore, conversationService, autoVaultedSecrets, attachmentStore, null, null);
    }

    /** Thrown when a purge is asked for a conversation the caller does not own. */
    public static class PurgeRefusedException extends IllegalStateException {
        public PurgeRefusedException(String message) {
            super(message);
        }
    }

    /**
     * Purges a conversation of an agent test run — and only that: a conversation
     * whose user id is not {@code agent-test:<runId>:…} is refused. Defence in
     * depth for the one caller that purges without a request to check: the runner
     * can only ever delete what it created.
     *
     * @throws PurgeRefusedException
     *             when the conversation does not belong to {@code runId}
     */
    public void purgeAgentTestConversation(String conversationId, String runId) throws ResourceStoreException, ResourceNotFoundException {
        checkNotNull(conversationId, "conversationId");
        checkNotNull(runId, "runId");
        ConversationMemorySnapshot snapshot;
        try {
            snapshot = conversationMemoryStore.loadConversationMemorySnapshot(conversationId);
        } catch (ResourceNotFoundException e) {
            return; // already gone
        }
        if (snapshot == null) {
            return; // the store answers null for a conversation that does not exist
        }
        String userId = snapshot.getUserId();
        String expectedPrefix = AgentTestConversations.USER_ID_PREFIX + runId + ":";
        if (userId == null || !userId.startsWith(expectedPrefix)) {
            throw new PurgeRefusedException("Conversation " + sanitize(conversationId) + " is not a conversation of agent test run "
                    + sanitize(runId) + "; refusing to delete it.");
        }
        purge(conversationId);
    }

    /**
     * The permanent delete. The caller must already have decided the delete is
     * allowed.
     *
     * @throws ResourceStoreException
     *             when the vault slots could not be deleted; nothing is deleted
     *             then
     */
    public void purge(String conversationId) throws ResourceStoreException, ResourceNotFoundException {
        checkNotNull(conversationId, "conversationId");
        // If the conversation is a live pending approval, resolve the HITL state
        // BEFORE removing the document: endConversation disarms the armed
        // timeout schedule (otherwise it fires later against a deleted
        // conversation, logs "Conversation not found", and leaves a dead
        // schedule row), clears the bookmark, writes the hitl.approval
        // cancellation audit, and invalidates the cached AWAITING_HUMAN state
        // (which getConversationState would otherwise keep serving for a
        // nonexistent conversation until TTL).
        try {
            if (conversationMemoryStore.getConversationState(conversationId) == ConversationState.AWAITING_HUMAN) {
                // G4: attribute the pause-terminating end (no request principal here,
                // and this also runs from scheduled cleanup).
                conversationService.endConversation(conversationId, "system:admin-end");
            }
        } catch (Exception e) {
            LOGGER.warnf("HITL cleanup before permanent delete failed for conversation %s: %s", sanitize(conversationId), e.getMessage());
        }

        // The snapshot holds the only references to the conversation's vault
        // slots: if they cannot be deleted, nothing is — a retry can then still
        // find them. Deleting the snapshot anyway would orphan the credentials.
        if (!deleteAutoVaultedSecrets(conversationId)) {
            throw new ResourceStoreException("Could not delete the vault secrets of conversation " + conversationId
                    + "; the conversation was not deleted. Retry once the secrets vault is reachable.");
        }
        deleteAttachments(conversationId);
        deleteCheckpoints(conversationId);
        deleteToolJournal(conversationId);
        conversationMemoryStore.deleteConversationMemorySnapshot(conversationId);
        conversationDescriptorStore.deleteAllDescriptor(conversationId);
        LOGGER.infof("Conversation has been permanently deleted (conversationId=%s)", sanitize(conversationId));
    }

    /**
     * Deletes the vault slots the conversation's auto-vaulted properties point at.
     *
     * @return false when they could not be deleted — the conversation must then be
     *         kept, with its references, for a retry
     */
    public boolean deleteAutoVaultedSecrets(String conversationId) {
        if (autoVaultedSecrets == null || !autoVaultedSecrets.isResolvable()) {
            return true;
        }
        ConversationMemorySnapshot snapshot;
        try {
            snapshot = conversationMemoryStore.loadConversationMemorySnapshot(conversationId);
        } catch (ResourceNotFoundException e) {
            return true; // no snapshot, no references
        } catch (Exception e) {
            LOGGER.warnf("Could not read conversation %s to delete its auto-vaulted secrets: %s", sanitize(conversationId), e.getMessage());
            return false;
        }
        if (snapshot == null) {
            return true;
        }
        try {
            var memory = ConversationMemoryUtilities.convertConversationMemorySnapshot(snapshot);
            int deleted = autoVaultedSecrets.get().deleteForConversation(ConversationMemory.everyPropertyVersion(memory), snapshot.getUserId());
            if (deleted > 0) {
                LOGGER.debugf("Deleted %d auto-vaulted secret(s) for conversation %s", deleted, sanitize(conversationId));
            }
            return true;
        } catch (Exception e) {
            LOGGER.warnf("Failed to delete auto-vaulted secrets for conversation %s; keeping the conversation for a retry: %s",
                    sanitize(conversationId), e.getMessage());
            return false;
        }
    }

    /**
     * Deletes the conversation's memory checkpoints; best effort. The tool loop
     * writes one before every tool call, each a full copy of the conversation's
     * memory — left behind, a deleted conversation would live on in them.
     */
    public void deleteCheckpoints(String conversationId) {
        if (checkpointStore != null && checkpointStore.isResolvable()) {
            try {
                long deleted = checkpointStore.get().deleteByConversationId(conversationId);
                if (deleted > 0) {
                    LOGGER.debugf("Deleted %d memory checkpoints for conversation %s", deleted, sanitize(conversationId));
                }
            } catch (Exception e) {
                LOGGER.warnf("Failed to delete memory checkpoints for conversation %s: %s", sanitize(conversationId), sanitize(e.getMessage()));
            }
        }
    }

    /**
     * Deletes the conversation's HITL tool-journal entries (the record of each
     * gated tool call's execution); best effort, as GDPR erasure does.
     */
    public void deleteToolJournal(String conversationId) {
        if (toolJournalStore != null && toolJournalStore.isResolvable()) {
            try {
                long deleted = toolJournalStore.get().deleteByConversationId(conversationId);
                if (deleted > 0) {
                    LOGGER.debugf("Deleted %d HITL tool-journal entries for conversation %s", deleted, sanitize(conversationId));
                }
            } catch (Exception e) {
                LOGGER.warnf("Failed to delete HITL tool-journal entries for conversation %s: %s", sanitize(conversationId),
                        sanitize(e.getMessage()));
            }
        }
    }

    /** Deletes the conversation's stored attachments; best effort. */
    public void deleteAttachments(String conversationId) {
        if (attachmentStore != null && attachmentStore.isResolvable()) {
            try {
                long deleted = attachmentStore.get().deleteByConversation(conversationId);
                if (deleted > 0) {
                    LOGGER.debugf("Deleted %d attachments for conversation %s", deleted, sanitize(conversationId));
                }
            } catch (Exception e) {
                LOGGER.warnf("Failed to delete attachments for conversation %s: %s", sanitize(conversationId), sanitize(e.getMessage()));
            }
        }
    }
}
