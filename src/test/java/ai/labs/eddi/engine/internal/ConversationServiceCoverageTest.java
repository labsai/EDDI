/* Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.configs.properties.IUserMemoryStore;
import ai.labs.eddi.datastore.IResourceStore.ResourceNotFoundException;
import ai.labs.eddi.datastore.IResourceStore.ResourceStoreException;
import ai.labs.eddi.engine.api.IConversationService.*;
import ai.labs.eddi.engine.audit.AuditLedgerService;
import ai.labs.eddi.engine.gdpr.GdprComplianceService;
import ai.labs.eddi.engine.caching.ICache;
import ai.labs.eddi.engine.caching.ICacheFactory;
import ai.labs.eddi.engine.lifecycle.IConversation;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.memory.descriptor.IConversationDescriptorStore;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot.ConversationStepSnapshot;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot.ResultSnapshot;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot.WorkflowRunSnapshot;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.model.Deployment.Environment;
import ai.labs.eddi.engine.model.InputData;
import ai.labs.eddi.engine.runtime.IAgent;
import ai.labs.eddi.engine.runtime.IAgentFactory;
import ai.labs.eddi.engine.runtime.IConversationCoordinator;
import ai.labs.eddi.engine.runtime.IConversationSetup;
import ai.labs.eddi.engine.runtime.IRuntime;
import ai.labs.eddi.engine.tenancy.TenantQuotaService;
import ai.labs.eddi.engine.tenancy.model.QuotaCheckResult;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.openMocks;

/**
 * Additional coverage tests for {@link ConversationService} — targets branches
 * not covered by ConversationServiceTest or ConversationServiceExtendedTest.
 */
class ConversationServiceCoverageTest {

    private ConversationService conversationService;

    @Mock
    private IAgentFactory agentFactory;
    @Mock
    private IConversationMemoryStore conversationMemoryStore;
    @Mock
    private IConversationDescriptorStore conversationDescriptorStore;
    @Mock
    private IConversationCoordinator conversationCoordinator;
    @Mock
    private IConversationSetup conversationSetup;
    @Mock
    private IRuntime runtime;
    @Mock
    private IContextLogger contextLogger;
    @Mock
    private ICacheFactory cacheFactory;
    @Mock
    private ICache<String, ConversationState> conversationStateCache;
    @Mock
    private AuditLedgerService auditLedgerService;
    @Mock
    private GdprComplianceService gdprComplianceService;
    @Mock
    private TenantQuotaService tenantQuotaService;
    @Mock
    private IUserMemoryStore userMemoryStore;

    private static final Environment ENV = Environment.production;
    private static final String AGENT_ID = "aabbccdd11223344eeff5566";
    private static final String CONVERSATION_ID = "112233445566778899aabbcc";
    private static final String USER_ID = "user-coverage-test";
    private static final int AGENT_TIMEOUT = 60;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        openMocks(this);

        when(tenantQuotaService.acquireConversationSlot()).thenReturn(QuotaCheckResult.OK);
        when(tenantQuotaService.acquireApiCallSlot()).thenReturn(QuotaCheckResult.OK);
        when(auditLedgerService.isEnabled()).thenReturn(false);

        doReturn(conversationStateCache).when(cacheFactory).getCache("conversationState");
        when(contextLogger.createLoggingContext(any(), any(), any(), any()))
                .thenReturn(new HashMap<>());

        conversationService = new ConversationService(agentFactory,
                conversationMemoryStore, conversationDescriptorStore,
                userMemoryStore, conversationCoordinator, conversationSetup,
                cacheFactory, runtime, contextLogger, auditLedgerService,
                gdprComplianceService, tenantQuotaService,
                new SimpleMeterRegistry(), AGENT_TIMEOUT);
    }

    private ConversationMemorySnapshot createSnapshot() {
        var snapshot = new ConversationMemorySnapshot();
        snapshot.setAgentId(AGENT_ID);
        snapshot.setUserId(USER_ID);
        snapshot.setAgentVersion(1);
        snapshot.setConversationState(ConversationState.READY);
        snapshot.setConversationSteps(new ArrayList<>());
        return snapshot;
    }

    private ConversationMemorySnapshot createSnapshotWithSteps(int stepCount) {
        var snapshot = createSnapshot();
        var steps = new ArrayList<ConversationStepSnapshot>();
        var outputs = new ArrayList<ai.labs.eddi.engine.memory.model.ConversationOutput>();
        for (int i = 0; i < stepCount; i++) {
            var step = new ConversationStepSnapshot();
            var workflowRun = new WorkflowRunSnapshot();
            var result = new ResultSnapshot("input", "test-input-" + i, null, new Date(), null, true);
            workflowRun.setLifecycleTasks(List.of(result));
            step.setWorkflows(List.of(workflowRun));
            steps.add(step);
            outputs.add(new ai.labs.eddi.engine.memory.model.ConversationOutput());
        }
        snapshot.setConversationSteps(steps);
        snapshot.setConversationOutputs(outputs);
        return snapshot;
    }

    // =========================================================
    // readConversationLog — null logSize
    // =========================================================

    @Nested
    @DisplayName("readConversationLog edge cases")
    class ReadConversationLogEdgeCases {

        @Test
        @DisplayName("should default to -1 when logSize is null")
        void readConversationLog_nullLogSize_defaultsToMinusOne() throws Exception {
            var snapshot = createSnapshot();
            when(conversationMemoryStore.loadConversationMemorySnapshot(CONVERSATION_ID))
                    .thenReturn(snapshot);

            var result = conversationService.readConversationLog(CONVERSATION_ID, "text", null);

            assertNotNull(result);
            assertEquals("text/plain", result.mediaType());
        }

        @Test
        @DisplayName("should return JSON when outputType is 'json'")
        void readConversationLog_jsonType_returnsJson() throws Exception {
            var snapshot = createSnapshot();
            when(conversationMemoryStore.loadConversationMemorySnapshot(CONVERSATION_ID))
                    .thenReturn(snapshot);

            var result = conversationService.readConversationLog(CONVERSATION_ID, "JSON", null);

            assertEquals("application/json", result.mediaType());
        }

        @Test
        @DisplayName("should return text/plain for 'string' outputType with explicit logSize")
        void readConversationLog_stringType_withLogSize() throws Exception {
            var snapshot = createSnapshot();
            when(conversationMemoryStore.loadConversationMemorySnapshot(CONVERSATION_ID))
                    .thenReturn(snapshot);

            var result = conversationService.readConversationLog(CONVERSATION_ID, "string", 5);

            assertEquals("text/plain", result.mediaType());
        }
    }

    // =========================================================
    // undo — success path (undo IS available)
    // =========================================================

    @Nested
    @DisplayName("undo success path")
    class UndoSuccessPath {

        @Test
        @DisplayName("should return true and store memory when undo is available")
        void undo_available_returnsTrue() throws Exception {
            // Create snapshot with 2 steps so undo is available
            var snapshot = createSnapshotWithSteps(2);
            when(conversationMemoryStore.loadConversationMemorySnapshot(CONVERSATION_ID))
                    .thenReturn(snapshot);
            when(conversationMemoryStore.storeConversationMemorySnapshot(any()))
                    .thenReturn(CONVERSATION_ID);

            boolean result = conversationService.undo(ENV, AGENT_ID, CONVERSATION_ID);

            assertTrue(result);
            verify(conversationMemoryStore).storeConversationMemorySnapshot(any());
        }

        @Test
        @DisplayName("should return false when undo is not available (empty conversation)")
        void undo_notAvailable_returnsFalse() throws Exception {
            var snapshot = createSnapshot(); // 0 steps
            when(conversationMemoryStore.loadConversationMemorySnapshot(CONVERSATION_ID))
                    .thenReturn(snapshot);

            boolean result = conversationService.undo(ENV, AGENT_ID, CONVERSATION_ID);

            assertFalse(result);
            verify(conversationMemoryStore, never()).storeConversationMemorySnapshot(any());
        }
    }

    // =========================================================
    // redo — success path (redo IS available)
    // =========================================================

    @Nested
    @DisplayName("redo success path")
    class RedoSuccessPath {

        @Test
        @DisplayName("should return false when redo is not available (nothing was undone)")
        void redo_notAvailable_returnsFalse() throws Exception {
            var snapshot = createSnapshotWithSteps(2);
            when(conversationMemoryStore.loadConversationMemorySnapshot(CONVERSATION_ID))
                    .thenReturn(snapshot);

            boolean result = conversationService.redo(ENV, AGENT_ID, CONVERSATION_ID);

            assertFalse(result);
            verify(conversationMemoryStore, never()).storeConversationMemorySnapshot(any());
        }
    }

    // =========================================================
    // sayStreaming — auto-deploy success path
    // =========================================================

    @Nested
    @DisplayName("sayStreaming auto-deploy success")
    class SayStreamingAutoDeploy {

        @Test
        @DisplayName("should auto-deploy agent and proceed when first getAgent returns null")
        void sayStreaming_autoDeploySuccess_submitsToCoordinator() throws Exception {
            var snapshot = createSnapshot();
            IAgent mockAgent = mock(IAgent.class);
            IConversation mockConversation = mock(IConversation.class);

            when(conversationMemoryStore.loadConversationMemorySnapshot(CONVERSATION_ID))
                    .thenReturn(snapshot);
            // First call returns null (not deployed), second returns the agent
            when(agentFactory.getAgent(ENV, AGENT_ID, 1))
                    .thenReturn(null)
                    .thenReturn(mockAgent);
            when(mockAgent.continueConversation(any(), any(), any()))
                    .thenReturn(mockConversation);
            when(mockConversation.isEnded()).thenReturn(false);

            var handler = mock(StreamingResponseHandler.class);

            conversationService.sayStreaming(ENV, AGENT_ID, CONVERSATION_ID,
                    false, false, List.of(),
                    new InputData("hello", Map.of()), handler);

            verify(agentFactory).deployAgent(ENV, AGENT_ID, 1, null);
            verify(conversationCoordinator).submitInOrder(eq(CONVERSATION_ID), any());
        }
    }

    // =========================================================
    // startConversation — IllegalAccessException wrapping
    // =========================================================

    @Nested
    @DisplayName("startConversation exception wrapping")
    class StartConversationExceptionWrapping {

        @Test
        @DisplayName("should wrap IllegalAccessException in ResourceStoreException")
        void startConversation_illegalAccess_wrapsInResourceStoreException() throws Exception {
            when(conversationSetup.computeAnonymousUserIdIfEmpty(any(), any()))
                    .thenReturn(USER_ID);
            IAgent mockAgent = mock(IAgent.class);
            when(agentFactory.getLatestReadyAgent(ENV, AGENT_ID))
                    .thenReturn(mockAgent);
            when(mockAgent.startConversation(any(), any(), any(), any()))
                    .thenThrow(new IllegalAccessException("Access denied"));

            assertThrows(ResourceStoreException.class,
                    () -> conversationService.startConversation(ENV, AGENT_ID, USER_ID,
                            new LinkedHashMap<>()));
        }
    }

    // =========================================================
    // validateParams — null parameter checks
    // =========================================================

    @Nested
    @DisplayName("validateParams null checks")
    class ValidateParamsNullChecks {

        @Test
        @DisplayName("readConversation should throw when environment is null")
        void readConversation_nullEnvironment() {
            assertThrows(IllegalArgumentException.class,
                    () -> conversationService.readConversation(null, AGENT_ID,
                            CONVERSATION_ID, false, false, List.of()));
        }

        @Test
        @DisplayName("readConversation should throw when agentId is null")
        void readConversation_nullAgentId() {
            assertThrows(IllegalArgumentException.class,
                    () -> conversationService.readConversation(ENV, null,
                            CONVERSATION_ID, false, false, List.of()));
        }

        @Test
        @DisplayName("readConversation should throw when conversationId is null")
        void readConversation_nullConversationId() {
            assertThrows(IllegalArgumentException.class,
                    () -> conversationService.readConversation(ENV, AGENT_ID,
                            null, false, false, List.of()));
        }

        @Test
        @DisplayName("startConversation should throw when environment is null")
        void startConversation_nullEnvironment() {
            assertThrows(IllegalArgumentException.class,
                    () -> conversationService.startConversation(null, AGENT_ID,
                            USER_ID, Map.of()));
        }

        @Test
        @DisplayName("startConversation should throw when agentId is null")
        void startConversation_nullAgentId() {
            assertThrows(IllegalArgumentException.class,
                    () -> conversationService.startConversation(ENV, null,
                            USER_ID, Map.of()));
        }

        @Test
        @DisplayName("getConversationState should throw when environment is null")
        void getConversationState_nullEnvironment() {
            assertThrows(IllegalArgumentException.class,
                    () -> conversationService.getConversationState(null, CONVERSATION_ID));
        }

        @Test
        @DisplayName("getConversationState should throw when conversationId is null")
        void getConversationState_nullConversationId() {
            assertThrows(IllegalArgumentException.class,
                    () -> conversationService.getConversationState(ENV, null));
        }

        @Test
        @DisplayName("getConversationState string-only should throw when null")
        void getConversationState_stringOnly_null() {
            assertThrows(IllegalArgumentException.class,
                    () -> conversationService.getConversationState(null));
        }

        @Test
        @DisplayName("readConversation string-only should throw when null")
        void readConversation_stringOnly_null() {
            assertThrows(IllegalArgumentException.class,
                    () -> conversationService.readConversation(null, false, false, List.of()));
        }
    }

    // =========================================================
    // Conversation not found (null memory from conversion)
    // =========================================================

    @Nested
    @DisplayName("ConversationNotFoundException paths")
    class ConversationNotFoundPaths {

        @Test
        @DisplayName("say should throw ConversationNotFoundException when memory snapshot loads but converts to null")
        void say_loadReturnsNull_throwsConversationNotFound() throws Exception {
            // If loadConversationMemorySnapshot throws, it propagates as-is
            when(conversationMemoryStore.loadConversationMemorySnapshot(CONVERSATION_ID))
                    .thenThrow(new ResourceNotFoundException("No snapshot"));

            var handler = mock(ConversationResponseHandler.class);

            assertThrows(Exception.class,
                    () -> conversationService.say(ENV, AGENT_ID, CONVERSATION_ID,
                            false, false, List.of(),
                            new InputData("hello", Map.of()), false, handler));
        }
    }
}
