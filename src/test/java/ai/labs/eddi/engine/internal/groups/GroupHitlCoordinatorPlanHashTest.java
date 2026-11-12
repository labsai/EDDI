/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal.groups;

import ai.labs.eddi.configs.groups.IAgentGroupStore;
import ai.labs.eddi.configs.groups.IGroupConversationStore;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration;
import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.configs.groups.model.GroupConversation.GroupConversationState;
import ai.labs.eddi.configs.groups.model.GroupConversation.HitlPauseType;
import ai.labs.eddi.configs.groups.model.GroupConversation.TranscriptEntry;
import ai.labs.eddi.configs.groups.model.GroupConversation.TranscriptEntryType;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.api.IGroupConversationService;
import ai.labs.eddi.engine.audit.AuditLedgerService;
import ai.labs.eddi.engine.hitl.HitlPlanBinding;
import ai.labs.eddi.engine.hitl.HitlPlanHash;
import ai.labs.eddi.engine.internal.GroupApprovalRequest;
import ai.labs.eddi.engine.internal.GroupConversationService;
import ai.labs.eddi.engine.lifecycle.model.HitlDecision;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.engine.security.CallerIdentityContext;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Plan binding on the group approve
 * ({@link GroupHitlCoordinator#resumeDiscussion}): the decision's
 * {@code planHash} is checked against the plan derived from the stored
 * discussion before anything is mutated.
 */
@DisplayName("Group approve — plan-hash binding")
class GroupHitlCoordinatorPlanHashTest {

    private static final String GROUP_ID = "group-1";
    private static final String GC_ID = "gc-1";

    private IGroupConversationStore conversationStore;
    private IAgentGroupStore groupStore;
    private GroupConversationService groupConversationService;
    private GroupHitlCoordinator coordinator;
    private GroupConversation gc;

    @BeforeEach
    void setUp() throws Exception {
        conversationStore = mock(IGroupConversationStore.class);
        groupStore = mock(IAgentGroupStore.class);
        groupConversationService = mock(GroupConversationService.class);
        coordinator = new GroupHitlCoordinator(groupStore, conversationStore, mock(IScheduleStore.class), mock(AuditLedgerService.class),
                new GroupSigningGuard(null, null, null, "default"), new ConcurrentHashMap<>(), mock(ExecutorService.class),
                new CallerIdentityContext(null, null), groupConversationService, new SimpleMeterRegistry().counter("p"),
                new SimpleMeterRegistry().counter("r"), new SimpleMeterRegistry().counter("f"));
        gc = new GroupConversation();
        gc.setId(GC_ID);
        gc.setGroupId(GROUP_ID);
        gc.setUserId("user-1");
        gc.setState(GroupConversationState.AWAITING_APPROVAL);
        gc.setSchemaVersion(GroupConversation.CURRENT_SCHEMA_VERSION);
        gc.setPausedAt(Instant.ofEpochMilli(5_000L));
        gc.setPausedAtPhaseIndex(0);
        gc.setPausedPhaseName("Opinion");
        gc.setHitlPauseType(HitlPauseType.PHASE);
        gc.getTranscript().add(new TranscriptEntry("a", "A", "ship it", 0, "Opinion", TranscriptEntryType.OPINION, Instant.EPOCH, null, null));
        when(conversationStore.read(GC_ID)).thenReturn(gc);
        // Past every plan check the approve reaches its pause-bound CAS; refusing it
        // there stops the test before the resume is scheduled.
        doThrow(new IResourceStore.ResourceModifiedException("stop here")).when(conversationStore)
                .updateIfStateAndPause(any(), eq(GroupConversationState.AWAITING_APPROVAL), any());
    }

    private static GroupApprovalRequest approve(String planHash) {
        var decision = new HitlDecision();
        decision.setVerdict(HitlDecision.HitlVerdict.APPROVED);
        decision.setDecidedBy("reviewer");
        decision.setPlanHash(planHash);
        var request = new GroupApprovalRequest();
        request.setDecision(decision);
        return request;
    }

    private void requirePlanHashOnTheGroup() throws Exception {
        var resourceId = mock(IResourceStore.IResourceId.class);
        when(resourceId.getVersion()).thenReturn(1);
        when(groupStore.getCurrentResourceId(GROUP_ID)).thenReturn(resourceId);
        var config = new AgentGroupConfiguration();
        var hitl = new AgentGroupConfiguration.HitlConfig();
        hitl.setRequirePlanHash(true);
        config.setHitlConfig(hitl);
        when(groupStore.read(GROUP_ID, 1)).thenReturn(config);
    }

    @Test
    @DisplayName("the current plan's hash passes the check, and the binding is recorded as MATCHED")
    void matching() {
        // Taken before the approve: it clears the pause bookmark on the way to its CAS.
        String plan = HitlPlanHash.forGroup(gc);
        var request = approve(plan);

        assertThrows(IResourceStore.ResourceModifiedException.class, () -> coordinator.resumeDiscussion(GC_ID, request, null));

        assertEquals(HitlPlanBinding.Outcome.MATCHED.name(), request.getDecision().getPlanBinding());
        assertEquals(plan, request.getDecision().getCommittedPlanHash());
    }

    @Test
    @DisplayName("a hash of different work: refused before anything is mutated")
    void mismatch() throws Exception {
        String reviewed = HitlPlanHash.forGroup(gc);
        gc.getTranscript().set(0, new TranscriptEntry("a", "A", "delete it", 0, "Opinion", TranscriptEntryType.OPINION, Instant.EPOCH, null,
                null));

        assertThrows(IGroupConversationService.GroupPlanMismatchException.class,
                () -> coordinator.resumeDiscussion(GC_ID, approve(reviewed), null));

        assertEquals(GroupConversationState.AWAITING_APPROVAL, gc.getState());
        verify(conversationStore, never()).updateIfStateAndPause(any(), any(), any());
        verify(conversationStore, never()).updateIfState(any(), any());
    }

    @Test
    @DisplayName("no hash, group not strict: accepted as NOT_PRESENTED")
    void notPresented() {
        var request = approve(null);

        assertThrows(IResourceStore.ResourceModifiedException.class, () -> coordinator.resumeDiscussion(GC_ID, request, null));

        assertEquals(HitlPlanBinding.Outcome.NOT_PRESENTED.name(), request.getDecision().getPlanBinding());
    }

    @Test
    @DisplayName("no hash, group requires one (hitlConfig.requirePlanHash): a 400 before anything is mutated")
    void strictGroup() throws Exception {
        requirePlanHashOnTheGroup();

        assertThrows(HitlPlanBinding.PlanHashRequired.class, () -> coordinator.resumeDiscussion(GC_ID, approve(null), null));

        verify(conversationStore, never()).updateIfStateAndPause(any(), any(), any());
    }

    @Test
    @DisplayName("no hash, deployment strict (eddi.hitl.plan-hash.strict): refused")
    void strictDeployment() throws Exception {
        when(groupConversationService.planHashStrict()).thenReturn(true);

        assertThrows(HitlPlanBinding.PlanHashRequired.class, () -> coordinator.resumeDiscussion(GC_ID, approve(null), null));

        verify(conversationStore, never()).updateIfStateAndPause(any(), any(), any());
    }

    @Test
    @DisplayName("strict, but an automated decision: not refused for the missing hash")
    void systemDecision() throws Exception {
        requirePlanHashOnTheGroup();
        var request = approve(null);
        request.getDecision().setDecidedBy("system:timeout");

        assertThrows(IResourceStore.ResourceModifiedException.class, () -> coordinator.resumeDiscussion(GC_ID, request, null));

        assertEquals(HitlPlanBinding.Outcome.SYSTEM.name(), request.getDecision().getPlanBinding());
    }
}
