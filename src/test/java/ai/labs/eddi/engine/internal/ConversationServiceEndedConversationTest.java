/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.properties.IUserMemoryStore;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.audit.AuditLedgerService;
import ai.labs.eddi.engine.caching.ICache;
import ai.labs.eddi.engine.caching.ICacheFactory;
import ai.labs.eddi.engine.gdpr.GdprComplianceService;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.memory.descriptor.IConversationDescriptorStore;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot.ConversationStepSnapshot;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot.ResultSnapshot;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot.WorkflowRunSnapshot;
import ai.labs.eddi.engine.memory.model.ConversationOutput;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.model.Deployment.Environment;
import ai.labs.eddi.engine.model.InputData;
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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Date;
import java.util.LinkedHashMap;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * How {@link ConversationService} treats a conversation that has ended: turns
 * on it are refused as ended before anything else is looked up, and ending one
 * can record why.
 */
class ConversationServiceEndedConversationTest {

    private static final Environment ENV = Environment.production;
    private static final String AGENT_ID = "agent-123";
    private static final String CONVERSATION_ID = "conv-456";
    private static final String USER_ID = "user-789";

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

    private ConversationService conversationService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        doReturn(conversationStateCache).when(cacheFactory).getCache("conversationState");
        conversationService = new ConversationService(
                agentFactory, conversationMemoryStore, conversationDescriptorStore,
                userMemoryStore, conversationCoordinator, conversationSetup,
                cacheFactory, runtime, contextLogger, auditLedgerService,
                gdprComplianceService, tenantQuotaService, scheduleStore, agentStore,
                jsonSerialization,
                new SimpleMeterRegistry(), ConversationServiceTestFixtures.hitlResumeEvent(),
                new CallerIdentityContext(null, null), 30);
    }

    private void storedConversationIn(ConversationState state) throws Exception {
        var snapshot = new ConversationMemorySnapshot();
        snapshot.setAgentId(AGENT_ID);
        snapshot.setAgentVersion(1);
        snapshot.setUserId(USER_ID);
        snapshot.setConversationId(CONVERSATION_ID);
        snapshot.setConversationState(state);
        snapshot.setEnvironment(ENV);
        var step = new ConversationStepSnapshot();
        var workflowRun = new WorkflowRunSnapshot();
        workflowRun.getLifecycleTasks().add(new ResultSnapshot("input:initial", "hello", null, new Date(), null, true));
        step.getWorkflows().add(workflowRun);
        snapshot.getConversationSteps().add(step);
        var output = new ConversationOutput();
        output.put("input", "hello");
        snapshot.getConversationOutputs().add(output);
        doReturn(snapshot).when(conversationMemoryStore).loadConversationMemorySnapshot(CONVERSATION_ID);
    }

    private static InputData input() {
        return new InputData("hello", new LinkedHashMap<>());
    }

    @Nested
    @DisplayName("a turn on an ended conversation")
    class TurnOnEndedConversation {

        /**
         * The agent version is not deployed here (the factory mock answers null), which
         * is the normal state after an undeploy with endAllActiveConversations. Before
         * the fix the agent lookup came first and the turn failed as "agent not ready",
         * which no client recovers from.
         */
        @Test
        @DisplayName("say is refused as ended, before the agent is looked up or quota is taken")
        void sayRefusedAsEnded() throws Exception {
            storedConversationIn(ConversationState.ENDED);

            assertThrows(IConversationService.ConversationEndedException.class,
                    () -> conversationService.say(ENV, AGENT_ID, CONVERSATION_ID, false, true, null, input(), false, s -> {
                    }));

            verify(agentFactory, never()).getAgent(any(), anyString(), anyInt());
            verifyNoInteractions(tenantQuotaService);
        }

        @Test
        @DisplayName("sayStreaming is refused as ended, before the agent is looked up or quota is taken")
        void sayStreamingRefusedAsEnded() throws Exception {
            storedConversationIn(ConversationState.ENDED);

            assertThrows(IConversationService.ConversationEndedException.class,
                    () -> conversationService.sayStreaming(ENV, AGENT_ID, CONVERSATION_ID, false, true, null, input(),
                            mock(IConversationService.StreamingResponseHandler.class)));

            verify(agentFactory, never()).getAgent(any(), anyString(), anyInt());
            verifyNoInteractions(tenantQuotaService);
        }

        @Test
        @DisplayName("a conversation that has not ended still reaches the agent lookup")
        void liveConversationStillResolvesAgent() throws Exception {
            storedConversationIn(ConversationState.READY);

            assertThrows(IConversationService.AgentNotReadyException.class,
                    () -> conversationService.say(ENV, AGENT_ID, CONVERSATION_ID, false, true, null, input(), false, s -> {
                    }));

            verify(agentFactory, atLeastOnce()).getAgent(ENV, AGENT_ID, 1);
        }
    }

    @Nested
    @DisplayName("ending a conversation with a reason")
    class EndWithReason {

        @Test
        @DisplayName("the reason is recorded after the state is set")
        void reasonRecordedAfterState() {
            conversationService.endConversation(CONVERSATION_ID, "user:alice",
                    IConversationService.END_REASON_AGENT_VERSION_RETIRED);

            InOrder order = inOrder(conversationMemoryStore);
            order.verify(conversationMemoryStore).setConversationState(CONVERSATION_ID, ConversationState.ENDED);
            order.verify(conversationMemoryStore).setConversationEndReason(CONVERSATION_ID,
                    IConversationService.END_REASON_AGENT_VERSION_RETIRED);
        }

        @Test
        @DisplayName("ending without a reason records none")
        void noReasonRecordsNothing() {
            conversationService.endConversation(CONVERSATION_ID, "user:alice");

            verify(conversationMemoryStore).setConversationState(CONVERSATION_ID, ConversationState.ENDED);
            verify(conversationMemoryStore, never()).setConversationEndReason(anyString(), anyString());
        }

        @Test
        @DisplayName("a reason that cannot be stored does not undo or fail the end")
        void reasonFailureIsSwallowed() {
            doThrow(new RuntimeException("store down")).when(conversationMemoryStore)
                    .setConversationEndReason(CONVERSATION_ID, IConversationService.END_REASON_AGENT_VERSION_RETIRED);

            assertDoesNotThrow(() -> conversationService.endConversation(CONVERSATION_ID, "user:alice",
                    IConversationService.END_REASON_AGENT_VERSION_RETIRED));

            verify(conversationMemoryStore).setConversationState(CONVERSATION_ID, ConversationState.ENDED);
            verify(conversationMemoryStore, never()).setConversationState(CONVERSATION_ID, ConversationState.READY);
        }
    }
}
