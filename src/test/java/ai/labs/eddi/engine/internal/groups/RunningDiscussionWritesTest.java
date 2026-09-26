/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal.groups;

import ai.labs.eddi.configs.groups.IGroupConversationStore;
import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.configs.groups.model.GroupConversation.GroupConversationState;
import ai.labs.eddi.datastore.IResourceStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * H14a — the running-leg write is a CAS on "still running", accepting either
 * running state.
 */
class RunningDiscussionWritesTest {

    private IGroupConversationStore store;
    private GroupConversation gc;

    @BeforeEach
    void setUp() {
        store = mock(IGroupConversationStore.class);
        gc = new GroupConversation();
        gc.setId("gc-1");
    }

    @Test
    @DisplayName("a persisted IN_PROGRESS accepts the write on the first attempt")
    void inProgress_writesOnce() throws Exception {
        gc.setState(GroupConversationState.AWAITING_APPROVAL);

        RunningDiscussionWrites.updateWhileRunning(store, gc);

        verify(store).updateIfState(gc, GroupConversationState.IN_PROGRESS);
        verify(store, never()).updateIfState(gc, GroupConversationState.SYNTHESIZING);
        verify(store, never()).update(any());
    }

    @Test
    @DisplayName("an in-memory SYNTHESIZING not yet persisted still writes over the persisted IN_PROGRESS")
    void inMemorySynthesizing_fallsBackToInProgress() throws Exception {
        gc.setState(GroupConversationState.SYNTHESIZING);
        doThrow(new IResourceStore.ResourceModifiedException("persisted is IN_PROGRESS"))
                .when(store).updateIfState(gc, GroupConversationState.SYNTHESIZING);

        RunningDiscussionWrites.updateWhileRunning(store, gc);

        var order = inOrder(store);
        order.verify(store).updateIfState(gc, GroupConversationState.SYNTHESIZING);
        order.verify(store).updateIfState(gc, GroupConversationState.IN_PROGRESS);
    }

    @Test
    @DisplayName("a persisted terminal state rejects both attempts — the leg is superseded, nothing written")
    void terminalPersisted_superseded() throws Exception {
        gc.setState(GroupConversationState.IN_PROGRESS);
        doThrow(new IResourceStore.ResourceModifiedException("persisted is CANCELLED"))
                .when(store).updateIfState(eq(gc), any());

        assertThrows(RunningDiscussionWrites.DiscussionSupersededException.class,
                () -> RunningDiscussionWrites.updateWhileRunning(store, gc));

        verify(store, never()).update(any());
    }

    @Test
    @DisplayName("a deleted document surfaces as the store's Gone exception, not as superseded")
    void deleted_isGone() throws Exception {
        gc.setState(GroupConversationState.IN_PROGRESS);
        doThrow(new IGroupConversationStore.GroupConversationGoneException("gone", null))
                .when(store).updateIfState(eq(gc), any());

        assertThrows(IGroupConversationStore.GroupConversationGoneException.class,
                () -> RunningDiscussionWrites.updateWhileRunning(store, gc));
        verify(store, never()).update(any());
    }
}
