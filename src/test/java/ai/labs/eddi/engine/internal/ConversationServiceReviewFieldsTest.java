/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.hitl.model.HitlReviewConfig;
import ai.labs.eddi.configs.properties.IUserMemoryStore;
import ai.labs.eddi.datastore.IResourceStore.ResourceStoreException;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.audit.AuditLedgerService;
import ai.labs.eddi.engine.caching.ICache;
import ai.labs.eddi.engine.caching.ICacheFactory;
import ai.labs.eddi.engine.gdpr.GdprComplianceService;
import ai.labs.eddi.engine.lifecycle.model.HitlDecision;
import ai.labs.eddi.engine.lifecycle.model.HitlDecision.HitlVerdict;
import ai.labs.eddi.engine.lifecycle.model.HitlRevisionCandidate;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.memory.descriptor.IConversationDescriptorStore;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot.ConversationStepSnapshot;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot.ResultSnapshot;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot.WorkflowRunSnapshot;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.runtime.IAgentFactory;
import ai.labs.eddi.engine.runtime.IConversationCoordinator;
import ai.labs.eddi.engine.runtime.IConversationSetup;
import ai.labs.eddi.engine.runtime.IRuntime;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.engine.security.CallerIdentityContext;
import ai.labs.eddi.engine.tenancy.TenantQuotaService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * The review fields of a decision ({@code editedAnswer}, {@code reasonCode})
 * are validated against the paused snapshot and the PINNED agent version before
 * the AWAITING_HUMAN→IN_PROGRESS compare-and-set: a bad edit never consumes the
 * pause.
 */
@DisplayName("ConversationService — review fields are validated before the pause is consumed")
class ConversationServiceReviewFieldsTest {

    private static final String CONVERSATION_ID = "conv-review-1";
    private static final String AGENT_ID = "agent-review";

    @Mock
    private IConversationMemoryStore conversationMemoryStore;
    @Mock
    private IAgentStore agentStore;
    @Mock
    private ICacheFactory cacheFactory;
    @Mock
    private ICache<String, ConversationState> conversationStateCache;

    private ConversationService conversationService;

    @BeforeEach
    void setUp() throws Exception {
        MockitoAnnotations.openMocks(this);
        doReturn(conversationStateCache).when(cacheFactory).getCache("conversationState", ConversationService.CONVERSATION_STATE_CACHE_TTL);
        var contextLogger = mock(IContextLogger.class);
        lenient().doReturn(new HashMap<String, String>()).when(contextLogger).createLoggingContext(any(), any(), any(), any());
        conversationService = new ConversationService(mock(IAgentFactory.class), conversationMemoryStore, mock(IConversationDescriptorStore.class),
                mock(IUserMemoryStore.class), mock(IConversationCoordinator.class), mock(IConversationSetup.class), cacheFactory,
                mock(IRuntime.class),
                contextLogger, mock(AuditLedgerService.class), mock(GdprComplianceService.class), mock(TenantQuotaService.class),
                mock(IScheduleStore.class), agentStore, mock(IJsonSerialization.class), new SimpleMeterRegistry(),
                ConversationServiceTestFixtures.hitlResumeEvent(), new CallerIdentityContext(null, null), 30);
        doReturn(ConversationState.AWAITING_HUMAN).when(conversationMemoryStore).getConversationState(CONVERSATION_ID);
    }

    private ConversationMemorySnapshot pausedSnapshot() {
        var snapshot = new ConversationMemorySnapshot();
        snapshot.setConversationId(CONVERSATION_ID);
        snapshot.setAgentId(AGENT_ID);
        snapshot.setAgentVersion(2);
        snapshot.setConversationState(ConversationState.AWAITING_HUMAN);
        snapshot.setHitlPauseType("RULE");
        snapshot.setHitlPausedAt(Instant.now());
        var candidate = new HitlRevisionCandidate();
        candidate.setDraft("draft");
        var run = new WorkflowRunSnapshot();
        run.setLifecycleTasks(List.of(new ResultSnapshot(HitlRevisionCandidate.STEP_KEY, candidate.toMap(), null, new Date(), "wf", false)));
        var step = new ConversationStepSnapshot();
        step.setWorkflows(List.of(run));
        snapshot.setConversationSteps(List.of(step));
        return snapshot;
    }

    private static AgentConfiguration agentConfig(boolean allowEdits) {
        var config = new AgentConfiguration();
        var hitl = new AgentConfiguration.HitlConfig();
        var review = new HitlReviewConfig();
        review.setAllowAnswerEdits(allowEdits);
        hitl.setReview(review);
        config.setHitlConfig(hitl);
        return config;
    }

    private static HitlDecision edit(String text) {
        var decision = new HitlDecision();
        decision.setVerdict(HitlVerdict.APPROVED);
        decision.setEditedAnswer(text);
        return decision;
    }

    @Test
    @DisplayName("edits not allowed by the pinned version: 400 and the pause is untouched")
    void editRefusedBeforeCas() throws Exception {
        doReturn(pausedSnapshot()).when(conversationMemoryStore).loadConversationMemorySnapshot(CONVERSATION_ID);
        doReturn(agentConfig(false)).when(agentStore).read(AGENT_ID, 2);

        var e = assertThrows(IllegalArgumentException.class, () -> conversationService.resumeConversation(CONVERSATION_ID, edit("better"), null));

        assertTrue(e.getMessage().contains("allowAnswerEdits"));
        verify(conversationMemoryStore, never()).compareAndSetState(any(), any(), any());
    }

    @Test
    @DisplayName("an unknown reason code is refused before the CAS")
    void reasonCodeRefusedBeforeCas() throws Exception {
        doReturn(pausedSnapshot()).when(conversationMemoryStore).loadConversationMemorySnapshot(CONVERSATION_ID);
        doReturn(agentConfig(false)).when(agentStore).read(AGENT_ID, 2);
        var reject = new HitlDecision();
        reject.setVerdict(HitlVerdict.REJECTED);
        reject.setReasonCode("NOT_A_CODE");

        assertThrows(IllegalArgumentException.class, () -> conversationService.resumeConversation(CONVERSATION_ID, reject, null));
        verify(conversationMemoryStore, never()).compareAndSetState(any(), any(), any());
    }

    @Test
    @DisplayName("an unreadable pinned config fails closed: no edit, no CAS")
    void unreadableConfigFailsClosed() throws Exception {
        doReturn(pausedSnapshot()).when(conversationMemoryStore).loadConversationMemorySnapshot(CONVERSATION_ID);
        doThrow(new ResourceStoreException("down")).when(agentStore).read(AGENT_ID, 2);

        assertThrows(ResourceStoreException.class, () -> conversationService.resumeConversation(CONVERSATION_ID, edit("better"), null));
        verify(conversationMemoryStore, never()).compareAndSetState(any(), any(), any());
    }

    @Test
    @DisplayName("validateHitlDecision is a dry run: a valid edit passes, nothing is consumed")
    void dryRun() throws Exception {
        doReturn(pausedSnapshot()).when(conversationMemoryStore).loadConversationMemorySnapshot(CONVERSATION_ID);
        doReturn(agentConfig(true)).when(agentStore).read(AGENT_ID, 2);

        assertDoesNotThrow(() -> conversationService.validateHitlDecision(CONVERSATION_ID, edit("better")));
        assertThrows(IllegalArgumentException.class, () -> conversationService.validateHitlDecision(CONVERSATION_ID, edit(" ")));
        verify(conversationMemoryStore, never()).compareAndSetState(any(), any(), any());
    }
}
