/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.properties.IUserMemoryStore;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.audit.AuditLedgerService;
import ai.labs.eddi.engine.caching.ICache;
import ai.labs.eddi.engine.caching.ICacheFactory;
import ai.labs.eddi.engine.gdpr.GdprComplianceService;
import ai.labs.eddi.engine.lifecycle.IConversation;
import ai.labs.eddi.engine.memory.ConversationMemory;
import ai.labs.eddi.engine.memory.IConversationMemory;
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
import ai.labs.eddi.engine.runtime.IAgent;
import ai.labs.eddi.engine.runtime.IAgentFactory;
import ai.labs.eddi.engine.runtime.IConversationCoordinator;
import ai.labs.eddi.engine.runtime.IConversationSetup;
import ai.labs.eddi.engine.runtime.IRuntime;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.engine.security.CallerIdentityContext;
import ai.labs.eddi.engine.tenancy.TenantQuotaService;
import ai.labs.eddi.engine.tenancy.model.QuotaCheckResult;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Which agent version a conversation's turn runs on: the highest ready version
 * of its compatibility generation, or — for a conversation without one —
 * exactly the version it has always run on.
 */
class ConversationServiceAgentVersionTest {

    private static final Environment ENV = Environment.production;
    private static final String AGENT_ID = "agent-123";
    private static final String CONVERSATION_ID = "conv-456";
    private static final String USER_ID = "user-789";
    private static final String SWITCH_METRIC = "eddi_conversation_agent_version_switch_count";

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

    private SimpleMeterRegistry meterRegistry;
    private ConversationService conversationService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        doReturn(conversationStateCache).when(cacheFactory).getCache("conversationState");
        meterRegistry = new SimpleMeterRegistry();
        conversationService = new ConversationService(
                agentFactory, conversationMemoryStore, conversationDescriptorStore,
                userMemoryStore, conversationCoordinator, conversationSetup,
                cacheFactory, runtime, contextLogger, auditLedgerService,
                gdprComplianceService, tenantQuotaService, scheduleStore, agentStore,
                jsonSerialization, meterRegistry, ConversationServiceTestFixtures.hitlResumeEvent(),
                new CallerIdentityContext(null, null), 30);
    }

    private static ConversationMemory memoryOn(int version, Integer generation) {
        var memory = new ConversationMemory(CONVERSATION_ID, AGENT_ID, version, USER_ID);
        memory.setCompatibilityGeneration(generation);
        memory.setConversationState(ConversationState.READY);
        return memory;
    }

    private static IAgent agent(int version, Integer generation) {
        IAgent agent = mock(IAgent.class);
        when(agent.getAgentId()).thenReturn(AGENT_ID);
        when(agent.getAgentVersion()).thenReturn(version);
        when(agent.getCompatibilityGeneration()).thenReturn(generation);
        return agent;
    }

    private double switches() {
        return meterRegistry.counter(SWITCH_METRIC).count();
    }

    @Nested
    @DisplayName("resolving the agent of a turn")
    class Resolve {

        /**
         * Guarantee: a conversation with no generation — every conversation that
         * existed before this feature — takes exactly today's path.
         */
        @Test
        @DisplayName("a conversation without a generation runs on its own version, looked up as before")
        void pinnedTakesTodaysPath() throws Exception {
            var memory = memoryOn(1, null);
            IAgent pinned = agent(1, null);
            when(agentFactory.getAgent(ENV, AGENT_ID, 1)).thenReturn(pinned);

            assertSame(pinned, conversationService.resolveConversationAgent(ENV, memory));

            verify(agentFactory, never()).getLatestReadyAgentOfGeneration(any(), anyString(), anyInt());
            assertEquals(1, memory.getAgentVersion());
            assertEquals(0.0, switches());
        }

        @Test
        @DisplayName("a newer ready version of the same generation is followed")
        void followsNewerCompatibleVersion() throws Exception {
            var memory = memoryOn(1, 2);
            IAgent newer = agent(3, 2);
            when(agentFactory.getLatestReadyAgentOfGeneration(ENV, AGENT_ID, 2)).thenReturn(newer);

            assertSame(newer, conversationService.resolveConversationAgent(ENV, memory));

            assertEquals(3, memory.getAgentVersion());
            assertEquals(1, memory.takePreviousAgentVersion(), "the step must be able to record where it came from");
            verify(conversationSetup).updateConversationAgentVersion(CONVERSATION_ID, AGENT_ID, 3);
            verify(agentFactory, never()).getAgent(any(), anyString(), anyInt());
            assertEquals(1.0, switches());
        }

        @Test
        @DisplayName("already on the latest compatible version: no move")
        void alreadyOnLatest() throws Exception {
            var memory = memoryOn(3, 2);
            IAgent current = agent(3, 2);
            when(agentFactory.getLatestReadyAgentOfGeneration(ENV, AGENT_ID, 2)).thenReturn(current);

            assertSame(current, conversationService.resolveConversationAgent(ENV, memory));

            assertNull(memory.takePreviousAgentVersion());
            verify(conversationSetup, never()).updateConversationAgentVersion(anyString(), anyString(), anyInt());
            assertEquals(0.0, switches());
        }

        /**
         * A faulty v6 is undeployed and v5 of the same generation is still deployed:
         * the conversations v6 picked up go back to v5 on their next turn.
         */
        @Test
        @DisplayName("an older compatible version is used when the newer one is gone — rollback")
        void rollsBackWithinGeneration() throws Exception {
            var memory = memoryOn(6, 2);
            IAgent older = agent(5, 2);
            when(agentFactory.getLatestReadyAgentOfGeneration(ENV, AGENT_ID, 2)).thenReturn(older);

            assertSame(older, conversationService.resolveConversationAgent(ENV, memory));

            assertEquals(5, memory.getAgentVersion());
        }

        @Test
        @DisplayName("no ready version of the generation here: its own version, deployed on demand as before")
        void noCandidateFallsBackToOwnVersion() throws Exception {
            var memory = memoryOn(3, 2);
            IAgent own = agent(3, 2);
            when(agentFactory.getLatestReadyAgentOfGeneration(ENV, AGENT_ID, 2)).thenReturn(null);
            when(agentFactory.getAgent(ENV, AGENT_ID, 3)).thenReturn(own);

            assertSame(own, conversationService.resolveConversationAgent(ENV, memory));

            assertEquals(3, memory.getAgentVersion());
        }

        @Test
        @DisplayName("a paused conversation stays on the version that paused it")
        void pausedStaysPut() throws Exception {
            var memory = memoryOn(1, 2);
            memory.setConversationState(ConversationState.AWAITING_HUMAN);
            IAgent own = agent(1, 2);
            when(agentFactory.getAgent(ENV, AGENT_ID, 1)).thenReturn(own);

            assertSame(own, conversationService.resolveConversationAgent(ENV, memory));

            verify(agentFactory, never()).getLatestReadyAgentOfGeneration(any(), anyString(), anyInt());
            assertEquals(1, memory.getAgentVersion());
        }

        @Test
        @DisplayName("a descriptor that cannot be updated does not stop the move")
        void descriptorFailureIsTolerated() throws Exception {
            var memory = memoryOn(1, 2);
            IAgent newer = agent(3, 2);
            when(agentFactory.getLatestReadyAgentOfGeneration(ENV, AGENT_ID, 2)).thenReturn(newer);
            doThrow(new IResourceStore.ResourceStoreException("down")).when(conversationSetup)
                    .updateConversationAgentVersion(CONVERSATION_ID, AGENT_ID, 3);

            assertSame(newer, conversationService.resolveConversationAgent(ENV, memory));
            assertEquals(3, memory.getAgentVersion());
        }
    }

    @Nested
    @DisplayName("a turn")
    class Turn {

        private void storedConversation(int version, Integer generation) throws Exception {
            var snapshot = new ConversationMemorySnapshot();
            snapshot.setAgentId(AGENT_ID);
            snapshot.setAgentVersion(version);
            snapshot.setCompatibilityGeneration(generation);
            snapshot.setUserId(USER_ID);
            snapshot.setConversationId(CONVERSATION_ID);
            snapshot.setConversationState(ConversationState.READY);
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
            when(tenantQuotaService.acquireApiCallSlot()).thenReturn(QuotaCheckResult.OK);
            when(contextLogger.createLoggingContext(any(), any(), any(), any())).thenReturn(new HashMap<>());
        }

        @Test
        @DisplayName("runs on the newer compatible version, with the memory moved to it")
        void turnRunsOnFollowedVersion() throws Exception {
            storedConversation(1, 2);
            IAgent newer = agent(3, 2);
            when(agentFactory.getLatestReadyAgentOfGeneration(ENV, AGENT_ID, 2)).thenReturn(newer);
            when(newer.continueConversation(any(), any(), any())).thenReturn(mock(IConversation.class));

            conversationService.say(ENV, AGENT_ID, CONVERSATION_ID, false, true, null,
                    new InputData("hello", new LinkedHashMap<>()), false, s -> {
                    });

            ArgumentCaptor<IConversationMemory> ranOn = ArgumentCaptor.forClass(IConversationMemory.class);
            verify(newer).continueConversation(ranOn.capture(), any(), any());
            assertEquals(3, ranOn.getValue().getAgentVersion());
            assertEquals(2, ranOn.getValue().getCompatibilityGeneration());
            verify(agentFactory, never()).getAgent(any(), anyString(), anyInt());
        }

        @Test
        @DisplayName("a stored conversation without a generation runs on its stored version")
        void legacyTurnStaysPinned() throws Exception {
            storedConversation(1, null);
            IAgent pinned = agent(1, null);
            when(agentFactory.getAgent(ENV, AGENT_ID, 1)).thenReturn(pinned);
            when(pinned.continueConversation(any(), any(), any())).thenReturn(mock(IConversation.class));

            conversationService.say(ENV, AGENT_ID, CONVERSATION_ID, false, true, null,
                    new InputData("hello", new LinkedHashMap<>()), false, s -> {
                    });

            verify(pinned).continueConversation(any(), any(), any());
            verify(agentFactory, never()).getLatestReadyAgentOfGeneration(any(), anyString(), anyInt());
        }
    }
}
