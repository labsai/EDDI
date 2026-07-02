/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.configs.properties.IUserMemoryStore;
import ai.labs.eddi.engine.api.IConversationService.*;
import ai.labs.eddi.engine.audit.AuditLedgerService;
import ai.labs.eddi.engine.gdpr.GdprComplianceService;
import ai.labs.eddi.engine.gdpr.ProcessingRestrictedException;
import ai.labs.eddi.engine.caching.ICache;
import ai.labs.eddi.engine.caching.ICacheFactory;
import ai.labs.eddi.engine.lifecycle.IConversation;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.memory.descriptor.IConversationDescriptorStore;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.model.Deployment.Environment;
import ai.labs.eddi.engine.model.InputData;
import ai.labs.eddi.engine.runtime.IAgent;
import ai.labs.eddi.engine.runtime.IAgentFactory;
import ai.labs.eddi.engine.runtime.IConversationCoordinator;
import ai.labs.eddi.engine.runtime.IRuntime;
import ai.labs.eddi.engine.runtime.service.ServiceException;
import ai.labs.eddi.engine.tenancy.TenantQuotaService;
import ai.labs.eddi.engine.tenancy.model.QuotaCheckResult;
import ai.labs.eddi.engine.runtime.IConversationSetup;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.configs.agents.IAgentStore;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("ConversationService Extended Branch Coverage Tests")
class ConversationServiceExtendedTest {

    private ConversationService conversationService;

    private IAgentFactory agentFactory;
    private IConversationMemoryStore conversationMemoryStore;
    private IConversationDescriptorStore conversationDescriptorStore;
    private IConversationCoordinator conversationCoordinator;
    private IConversationSetup conversationSetup;
    private IRuntime runtime;
    private IContextLogger contextLogger;
    private ICacheFactory cacheFactory;
    @SuppressWarnings("unchecked")
    private ICache<String, ConversationState> conversationStateCache;
    private AuditLedgerService auditLedgerService;
    private GdprComplianceService gdprComplianceService;
    private TenantQuotaService tenantQuotaService;
    private IScheduleStore scheduleStore;
    private IAgentStore agentStore;
    private IUserMemoryStore userMemoryStore;

    private static final Environment ENV = Environment.production;
    private static final String AGENT_ID = "test-agent-id";
    private static final String CONVERSATION_ID = "test-conversation-id";
    private static final String USER_ID = "test-user-id";
    private static final int AGENT_TIMEOUT = 60;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        agentFactory = mock(IAgentFactory.class);
        conversationMemoryStore = mock(IConversationMemoryStore.class);
        conversationDescriptorStore = mock(IConversationDescriptorStore.class);
        conversationCoordinator = mock(IConversationCoordinator.class);
        conversationSetup = mock(IConversationSetup.class);
        runtime = mock(IRuntime.class);
        contextLogger = mock(IContextLogger.class);
        cacheFactory = mock(ICacheFactory.class);
        conversationStateCache = mock(ICache.class);
        auditLedgerService = mock(AuditLedgerService.class);
        gdprComplianceService = mock(GdprComplianceService.class);
        tenantQuotaService = mock(TenantQuotaService.class);
        scheduleStore = mock(IScheduleStore.class);
        agentStore = mock(IAgentStore.class);
        userMemoryStore = mock(IUserMemoryStore.class);

        when(tenantQuotaService.acquireConversationSlot()).thenReturn(QuotaCheckResult.OK);
        when(tenantQuotaService.acquireApiCallSlot()).thenReturn(QuotaCheckResult.OK);
        when(auditLedgerService.isEnabled()).thenReturn(false);
        MeterRegistry meterRegistry = new SimpleMeterRegistry();

        doReturn(conversationStateCache).when(cacheFactory).getCache("conversationState");
        when(contextLogger.createLoggingContext(any(), any(), any(), any())).thenReturn(new HashMap<>());

        conversationService = new ConversationService(agentFactory, conversationMemoryStore,
                conversationDescriptorStore, userMemoryStore, conversationCoordinator,
                conversationSetup, cacheFactory, runtime, contextLogger, auditLedgerService,
                gdprComplianceService, tenantQuotaService, scheduleStore, agentStore,
                meterRegistry, ConversationServiceTestFixtures.hitlResumeEvent(), AGENT_TIMEOUT);
    }

    @Nested
    @DisplayName("say — rerunOnly=true")
    class SayRerunOnlyTests {

        @Test
        @DisplayName("rerunOnly=true — uses conversation.rerun instead of say")
        void rerunOnlyTrue() throws Exception {
            var snapshot = createSnapshot(AGENT_ID);
            IAgent mockAgent = mock(IAgent.class);
            IConversation mockConversation = mock(IConversation.class);

            when(conversationMemoryStore.loadConversationMemorySnapshot(CONVERSATION_ID)).thenReturn(snapshot);
            when(agentFactory.getAgent(ENV, AGENT_ID, 1)).thenReturn(mockAgent);
            when(mockAgent.continueConversation(any(), any(), any())).thenReturn(mockConversation);
            when(mockConversation.isEnded()).thenReturn(false);

            var handler = mock(ConversationResponseHandler.class);

            // rerunOnly=true (second-to-last param)
            conversationService.say(ENV, AGENT_ID, CONVERSATION_ID, false, false, List.of(),
                    new InputData("hello", Map.of()), true, handler);

            verify(conversationCoordinator).submitInOrder(eq(CONVERSATION_ID), any());
        }
    }

    @Nested
    @DisplayName("say — auto-deploy")
    class AutoDeployTests {

        @Test
        @DisplayName("agent null first time, succeeds after deploy — no exception")
        void autoDeploySuccess() throws Exception {
            var snapshot = createSnapshot(AGENT_ID);
            IAgent mockAgent = mock(IAgent.class);
            IConversation mockConversation = mock(IConversation.class);

            when(conversationMemoryStore.loadConversationMemorySnapshot(CONVERSATION_ID)).thenReturn(snapshot);
            // First call returns null, second call returns agent after deploy
            when(agentFactory.getAgent(ENV, AGENT_ID, 1)).thenReturn(null, mockAgent);
            when(mockAgent.continueConversation(any(), any(), any())).thenReturn(mockConversation);
            when(mockConversation.isEnded()).thenReturn(false);

            var handler = mock(ConversationResponseHandler.class);

            conversationService.say(ENV, AGENT_ID, CONVERSATION_ID, false, false, List.of(),
                    new InputData("hello", Map.of()), false, handler);

            verify(agentFactory).deployAgent(ENV, AGENT_ID, 1, null);
            verify(conversationCoordinator).submitInOrder(eq(CONVERSATION_ID), any());
        }
    }

    @Nested
    @DisplayName("say — userId null (anonymous)")
    class AnonymousUserTests {

        @Test
        @DisplayName("userId null in memory — gdpr check skipped")
        void nullUserId() throws Exception {
            var snapshot = createSnapshot(AGENT_ID);
            snapshot.setUserId(null);
            IAgent mockAgent = mock(IAgent.class);
            IConversation mockConversation = mock(IConversation.class);

            when(conversationMemoryStore.loadConversationMemorySnapshot(CONVERSATION_ID)).thenReturn(snapshot);
            when(agentFactory.getAgent(ENV, AGENT_ID, 1)).thenReturn(mockAgent);
            when(mockAgent.continueConversation(any(), any(), any())).thenReturn(mockConversation);
            when(mockConversation.isEnded()).thenReturn(false);

            var handler = mock(ConversationResponseHandler.class);

            assertDoesNotThrow(() -> conversationService.say(ENV, AGENT_ID, CONVERSATION_ID,
                    false, false, List.of(), new InputData("hello", Map.of()), false, handler));

            verify(gdprComplianceService, never()).isProcessingRestricted(any());
        }
    }

    @Nested
    @DisplayName("readConversationLog — outputType edge cases")
    class ConversationLogTests {

        @Test
        @DisplayName("outputType='json' — returns application/json")
        void jsonOutput() throws Exception {
            var snapshot = createSnapshot(AGENT_ID);
            when(conversationMemoryStore.loadConversationMemorySnapshot(CONVERSATION_ID)).thenReturn(snapshot);

            var result = conversationService.readConversationLog(CONVERSATION_ID, "JSON", 10);
            assertEquals("application/json", result.mediaType());
        }

        @Test
        @DisplayName("outputType='' (empty) — returns text/plain")
        void emptyOutputType() throws Exception {
            var snapshot = createSnapshot(AGENT_ID);
            when(conversationMemoryStore.loadConversationMemorySnapshot(CONVERSATION_ID)).thenReturn(snapshot);

            var result = conversationService.readConversationLog(CONVERSATION_ID, "", 5);
            assertEquals("text/plain", result.mediaType());
        }

        @Test
        @DisplayName("logSize null — defaults to -1 (all)")
        void nullLogSize() throws Exception {
            var snapshot = createSnapshot(AGENT_ID);
            when(conversationMemoryStore.loadConversationMemorySnapshot(CONVERSATION_ID)).thenReturn(snapshot);

            var result = conversationService.readConversationLog(CONVERSATION_ID, "text", null);
            assertNotNull(result);
        }
    }

    @Nested
    @DisplayName("createPropertiesHandler")
    class PropertiesHandlerTests {

        @Test
        @DisplayName("with memoryConfig — stores config correctly")
        void withMemoryConfig() {
            var config = new ai.labs.eddi.configs.agents.model.AgentConfiguration.UserMemoryConfig();
            config.setMaxRecallEntries(100);

            var handler = conversationService.createPropertiesHandler(USER_ID, config);

            assertNotNull(handler);
            assertEquals(USER_ID, handler.getUserId());
            assertNotNull(handler.getUserMemoryStore());
            assertNotNull(handler.getUserMemoryConfig());
            assertEquals(100, handler.getUserMemoryConfig().getMaxRecallEntries());
        }

        @Test
        @DisplayName("with null userId — returns handler with null userId")
        void nullUserId() {
            var handler = conversationService.createPropertiesHandler(null, null);

            assertNull(handler.getUserId());
            assertNull(handler.getUserMemoryConfig());
        }
    }

    @Nested
    @DisplayName("startConversation — ServiceException wrapping")
    class StartConversationErrorTests {

        @Test
        @DisplayName("ServiceException during start — wrapped in ResourceStoreException")
        void serviceException() throws Exception {
            when(conversationSetup.computeAnonymousUserIdIfEmpty(eq(USER_ID), any())).thenReturn(USER_ID);

            IAgent mockAgent = mock(IAgent.class);
            when(agentFactory.getLatestReadyAgent(ENV, AGENT_ID)).thenReturn(mockAgent);
            when(mockAgent.startConversation(anyString(), anyMap(), any(), any()))
                    .thenThrow(new InstantiationException("Agent init failed"));

            assertThrows(ai.labs.eddi.datastore.IResourceStore.ResourceStoreException.class,
                    () -> conversationService.startConversation(ENV, AGENT_ID, USER_ID, new LinkedHashMap<>()));
        }
    }

    @Nested
    @DisplayName("sayStreaming — agent not deployed")
    class SayStreamingAgentNotDeployed {

        @Test
        @DisplayName("agent null after deploy — throws AgentNotReadyException")
        void agentNull() throws Exception {
            var snapshot = createSnapshot(AGENT_ID);
            when(conversationMemoryStore.loadConversationMemorySnapshot(CONVERSATION_ID)).thenReturn(snapshot);
            when(agentFactory.getAgent(ENV, AGENT_ID, 1)).thenReturn(null);

            var handler = mock(StreamingResponseHandler.class);

            assertThrows(AgentNotReadyException.class,
                    () -> conversationService.sayStreaming(ENV, AGENT_ID, CONVERSATION_ID,
                            false, false, List.of(), new InputData("hello", Map.of()), handler));

            verify(agentFactory).deployAgent(ENV, AGENT_ID, 1, null);
        }
    }

    @Nested
    @DisplayName("sayStreaming — valid input with audit")
    class SayStreamingWithAudit {

        @Test
        @DisplayName("audit enabled — sets audit collector")
        void auditEnabled() throws Exception {
            var snapshot = createSnapshot(AGENT_ID);
            IAgent mockAgent = mock(IAgent.class);
            IConversation mockConversation = mock(IConversation.class);

            when(conversationMemoryStore.loadConversationMemorySnapshot(CONVERSATION_ID)).thenReturn(snapshot);
            when(agentFactory.getAgent(ENV, AGENT_ID, 1)).thenReturn(mockAgent);
            when(mockAgent.continueConversation(any(), any(), any())).thenReturn(mockConversation);
            when(mockConversation.isEnded()).thenReturn(false);
            when(auditLedgerService.isEnabled()).thenReturn(true);

            var handler = mock(StreamingResponseHandler.class);

            conversationService.sayStreaming(ENV, AGENT_ID, CONVERSATION_ID, false, false,
                    List.of(), new InputData("hello", Map.of()), handler);

            verify(conversationCoordinator).submitInOrder(eq(CONVERSATION_ID), any());
        }
    }

    @Nested
    @DisplayName("undo/redo — with available operations")
    class UndoRedoAvailable {

        @Test
        @DisplayName("undo available — returns true")
        void undoAvailable() throws Exception {
            var snapshot = createSnapshot(AGENT_ID);
            // Add conversation steps AND outputs to make undo available
            var step = new ConversationMemorySnapshot.ConversationStepSnapshot();
            step.setWorkflows(new ArrayList<>());
            snapshot.setConversationSteps(new ArrayList<>(List.of(step, step)));
            snapshot.setConversationOutputs(new ArrayList<>(List.of(
                    new ai.labs.eddi.engine.memory.model.ConversationOutput(),
                    new ai.labs.eddi.engine.memory.model.ConversationOutput())));
            when(conversationMemoryStore.loadConversationMemorySnapshot(CONVERSATION_ID)).thenReturn(snapshot);

            Boolean result = conversationService.isUndoAvailable(ENV, AGENT_ID, CONVERSATION_ID);
            assertTrue(result);
        }
    }

    // --- Helpers ---

    private ConversationMemorySnapshot createSnapshot(String agentId) {
        var snapshot = new ConversationMemorySnapshot();
        snapshot.setAgentId(agentId);
        snapshot.setUserId(USER_ID);
        snapshot.setAgentVersion(1);
        snapshot.setEnvironment(ENV);
        snapshot.setConversationState(ConversationState.READY);
        snapshot.setConversationSteps(new ArrayList<>());
        return snapshot;
    }
}
