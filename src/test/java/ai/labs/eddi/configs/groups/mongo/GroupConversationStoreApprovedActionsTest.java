/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.groups.mongo;

import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.ApprovedActionFailurePolicy;
import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.configs.groups.model.GroupConversation.GroupConversationState;
import ai.labs.eddi.configs.groups.model.SharedTaskList;
import ai.labs.eddi.configs.groups.model.SharedTaskList.ApprovedActionRecord;
import ai.labs.eddi.configs.groups.model.SharedTaskList.ApprovedActionStatus;
import ai.labs.eddi.datastore.IResourceStorage;
import ai.labs.eddi.datastore.IResourceStorageFactory;
import ai.labs.eddi.datastore.serialization.IDocumentBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * An approved-result action still owed when its discussion ends is settled —
 * visibly, and without being sent — by the store, on every read and write of
 * the ended document (finding: a crash between the approval and the send left
 * the record PENDING forever on a dead or cancelled discussion).
 */
@SuppressWarnings("unchecked")
class GroupConversationStoreApprovedActionsTest {

    private IResourceStorage<GroupConversation> storage;
    private GroupConversationStore store;

    @BeforeEach
    void setUp() {
        IResourceStorageFactory storageFactory = mock(IResourceStorageFactory.class);
        IDocumentBuilder documentBuilder = mock(IDocumentBuilder.class);
        storage = mock(IResourceStorage.class);
        when(storageFactory.create(eq("groupconversations"), eq(documentBuilder), eq(GroupConversation.class), eq("groupId"), eq("state")))
                .thenReturn(storage);
        store = new GroupConversationStore(storageFactory, documentBuilder);
    }

    private static final Instant APPROVED_AT = Instant.parse("2026-11-01T10:00:00Z");
    private static final Instant ENDED_AT = Instant.parse("2026-11-01T11:00:00Z");

    private static ApprovedActionRecord record(ApprovedActionStatus status) {
        return new ApprovedActionRecord(status, "Draft", 0, "eddi://ai.labs.apicalls/apicallstore/apicalls/x?version=1#post",
                ApprovedActionFailurePolicy.FAIL_TASK, "alice", false, "key", APPROVED_AT, null, null, null, null, null);
    }

    /** Every read deserializes a fresh document, as the database does. */
    private void storedAs(Supplier<GroupConversation> document) throws Exception {
        when(storage.read("gc-1", 1)).thenAnswer(inv -> {
            IResourceStorage.IResource<GroupConversation> resource = mock(IResourceStorage.IResource.class);
            when(resource.getData()).thenReturn(document.get());
            return resource;
        });
    }

    private static GroupConversation gcWith(GroupConversationState state) {
        var gc = new GroupConversation();
        gc.setId("gc-1");
        gc.setState(state);
        var list = new SharedTaskList();
        list.setApprovedActions(Map.of("pending", record(ApprovedActionStatus.PENDING), "running", record(ApprovedActionStatus.RUNNING),
                "done", record(ApprovedActionStatus.SUCCEEDED)));
        gc.setTaskList(list);
        return gc;
    }

    private GroupConversation readBack(GroupConversation stored) throws Exception {
        IResourceStorage.IResource<GroupConversation> resource = mock(IResourceStorage.IResource.class);
        when(resource.getData()).thenReturn(stored);
        when(storage.read("gc-1", 1)).thenReturn(resource);
        return store.read("gc-1");
    }

    @ParameterizedTest
    @EnumSource(value = GroupConversationState.class, names = {"COMPLETED", "FAILED", "REJECTED", "CANCELLED", "CLOSED"})
    void anEndedDiscussion_readsItsOwedActionsAsNotSent(GroupConversationState ended) throws Exception {
        GroupConversation gc = readBack(gcWith(ended));

        Map<String, ApprovedActionRecord> actions = gc.getTaskList().getApprovedActions();
        assertEquals(ApprovedActionStatus.NOT_SENT, actions.get("pending").status());
        assertTrue(actions.get("pending").summary().contains(ended.name()), actions.get("pending").summary());
        assertNotNull(actions.get("pending").finishedAt());
        assertEquals(ApprovedActionStatus.OUTCOME_UNKNOWN, actions.get("running").status());
        assertEquals(ApprovedActionStatus.SUCCEEDED, actions.get("done").status(), "a settled record is left alone");
    }

    @ParameterizedTest
    @EnumSource(value = GroupConversationState.class, names = {"CREATED", "IN_PROGRESS", "SYNTHESIZING", "AWAITING_APPROVAL",
            "AWAITING_HUMAN_INPUT"})
    void aLiveDiscussion_keepsWhatItOwes(GroupConversationState live) throws Exception {
        GroupConversation gc = readBack(gcWith(live));

        assertEquals(ApprovedActionStatus.PENDING, gc.getTaskList().getApprovedActions().get("pending").status());
        assertEquals(ApprovedActionStatus.RUNNING, gc.getTaskList().getApprovedActions().get("running").status());
    }

    @Test
    void theCancelWrite_persistsTheSettledRecord() throws Exception {
        var gc = gcWith(GroupConversationState.CANCELLED);
        AtomicReference<ApprovedActionStatus> written = new AtomicReference<>();
        when(storage.newResource(eq("gc-1"), eq(1), same(gc))).thenAnswer(inv -> {
            written.set(gc.getTaskList().getApprovedActions().get("pending").status());
            return mock(IResourceStorage.IResource.class);
        });

        store.updateIfStateAndPause(gc, GroupConversationState.AWAITING_APPROVAL, "pause-1");

        assertEquals(ApprovedActionStatus.NOT_SENT, written.get(), "settled before the document is written, not after");
    }

    @Test
    void aTerminalWriteThroughUpdateIfState_settlesToo() throws Exception {
        var gc = gcWith(GroupConversationState.COMPLETED);
        when(storage.newResource(eq("gc-1"), eq(1), same(gc))).thenReturn(mock(IResourceStorage.IResource.class));

        store.updateIfState(gc, GroupConversationState.IN_PROGRESS);

        assertEquals(ApprovedActionStatus.NOT_SENT, gc.getTaskList().getApprovedActions().get("pending").status());
    }

    @Test
    void aWriteWhileRunning_leavesTheOwedActionToTheLeg() throws Exception {
        var gc = gcWith(GroupConversationState.IN_PROGRESS);
        when(storage.newResource(eq("gc-1"), eq(1), same(gc))).thenReturn(mock(IResourceStorage.IResource.class));

        store.update(gc);

        assertEquals(ApprovedActionStatus.PENDING, gc.getTaskList().getApprovedActions().get("pending").status());
    }

    @Test
    void twoReadsOfAnEndedDiscussion_showTheSameFinishTime_theDiscussionsEnd() throws Exception {
        storedAs(() -> {
            var gc = gcWith(GroupConversationState.CANCELLED);
            gc.setLastModified(ENDED_AT);
            return gc;
        });

        ApprovedActionRecord first = store.read("gc-1").getTaskList().getApprovedActions().get("pending");
        ApprovedActionRecord second = store.read("gc-1").getTaskList().getApprovedActions().get("pending");

        assertEquals(ApprovedActionStatus.NOT_SENT, first.status());
        assertEquals(ENDED_AT, first.finishedAt(), "stamped with the discussion's end, not the time of the read");
        assertEquals(first.finishedAt(), second.finishedAt());
        assertEquals(ENDED_AT, store.read("gc-1").getTaskList().getApprovedActions().get("running").finishedAt());
    }

    @Test
    void anEndedDiscussionWithoutLastModified_fallsBackToTheRecordsOwnTime() throws Exception {
        storedAs(() -> gcWith(GroupConversationState.COMPLETED));

        ApprovedActionRecord first = store.read("gc-1").getTaskList().getApprovedActions().get("pending");
        ApprovedActionRecord second = store.read("gc-1").getTaskList().getApprovedActions().get("pending");

        assertEquals(APPROVED_AT, first.finishedAt());
        assertEquals(first.finishedAt(), second.finishedAt());
    }

    @Test
    void theWritePersistsTheSettledRecord_soTheNextReadHasNothingLeftToSettle() throws Exception {
        var gc = gcWith(GroupConversationState.CANCELLED);
        gc.setLastModified(ENDED_AT);
        when(storage.newResource(eq("gc-1"), eq(1), same(gc))).thenReturn(mock(IResourceStorage.IResource.class));

        store.update(gc);

        assertTrue(gc.getTaskList().settleOwedApprovedActions("unused", Instant.now()).isEmpty(),
                "the document as written owes nothing, so a later read has nothing left to settle");
        // the document as written is what the next read deserializes
        storedAs(() -> gc);
        GroupConversation readBack = store.read("gc-1");
        assertEquals(ApprovedActionStatus.NOT_SENT, readBack.getTaskList().getApprovedActions().get("pending").status());
        assertEquals(ENDED_AT, readBack.getTaskList().getApprovedActions().get("pending").finishedAt());
    }

    @Test
    void notSent_allowsANewAttempt_butSucceededAndUnknownDoNot() {
        assertTrue(ApprovedActionStatus.NOT_SENT.allowsNewAttempt());
        assertTrue(ApprovedActionStatus.FAILED.allowsNewAttempt());
        assertTrue(ApprovedActionStatus.SKIPPED.allowsNewAttempt());
        assertFalse(ApprovedActionStatus.SUCCEEDED.allowsNewAttempt());
        assertFalse(ApprovedActionStatus.OUTCOME_UNKNOWN.allowsNewAttempt());
        assertFalse(ApprovedActionStatus.PENDING.allowsNewAttempt());
        assertFalse(ApprovedActionStatus.RUNNING.allowsNewAttempt());
    }
}
