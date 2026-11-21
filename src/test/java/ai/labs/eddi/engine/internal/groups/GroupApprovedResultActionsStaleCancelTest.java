/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal.groups;

import ai.labs.eddi.configs.apicalls.IApiCallsStore;
import ai.labs.eddi.configs.apicalls.model.ApiCall;
import ai.labs.eddi.configs.apicalls.model.ApiCallsConfiguration;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.ApprovedAction;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.ApprovedActionType;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.ContextScope;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.DiscussionPhase;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.OnApprovedConfig;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.PhaseType;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.TurnOrder;
import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.configs.groups.model.GroupConversation.GroupConversationState;
import ai.labs.eddi.configs.groups.model.SharedTaskList;
import ai.labs.eddi.configs.groups.model.SharedTaskList.ApprovedActionStatus;
import ai.labs.eddi.configs.groups.model.SharedTaskList.TaskItem;
import ai.labs.eddi.configs.groups.mongo.GroupConversationStore;
import ai.labs.eddi.configs.groups.validation.ApprovedResultActionValidation;
import ai.labs.eddi.datastore.IResourceStorage;
import ai.labs.eddi.datastore.IResourceStorageFactory;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.serialization.IDocumentBuilder;
import ai.labs.eddi.datastore.serialization.JsonSerialization;
import ai.labs.eddi.datastore.serialization.SerializationCustomizer;
import ai.labs.eddi.modules.apicalls.impl.IApiCallExecutor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * A cancel decided on another pod against a document read <em>before</em> the
 * running leg's claim must not overwrite that claim (finding: the cancel's
 * state-and-pause compare-and-set still matched, its stale PENDING record was
 * settled NOT_SENT, and the request the leg then sent was recorded as never
 * sent). The order under test: cancel read → claim write → cancel write → send.
 * <p>
 * The storage is an in-memory document with the compare-and-set semantics both
 * backends implement — each write replaces the whole JSON document only when
 * every expected field (a dotted path; {@code null} = absent) matches the
 * stored one.
 */
@SuppressWarnings("unchecked")
class GroupApprovedResultActionsStaleCancelTest {

    private static final String URI = "eddi://ai.labs.apicalls/apicallstore/apicalls/6740a1b2c3d4e5f6a7b8c9d0?version=2";
    private static final String CALL = "postComment";

    private final JsonSerialization json = new JsonSerialization(SerializationCustomizer.configureObjectMapper(new ObjectMapper(), false));
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicReference<String> stored = new AtomicReference<>();

    private GroupConversationStore store;
    private IApiCallExecutor executor;
    private GroupApprovedResultActions actions;

    @BeforeEach
    void setUp() throws Exception {
        IResourceStorage<GroupConversation> storage = mock(IResourceStorage.class);
        IResourceStorageFactory factory = mock(IResourceStorageFactory.class);
        IDocumentBuilder documentBuilder = mock(IDocumentBuilder.class);
        when(factory.create(eq("groupconversations"), eq(documentBuilder), eq(GroupConversation.class), eq("groupId"), eq("state")))
                .thenReturn(storage);
        // newResource snapshots the document as it is at write time
        when(storage.newResource(anyString(), anyInt(), any(GroupConversation.class)))
                .thenAnswer(inv -> resource(json.serialize(inv.getArgument(2))));
        when(storage.read(eq("gc-1"), eq(1))).thenAnswer(inv -> resource(stored.get()));
        doAnswer(inv -> {
            Map<String, String> expected = new LinkedHashMap<>();
            expected.put(inv.getArgument(1), inv.getArgument(2));
            replaceIfMatches(inv.getArgument(0), expected);
            return null;
        }).when(storage).storeIfFieldEquals(any(), anyString(), nullable(String.class));
        doAnswer(inv -> {
            replaceIfMatches(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(storage).storeIfFieldsEqual(any(), anyMap());
        store = new GroupConversationStore(factory, documentBuilder);

        IApiCallsStore apiCallsStore = mock(IApiCallsStore.class);
        var call = new ApiCall();
        call.setName(CALL);
        var httpCalls = new ApiCallsConfiguration();
        httpCalls.setTargetServerUrl("https://api.github.com");
        httpCalls.setHttpCalls(List.of(call));
        when(apiCallsStore.read("6740a1b2c3d4e5f6a7b8c9d0", 2)).thenReturn(httpCalls);
        ApprovedResultActionValidation validation = mock(ApprovedResultActionValidation.class);
        when(validation.referenceProblems(anyString(), any())).thenReturn(List.of());
        executor = mock(IApiCallExecutor.class);
        actions = new GroupApprovedResultActions(apiCallsStore, executor, validation, null, new SimpleMeterRegistry());
    }

    private IResourceStorage.IResource<GroupConversation> resource(String document) throws Exception {
        IResourceStorage.IResource<GroupConversation> resource = mock(IResourceStorage.IResource.class);
        when(resource.getId()).thenReturn("gc-1");
        when(resource.getData()).thenAnswer(inv -> json.deserialize(document, GroupConversation.class));
        when(resource.toString()).thenReturn(document);
        return resource;
    }

    /** One atomic compare-and-replace, as replaceOne / UPDATE … WHERE do it. */
    private synchronized void replaceIfMatches(IResourceStorage.IResource<GroupConversation> resource, Map<String, String> expected)
            throws Exception {
        JsonNode current = mapper.readTree(stored.get());
        for (var field : expected.entrySet()) {
            JsonNode node = current;
            for (String segment : field.getKey().split("\\.")) {
                node = node == null ? null : node.get(segment);
            }
            String actual = node == null || node.isNull() ? null : node.asText();
            if (field.getValue() == null ? actual != null : !field.getValue().equals(actual)) {
                throw new IResourceStore.ResourceModifiedException("field " + field.getKey() + " was " + actual);
            }
        }
        stored.set(resource.toString());
    }

    private GroupConversation readStored() throws Exception {
        return json.deserialize(stored.get(), GroupConversation.class);
    }

    /**
     * What {@code GroupHitlCoordinator.cancelStored} writes for a running
     * discussion.
     */
    private static void cancelLikeAnotherPod(GroupConversation readBeforeTheClaim, IGroupConversationStoreWriter writer) throws Exception {
        readBeforeTheClaim.setState(GroupConversationState.CANCELLED);
        readBeforeTheClaim.setPausedAt(null);
        writer.write(readBeforeTheClaim);
    }

    @FunctionalInterface
    private interface IGroupConversationStoreWriter {
        void write(GroupConversation gc) throws Exception;
    }

    @Test
    void aCancelReadBeforeTheClaim_cannotOverwriteIt_andTheSentRequestIsRecordedAsSent() throws Exception {
        var task = new TaskItem("Reply", "reply to the issue", 0);
        var gc = new GroupConversation();
        gc.setId("gc-1");
        gc.setGroupId("group-1");
        gc.setUserId("user-1");
        gc.setState(GroupConversationState.IN_PROGRESS);
        var list = new SharedTaskList();
        list.addTask(task);
        list.assignTask(task.id(), "writer", "Writer");
        list.startTask(task.id());
        list.submitForApproval(task.id(), "draft");
        list.approveTask(task.id());
        gc.setTaskList(list);
        var phase = new DiscussionPhase("Draft", PhaseType.EXECUTE, "ALL", TurnOrder.SEQUENTIAL, ContextScope.FULL, false, null, 1, true,
                null, false, null, null, new OnApprovedConfig(new ApprovedAction(ApprovedActionType.HTTPCALL, URI, CALL), null));
        actions.recordApprovals(gc, phase, 0, Set.of(task.id()), "alice");
        stored.set(json.serialize(gc)); // the resumed leg's document: IN_PROGRESS, PENDING

        // 1. another pod's cancel reads the running discussion — the record is PENDING
        GroupConversation cancelsCopy = store.read("gc-1");
        assertEquals(ApprovedActionStatus.PENDING, cancelsCopy.getTaskList().getApprovedActions().get(task.id()).status());
        AtomicReference<Exception> cancelOutcome = new AtomicReference<>();
        when(executor.execute(any(), any(), anyMap(), anyString())).thenAnswer(inv -> {
            // 2. the claim has been written (the request is sent only after it) …
            assertEquals(ApprovedActionStatus.RUNNING, readStored().getTaskList().getApprovedActions().get(task.id()).status());
            // 3. … and now the cancel writes its stale copy
            try {
                cancelLikeAnotherPod(cancelsCopy, copy -> store.updateIfStateAndPause(copy, GroupConversationState.IN_PROGRESS, null));
            } catch (Exception e) {
                cancelOutcome.set(e);
            }
            // 4. the request is sent and accepted
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("httpCode", 201);
            result.put("body", Map.of("html_url", "https://github.com/acme/repo/issues/7#issuecomment-1"));
            return result;
        });

        actions.runOwed(gc, store, () -> false);

        assertInstanceOf(IResourceStore.ResourceModifiedException.class, cancelOutcome.get(),
                "the stale cancel loses its compare-and-set (a 409 to its caller) instead of overwriting the claim");
        GroupConversation after = readStored();
        assertEquals(GroupConversationState.IN_PROGRESS, after.getState());
        var record = after.getTaskList().getApprovedActions().get(task.id());
        assertEquals(ApprovedActionStatus.SUCCEEDED, record.status(), "a request that was sent is never recorded as NOT_SENT");
        assertEquals(201, record.httpCode());
        verify(executor, times(1)).execute(any(), any(), anyMap(), anyString());
    }

    @Test
    void aCancelReadAfterTheClaim_stillLands_andReadsTheClaimAsOutcomeUnknown() throws Exception {
        var task = new TaskItem("Reply", "reply to the issue", 0);
        var gc = new GroupConversation();
        gc.setId("gc-1");
        gc.setGroupId("group-1");
        gc.setState(GroupConversationState.IN_PROGRESS);
        var list = new SharedTaskList();
        list.addTask(task);
        list.assignTask(task.id(), "writer", "Writer");
        list.startTask(task.id());
        list.submitForApproval(task.id(), "draft");
        list.approveTask(task.id());
        gc.setTaskList(list);
        var phase = new DiscussionPhase("Draft", PhaseType.EXECUTE, "ALL", TurnOrder.SEQUENTIAL, ContextScope.FULL, false, null, 1, true,
                null, false, null, null, new OnApprovedConfig(new ApprovedAction(ApprovedActionType.HTTPCALL, URI, CALL), null));
        actions.recordApprovals(gc, phase, 0, Set.of(task.id()), "alice");
        stored.set(json.serialize(gc));
        when(executor.execute(any(), any(), anyMap(), anyString())).thenAnswer(inv -> {
            // a cancel that read the claimed document is current: it is not refused
            GroupConversation fresh = store.read("gc-1");
            cancelLikeAnotherPod(fresh, copy -> store.updateIfStateAndPause(copy, GroupConversationState.IN_PROGRESS, null));
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("httpCode", 201);
            return result;
        });

        actions.runOwed(gc, store, () -> false);

        GroupConversation after = readStored();
        assertEquals(GroupConversationState.CANCELLED, after.getState());
        assertEquals(ApprovedActionStatus.OUTCOME_UNKNOWN, after.getTaskList().getApprovedActions().get(task.id()).status(),
                "the call was in flight when the cancel landed: its outcome is unknown, not NOT_SENT");
        assertEquals(SharedTaskList.TaskStatus.FAILED, after.getTaskList().findById(task.id()).status(),
                "OUTCOME_UNKNOWN applies FAIL_TASK, the default policy");
    }
}
