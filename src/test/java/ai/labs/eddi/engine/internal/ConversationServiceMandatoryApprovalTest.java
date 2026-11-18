/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.hitl.HitlTimeoutPolicy;
import ai.labs.eddi.configs.hitl.model.ToolApprovalsConfig;
import ai.labs.eddi.configs.properties.IUserMemoryStore;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.audit.AuditLedgerService;
import ai.labs.eddi.engine.caching.ICache;
import ai.labs.eddi.engine.caching.ICacheFactory;
import ai.labs.eddi.engine.gdpr.GdprComplianceService;
import ai.labs.eddi.engine.hitl.HitlPlanHash;
import ai.labs.eddi.engine.hitl.tools.ApproverRule;
import ai.labs.eddi.engine.hitl.tools.MandatoryApprovalAttribution;
import ai.labs.eddi.engine.hitl.tools.MandatoryApprovalAttribution.ActingAs;
import ai.labs.eddi.engine.hitl.tools.MandatoryApprovalRefusedException;
import ai.labs.eddi.engine.hitl.tools.MandatoryApproverCheck;
import ai.labs.eddi.engine.lifecycle.IConversation;
import ai.labs.eddi.engine.lifecycle.model.HitlDecision;
import ai.labs.eddi.engine.lifecycle.model.HitlDecision.HitlVerdict;
import ai.labs.eddi.engine.lifecycle.model.ToolCallDecision;
import ai.labs.eddi.engine.memory.ConversationMemory;
import ai.labs.eddi.engine.memory.ConversationMemoryUtilities;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.memory.descriptor.IConversationDescriptorStore;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot.ConversationStepSnapshot;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot.WorkflowRunSnapshot;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.memory.model.PendingToolCallBatch;
import ai.labs.eddi.engine.model.Deployment.Environment;
import ai.labs.eddi.engine.runtime.IAgent;
import ai.labs.eddi.engine.runtime.IAgentFactory;
import ai.labs.eddi.engine.runtime.IConversationCoordinator;
import ai.labs.eddi.engine.runtime.IConversationSetup;
import ai.labs.eddi.engine.runtime.IRuntime;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.engine.security.CallerIdentity;
import ai.labs.eddi.engine.security.CallerIdentityContext;
import ai.labs.eddi.engine.security.ResolutionPrincipal.Provenance;
import ai.labs.eddi.engine.tenancy.TenantQuotaService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * The resume path of the mandatory approval hook (WP-0.2): a decision that
 * approves a mandatorily gated call from an approver its {@link ApproverRule}
 * does not accept is refused before anything runs, and the pause is put back
 * untouched; an administrator's approval on the user's behalf is logged.
 */
@DisplayName("Resume — mandatory approvals")
class ConversationServiceMandatoryApprovalTest {

    private static final Environment ENV = Environment.production;
    private static final String AGENT_ID = "64b7f0c2a1d3e4f5a6b7c8a0";
    private static final int AGENT_VERSION = 2;
    private static final String CONVERSATION_ID = "64b7f0c2a1d3e4f5a6b7c8a1";
    private static final String USER_ID = "64b7f0c2a1d3e4f5a6b7c8d9";
    private static final String OTHER_ID = "64b7f0c2a1d3e4f5a6b7c8e0";
    private static final String ADMIN_ID = "64b7f0c2a1d3e4f5a6b7c8e1";

    @Mock
    private IAgentFactory agentFactory;
    @Mock
    private IConversationMemoryStore conversationMemoryStore;
    @Mock
    private IConversationDescriptorStore conversationDescriptorStore;
    @Mock
    private IUserMemoryStore userMemoryStore;
    @Mock
    private IConversationCoordinator conversationCoordinator;
    @Mock
    private IConversationSetup conversationSetup;
    @Mock
    private ICacheFactory cacheFactory;
    @Mock
    private IRuntime runtime;
    @Mock
    private IContextLogger contextLogger;
    @Mock
    private AuditLedgerService auditLedgerService;
    @Mock
    private GdprComplianceService gdprComplianceService;
    @Mock
    private TenantQuotaService tenantQuotaService;
    @Mock
    private IScheduleStore scheduleStore;
    @Mock
    private IAgentStore agentStore;
    @Mock
    private IJsonSerialization jsonSerialization;
    @Mock
    private ICache<String, ConversationState> conversationStateCache;

    private final CallerIdentityContext callerContext = new CallerIdentityContext(null, null);
    private ConversationService conversationService;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        MockitoAnnotations.openMocks(this);
        doReturn(conversationStateCache).when(cacheFactory).getCache("conversationState", ConversationService.CONVERSATION_STATE_CACHE_TTL);
        conversationService = new ConversationService(agentFactory, conversationMemoryStore, conversationDescriptorStore, userMemoryStore,
                conversationCoordinator, conversationSetup, cacheFactory, runtime, contextLogger, auditLedgerService, gdprComplianceService,
                tenantQuotaService, scheduleStore, agentStore, jsonSerialization, new SimpleMeterRegistry(),
                ConversationServiceTestFixtures.hitlResumeEvent(), callerContext, 30);
        doReturn(ConversationState.AWAITING_HUMAN).when(conversationMemoryStore).getConversationState(CONVERSATION_ID);
        lenient().when(runtime.submitCallable(any(Callable.class), any(IRuntime.IFinishedExecution.class), any()))
                .thenAnswer(invocation -> CompletableFuture.completedFuture(null));
        lenient().when(conversationMemoryStore.storeConversationMemorySnapshotIfState(any(), any())).thenReturn(true);
        lenient().doReturn(true).when(conversationMemoryStore).compareAndSetState(CONVERSATION_ID, ConversationState.AWAITING_HUMAN,
                ConversationState.IN_PROGRESS);
        lenient().doReturn(true).when(conversationMemoryStore).compareAndSetState(CONVERSATION_ID, ConversationState.IN_PROGRESS,
                ConversationState.AWAITING_HUMAN);
    }

    /**
     * A tool pause whose one call a rule gated for the conversation user's consent.
     */
    private static ConversationMemorySnapshot consentPause(Provenance provenance) {
        var snapshot = new ConversationMemorySnapshot();
        snapshot.setConversationId(CONVERSATION_ID);
        snapshot.setAgentId(AGENT_ID);
        snapshot.setAgentVersion(AGENT_VERSION);
        snapshot.setUserId(USER_ID);
        snapshot.setResolutionProvenance(provenance);
        snapshot.setConversationState(ConversationState.AWAITING_HUMAN);
        snapshot.setEnvironment(ENV);
        snapshot.setHitlPausedWorkflowId("workflow-1");
        snapshot.setHitlPausedAbsoluteTaskIndex(2);
        snapshot.setHitlPausedAt(Instant.ofEpochMilli(1_700_000_000_123L));
        snapshot.setHitlPauseType("TOOL_CALL");
        var step = new ConversationStepSnapshot();
        step.getWorkflows().add(new WorkflowRunSnapshot());
        snapshot.getConversationSteps().add(step);
        var call = new PendingToolCallBatch.PendingToolCall();
        call.setCallId("call-1");
        call.setToolName("sendToPeer");
        call.setSource("a2a");
        call.setArgumentsRaw("{\"file\":\"report.pdf\"}");
        call.setArgumentsRedacted("{\"file\":\"report.pdf\"}");
        call.setMandatoryReasonCode("file-egress");
        call.setMandatoryApprover(ApproverRule.CONVERSATION_USER.name());
        call.setMandatorySummary("Sends 1 file out of this deployment");
        var batch = new PendingToolCallBatch();
        batch.setPauseEpoch("epoch-1");
        batch.setCalls(new ArrayList<>(List.of(call)));
        snapshot.setHitlPendingToolCalls(batch);
        IConversationMemory memory = ConversationMemoryUtilities.convertConversationMemorySnapshot(snapshot);
        String hash = HitlPlanHash.commit(memory);
        snapshot.setHitlPlanHash(hash);
        batch.setPlanHash(hash);
        return snapshot;
    }

    private void deployAgent() throws Exception {
        IAgent agent = mock(IAgent.class);
        doReturn(agent).when(agentFactory).getAgent(ENV, AGENT_ID, AGENT_VERSION);
        doReturn(mock(IConversation.class)).when(agent).continueConversation(any(IConversationMemory.class), any(), any());
    }

    private static HitlDecision approve(String decidedBy, String note) {
        var decision = new HitlDecision();
        decision.setVerdict(HitlVerdict.APPROVED);
        decision.setDecidedBy(decidedBy);
        decision.setNote(note);
        return decision;
    }

    private void resumeAs(CallerIdentity approver, HitlDecision decision) throws Exception {
        callerContext.withIdentity(approver, () -> {
            conversationService.resumeConversation(CONVERSATION_ID, decision, null);
            return null;
        }).call();
    }

    @Test
    @DisplayName("another, non-admin approver: 403-class refusal, pause restored, nothing run")
    void otherApproverRefusedAndRestored() throws Exception {
        doReturn(consentPause(Provenance.VERIFIED)).when(conversationMemoryStore).loadConversationMemorySnapshot(CONVERSATION_ID);
        deployAgent();

        var refused = assertThrows(MandatoryApprovalRefusedException.class,
                () -> resumeAs(new CallerIdentity("t", OTHER_ID, "test"), approve(OTHER_ID, "fine by me")));

        assertEquals(MandatoryApproverCheck.REFUSED_NOT_USER, refused.getMessage());
        verify(conversationMemoryStore).compareAndSetState(CONVERSATION_ID, ConversationState.IN_PROGRESS, ConversationState.AWAITING_HUMAN);
        verify(conversationCoordinator, never()).submitInOrder(any(), any());
    }

    @Test
    @DisplayName("the conversation's verified user: resumed, the decision carries USER")
    void verifiedUserResumes() throws Exception {
        doReturn(consentPause(Provenance.VERIFIED)).when(conversationMemoryStore).loadConversationMemorySnapshot(CONVERSATION_ID);
        deployAgent();
        var decision = approve(USER_ID, null);

        resumeAs(new CallerIdentity("t", USER_ID, "test"), decision);

        verify(conversationCoordinator).submitInOrder(eq(CONVERSATION_ID), any());
        assertEquals(new MandatoryApprovalAttribution(USER_ID, null, ActingAs.USER), decision.getMandatoryApproval());
        // ...and per call, which is what the resumed tool loop executes each call with.
        assertEquals(Map.of("call-1", new MandatoryApprovalAttribution(USER_ID, null, ActingAs.USER)),
                decision.getMandatoryApprovalsByCallId());
    }

    @Test
    @DisplayName("an administrator with a reason, ledger off: resumed, and an INFO line names approvedBy and onBehalfOf")
    void adminOnBehalfIsLogged() throws Exception {
        doReturn(consentPause(Provenance.VERIFIED)).when(conversationMemoryStore).loadConversationMemorySnapshot(CONVERSATION_ID);
        deployAgent();
        var decision = approve(ADMIN_ID, "user confirmed by phone");

        List<String> captured = new ArrayList<>();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                captured.add(String.valueOf(record.getMessage()));
                if (record.getParameters() != null) {
                    for (Object parameter : record.getParameters()) {
                        captured.add(String.valueOf(parameter));
                    }
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        // logging.properties turns ai.labs.eddi OFF for plain unit tests: open it, or
        // nothing is captured and this would pass for the wrong reason.
        Logger julLogger = Logger.getLogger(ConversationHitlService.class.getName());
        Level previous = julLogger.getLevel();
        julLogger.setLevel(Level.ALL);
        julLogger.addHandler(handler);
        try {
            resumeAs(new CallerIdentity("t", ADMIN_ID, "test", Map.of(), true), decision);
        } finally {
            julLogger.removeHandler(handler);
            julLogger.setLevel(previous);
        }

        verify(conversationCoordinator).submitInOrder(eq(CONVERSATION_ID), any());
        assertEquals(new MandatoryApprovalAttribution(ADMIN_ID, USER_ID, ActingAs.ADMIN_ON_BEHALF), decision.getMandatoryApproval());
        String joined = String.join("|", captured);
        assertFalse(captured.isEmpty(), "nothing was captured — the logger was not open");
        assertTrue(joined.contains("hitl.mandatory.admin_on_behalf"), joined);
        assertTrue(joined.contains("approvedBy=" + ADMIN_ID), joined);
        assertTrue(joined.contains("onBehalfOf=" + USER_ID), joined);
        assertTrue(joined.contains("actingAs=ADMIN_ON_BEHALF"), joined);
    }

    @Test
    @DisplayName("an administrator without a reason: refused, pause restored")
    void adminWithoutReasonRefused() throws Exception {
        doReturn(consentPause(Provenance.VERIFIED)).when(conversationMemoryStore).loadConversationMemorySnapshot(CONVERSATION_ID);
        deployAgent();

        assertThrows(MandatoryApprovalRefusedException.class,
                () -> resumeAs(new CallerIdentity("t", ADMIN_ID, "test", Map.of(), true), approve(ADMIN_ID, null)));

        verify(conversationCoordinator, never()).submitInOrder(any(), any());
    }

    @Test
    @DisplayName("a timeout's automatic approval: refused, pause restored")
    void systemApprovalRefused() throws Exception {
        doReturn(consentPause(Provenance.VERIFIED)).when(conversationMemoryStore).loadConversationMemorySnapshot(CONVERSATION_ID);
        deployAgent();

        assertThrows(MandatoryApprovalRefusedException.class,
                () -> conversationService.resumeConversation(CONVERSATION_ID, approve("system:timeout", null), null));

        verify(conversationCoordinator, never()).submitInOrder(any(), any());
    }

    @Test
    @DisplayName("a rejection by anyone resumes")
    void rejectionByAnyone() throws Exception {
        doReturn(consentPause(Provenance.VERIFIED)).when(conversationMemoryStore).loadConversationMemorySnapshot(CONVERSATION_ID);
        deployAgent();
        var decision = new HitlDecision();
        decision.setVerdict(HitlVerdict.REJECTED);
        decision.setDecidedBy(OTHER_ID);

        resumeAs(new CallerIdentity("t", OTHER_ID, "test"), decision);

        verify(conversationCoordinator).submitInOrder(eq(CONVERSATION_ID), any());
        assertNull(decision.getMandatoryApproval());
    }

    @Test
    @DisplayName("amended arguments of the mandatory call: 400 before the pause is consumed")
    void amendmentRefusedBeforeCas() throws Exception {
        doReturn(consentPause(Provenance.VERIFIED)).when(conversationMemoryStore).loadConversationMemorySnapshot(CONVERSATION_ID);
        var decision = approve(USER_ID, null);
        var cd = new ToolCallDecision();
        cd.setVerdict(HitlVerdict.APPROVED);
        cd.setAmendedArguments("{\"file\":\"other.pdf\"}");
        decision.setToolDecisions(Map.of("call-1", cd));

        var refused = assertThrows(IllegalArgumentException.class, () -> resumeAs(new CallerIdentity("t", USER_ID, "test"), decision));

        assertEquals(MandatoryApproverCheck.REFUSED_AMENDMENT, refused.getMessage());
        verify(conversationMemoryStore, never()).compareAndSetState(any(), any(), any());
    }

    @Test
    @DisplayName("an AUTO_APPROVE timeout policy, at any level, waits instead for a batch with a mandatory call")
    void timeoutNeverAutoApproves() {
        var explicit = new ToolApprovalsConfig();
        explicit.setTimeoutPolicy(HitlTimeoutPolicy.AUTO_APPROVE);
        var rule = new ToolApprovalsConfig.ApprovalRule();
        rule.setMatch("sendToPeer");
        rule.setTimeoutPolicy(HitlTimeoutPolicy.AUTO_APPROVE);

        for (ToolApprovalsConfig.ApprovalRule governing : new ToolApprovalsConfig.ApprovalRule[]{null, rule}) {
            var batch = consentPause(Provenance.VERIFIED).getHitlPendingToolCalls();
            batch.setEffectiveToolApprovals(explicit);
            batch.setEffectiveRule(governing);
            IConversationMemory memory = new ConversationMemory(CONVERSATION_ID, AGENT_ID, AGENT_VERSION, USER_ID);
            memory.setHitlPendingToolCalls(batch);
            var hitlConfig = new AgentConfiguration.HitlConfig();
            hitlConfig.setApprovalTimeout("PT1H");

            conversationService.conversationHitlService.applyEffectiveToolTimeoutPolicy(memory, hitlConfig);

            assertEquals(HitlTimeoutPolicy.WAIT_INDEFINITELY, memory.getHitlTimeoutPolicy(), "governing rule: " + governing);
        }
        // The same config auto-approves a batch without a mandatory call — the clamp is
        // what the assertion above sees, not a default.
        var plain = consentPause(Provenance.VERIFIED).getHitlPendingToolCalls();
        plain.getCalls().get(0).setMandatoryReasonCode(null);
        plain.setEffectiveToolApprovals(explicit);
        IConversationMemory memory = new ConversationMemory(CONVERSATION_ID, AGENT_ID, AGENT_VERSION, USER_ID);
        memory.setHitlPendingToolCalls(plain);
        var hitlConfig = new AgentConfiguration.HitlConfig();
        hitlConfig.setApprovalTimeout("PT1H");
        conversationService.conversationHitlService.applyEffectiveToolTimeoutPolicy(memory, hitlConfig);
        assertEquals(HitlTimeoutPolicy.AUTO_APPROVE, memory.getHitlTimeoutPolicy());
    }
}
