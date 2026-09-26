/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal.groups;

import ai.labs.eddi.configs.groups.IGroupConversationStore;
import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.configs.groups.model.GroupConversation.GroupConversationState;
import ai.labs.eddi.datastore.IResourceStore;

/**
 * The one way a <em>running</em> discussion leg writes its document (H14a).
 * <p>
 * A leg persists by whole-document replace from its in-memory copy: after every
 * phase, when it pauses, when it records a cancel, when a facilitator diverges
 * the phase list. Those writes used to be unconditional. Group control is
 * per-pod — the cancel token lives in the process that runs the leg — so a
 * cancel landing on another pod can only flip the persisted state
 * ({@code IN_PROGRESS → CANCELLED}) and report success. The running pod's next
 * unconditional write then put {@code IN_PROGRESS} back, and its final
 * completion CAS committed {@code COMPLETED} over a cancel the caller had been
 * told succeeded. The phase-boundary re-read narrowed that window without
 * closing it.
 * <p>
 * Every such write is now a compare-and-swap on the persisted state still being
 * a running one. A leg may hold either running state: it flips to
 * {@code SYNTHESIZING} in memory before a synthesis phase and only persists the
 * flip afterwards, so the in-memory value is a hint for which to try first, not
 * the expectation itself. Anything else persisted — a terminal state another
 * writer committed, or a pause — means this leg no longer owns the document and
 * must stop without writing: {@link DiscussionSupersededException}. A document
 * that is gone surfaces as the store's own
 * {@link IGroupConversationStore.GroupConversationGoneException}.
 */
public final class RunningDiscussionWrites {

    private RunningDiscussionWrites() {
    }

    /**
     * Replaces the document with {@code gc} only if the persisted state is still
     * {@code IN_PROGRESS} or {@code SYNTHESIZING}. {@code gc} may carry any state,
     * including the one this write is meant to commit ({@code CANCELLED}, a pause).
     *
     * @throws DiscussionSupersededException
     *             the persisted state is no longer a running one — another writer
     *             owns the outcome
     * @throws IGroupConversationStore.GroupConversationGoneException
     *             the document was deleted while the leg ran
     */
    public static void updateWhileRunning(IGroupConversationStore store, GroupConversation gc)
            throws IResourceStore.ResourceStoreException {
        GroupConversationState first = gc.getState() == GroupConversationState.SYNTHESIZING
                ? GroupConversationState.SYNTHESIZING
                : GroupConversationState.IN_PROGRESS;
        GroupConversationState second = first == GroupConversationState.IN_PROGRESS
                ? GroupConversationState.SYNTHESIZING
                : GroupConversationState.IN_PROGRESS;
        try {
            store.updateIfState(gc, first);
            return;
        } catch (IResourceStore.ResourceModifiedException e) {
            // Either the other running state is persisted, or the leg lost the
            // document to another writer — the second attempt tells them apart.
        }
        try {
            store.updateIfState(gc, second);
        } catch (IResourceStore.ResourceModifiedException e) {
            throw new DiscussionSupersededException(gc.getId(), e);
        }
    }

    /**
     * The persisted state of a discussion left the running states while a leg was
     * still executing it: a cancel, close, abort or failure committed elsewhere.
     * Unchecked, so it travels out of the helpers the loop calls (pause commits,
     * phase-list persists) to {@code executeDiscussion}, which adopts the persisted
     * outcome instead of overwriting it.
     */
    public static final class DiscussionSupersededException extends RuntimeException {
        public DiscussionSupersededException(String groupConversationId, Throwable cause) {
            super("Group conversation " + groupConversationId + " is no longer running — another writer owns its state", cause);
        }
    }
}
