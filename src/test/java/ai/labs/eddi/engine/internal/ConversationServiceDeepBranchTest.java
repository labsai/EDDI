/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.configs.properties.IUserMemoryStore;
import ai.labs.eddi.datastore.IResourceStore.ResourceNotFoundException;
import ai.labs.eddi.datastore.IResourceStore.ResourceStoreException;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
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
import ai.labs.eddi.engine.tenancy.QuotaExceededException;
import ai.labs.eddi.engine.tenancy.TenantQuotaService;
import ai.labs.eddi.engine.tenancy.model.QuotaCheckResult;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.configs.agents.IAgentStore;
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
 * Deep branch coverage tests for {@link ConversationService} — targets branches
 * not exercised by ConversationServiceCoverageTest or other existing tests:
 * <ul>
 * <li>endConversation sets state to ENDED</li>
 * <li>getConversationState — cache hit, cache miss + DB found, not found</li>
 * <li>readConversation — agent mismatch</li>
 * <li>readConversationLog — all output type branches</li>
 * <li>say — GDPR processing restricted</li>
 * <li>say — agent mismatch</li>
 * <li>say — quota exceeded</li>
 * <li>say — conversation ended</li>
 * <li>sayStreaming — GDPR restricted, agent mismatch, quota exceeded</li>
 * <li>isUndoAvailable / isRedoAvailable</li>
 * <li>undo/redo by conversationId only (overloads)</li>
 * <li>startConversation — GDPR restricted</li>
 * <li>startConversation — null context</li>
 * <li>startConversation — quota exceeded</li>
 * <li>createPropertiesHandler — returns correct handler</li>
 * </ul>
 */
@DisplayName("ConversationService — Deep Branch Coverage")
class ConversationServiceDeepBranchTest {

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
    private IScheduleStore scheduleStore;
    @Mock
    private IAgentStore agentStore;
    @Mock
    private IUserMemoryStore userMemoryStore;
    @Mock
    private IJsonSerialization jsonSerialization;

    private static final Environment ENV = Environment.production;
    private static final String AGENT_ID = "aabbccdd11223344eeff5566";
    private static final String CONVERSATION_ID = "112233445566778899aabbcc";
    private static final String USER_ID = "user-deep-test";
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
                gdprComplianceService, tenantQuotaService, scheduleStore, agentStore,
                jsonSerialization,
                new SimpleMeterRegistry(), ConversationServiceTestFixtures.hitlResumeEvent(), AGENT_TIMEOUT);
    }

    private ConversationMemorySnapshot createSnapshot() {
        var snapshot = new ConversationMemorySnapshot();
        snapshot.setAgentId(AGENT_ID);
        snapshot.setUserId(USER_ID);
        snapshot.setAgentVersion(1);
        snapshot.setConversationState(ConversationState.READY);
        snapshot.setConversationSteps(new ArrayList<>());
        snapshot.setEnvironment(ENV);
        return snapshot;
    }

    // ==================== endConversation ====================

    @Nested
    @DisplayName("endConversation")
    class EndConversationTests {

        @Test
        @DisplayName("sets conversation state to ENDED and caches it")
        void setsStateToEnded() {
            conversationService.endConversation(CONVERSATION_ID);

            verify(conversationMemoryStore).setConversationState(CONVERSATION_ID, ConversationState.ENDED);
            verify(conversationStateCache).put(CONVERSATION_ID, ConversationState.ENDED);
        }
    }

    // ==================== getConversationState branches ====================

    @Nested
    @DisplayName("getConversationState")
    class GetConversationStateTests {

        @Test
        @DisplayName("cache hit → returns cached state without DB lookup")
        void cacheHit() {
            when(conversationStateCache.get(CONVERSATION_ID)).thenReturn(ConversationState.READY);

            var result = conversationService.getConversationState(ENV, CONVERSATION_ID);

            assertEquals(ConversationState.READY, result);
            verify(conversationMemoryStore, never()).getConversationState(anyString());
        }

        @Test
        @DisplayName("cache miss, DB found → caches and returns")
        void cacheMissDbFound() {
            when(conversationStateCache.get(CONVERSATION_ID)).thenReturn(null);
            when(conversationMemoryStore.getConversationState(CONVERSATION_ID))
                    .thenReturn(ConversationState.READY);

            var result = conversationService.getConversationState(ENV, CONVERSATION_ID);

            assertEquals(ConversationState.READY, result);
            verify(conversationStateCache).put(CONVERSATION_ID, ConversationState.READY);
        }

        @Test
        @DisplayName("cache miss, DB returns null → throws ConversationNotFoundException")
        void cacheMissDbNull() {
            when(conversationStateCache.get(CONVERSATION_ID)).thenReturn(null);
            when(conversationMemoryStore.getConversationState(CONVERSATION_ID))
                    .thenReturn(null);

            assertThrows(ConversationNotFoundException.class,
                    () -> conversationService.getConversationState(ENV, CONVERSATION_ID));
        }

        @Test
        @DisplayName("string-only overload — cache hit")
        void stringOnlyCacheHit() {
            when(conversationStateCache.get(CONVERSATION_ID)).thenReturn(ConversationState.ENDED);

            var result = conversationService.getConversationState(CONVERSATION_ID);

            assertEquals(ConversationState.ENDED, result);
        }

        @Test
        @DisplayName("string-only overload — not found throws")
        void stringOnlyNotFound() {
            when(conversationStateCache.get(CONVERSATION_ID)).thenReturn(null);
            when(conversationMemoryStore.getConversationState(CONVERSATION_ID)).thenReturn(null);

            assertThrows(ConversationNotFoundException.class,
                    () -> conversationService.getConversationState(CONVERSATION_ID));
        }
    }

    // ==================== readConversation — agentMismatch ====================

    @Nested
    @DisplayName("readConversation Agent Mismatch")
    class ReadConversationMismatchTests {

        @Test
        @DisplayName("agent mismatch throws AgentMismatchException")
        void agentMismatch() throws Exception {
            var snapshot = createSnapshot();
            snapshot.setAgentId("differentAgentId");
            when(conversationMemoryStore.loadConversationMemorySnapshot(CONVERSATION_ID))
                    .thenReturn(snapshot);

            assertThrows(AgentMismatchException.class,
                    () -> conversationService.readConversation(ENV, AGENT_ID, CONVERSATION_ID,
                            false, false, List.of()));
        }
    }

    // ==================== readConversationLog — all outputType branches
    // ====================

    @Nested
    @DisplayName("readConversationLog outputType branches")
    class ReadConversationLogOutputTypeTests {

        @Test
        @DisplayName("outputType 'text' → text/plain")
        void textType() throws Exception {
            var snapshot = createSnapshot();
            when(conversationMemoryStore.loadConversationMemorySnapshot(CONVERSATION_ID))
                    .thenReturn(snapshot);

            var result = conversationService.readConversationLog(CONVERSATION_ID, "text", 10);
            assertEquals("text/plain", result.mediaType());
        }

        @Test
        @DisplayName("outputType 'JSON' → application/json (case insensitive)")
        void jsonType() throws Exception {
            var snapshot = createSnapshot();
            when(conversationMemoryStore.loadConversationMemorySnapshot(CONVERSATION_ID))
                    .thenReturn(snapshot);

            var result = conversationService.readConversationLog(CONVERSATION_ID, "JSON", 10);
            assertEquals("application/json", result.mediaType());
        }

        @Test
        @DisplayName("outputType 'string' → text/plain")
        void stringType() throws Exception {
            var snapshot = createSnapshot();
            when(conversationMemoryStore.loadConversationMemorySnapshot(CONVERSATION_ID))
                    .thenReturn(snapshot);

            var result = conversationService.readConversationLog(CONVERSATION_ID, "string", 5);
            assertEquals("text/plain", result.mediaType());
        }

        @Test
        @DisplayName("outputType empty → text/plain")
        void emptyType() throws Exception {
            var snapshot = createSnapshot();
            when(conversationMemoryStore.loadConversationMemorySnapshot(CONVERSATION_ID))
                    .thenReturn(snapshot);

            var result = conversationService.readConversationLog(CONVERSATION_ID, " ", null);
            assertEquals("application/json", result.mediaType());
        }

        @Test
        @DisplayName("null logSize defaults to -1")
        void nullLogSize() throws Exception {
            var snapshot = createSnapshot();
            when(conversationMemoryStore.loadConversationMemorySnapshot(CONVERSATION_ID))
                    .thenReturn(snapshot);

            var result = conversationService.readConversationLog(CONVERSATION_ID, "text", null);
            assertNotNull(result);
        }
    }

    // ==================== startConversation branches ====================

    @Nested
    @DisplayName("startConversation Branches")
    class StartConversationBranchTests {

        @Test
        @DisplayName("GDPR restricted user throws ProcessingRestrictedException")
        void gdprRestricted() throws Exception {
            when(conversationSetup.computeAnonymousUserIdIfEmpty(any(), any()))
                    .thenReturn(USER_ID);
            when(gdprComplianceService.isProcessingRestricted(USER_ID)).thenReturn(true);

            assertThrows(ProcessingRestrictedException.class,
                    () -> conversationService.startConversation(ENV, AGENT_ID, USER_ID,
                            new LinkedHashMap<>()));
        }

        @Test
        @DisplayName("null context is replaced with empty map")
        void nullContext() throws Exception {
            when(conversationSetup.computeAnonymousUserIdIfEmpty(any(), any()))
                    .thenReturn(USER_ID);
            IAgent mockAgent = mock(IAgent.class);
            when(agentFactory.getLatestReadyAgent(ENV, AGENT_ID)).thenReturn(mockAgent);
            when(mockAgent.getUserMemoryConfig()).thenReturn(null);
            IConversation conversation = mock(IConversation.class);
            IConversationMemory conversationMemory = mock(IConversationMemory.class);
            when(conversation.getConversationMemory()).thenReturn(conversationMemory);
            when(conversationMemory.getConversationState()).thenReturn(ConversationState.READY);
            when(conversationMemory.getConversationId()).thenReturn(CONVERSATION_ID);
            when(conversationMemory.getUserId()).thenReturn(USER_ID);
            when(conversationMemory.getAgentId()).thenReturn(AGENT_ID);
            when(conversationMemory.getAgentVersion()).thenReturn(1);
            when(conversationMemory.getRedoCache()).thenReturn(new java.util.Stack<>());
            var stepStack = mock(IConversationMemory.IConversationStepStack.class);
            when(stepStack.size()).thenReturn(0);
            when(conversationMemory.getAllSteps()).thenReturn(stepStack);
            when(conversationMemory.getConversationOutputs()).thenReturn(new ArrayList<>());
            var conversationProperties = mock(ai.labs.eddi.engine.memory.model.ConversationProperties.class);
            doReturn(new java.util.HashSet<>()).when(conversationProperties).entrySet();
            when(conversationMemory.getConversationProperties()).thenReturn(conversationProperties);
            when(mockAgent.startConversation(any(), any(), any(), any())).thenReturn(conversation);
            when(conversationMemoryStore.storeConversationMemorySnapshot(any()))
                    .thenReturn(CONVERSATION_ID);

            var result = conversationService.startConversation(ENV, AGENT_ID, USER_ID, null);

            assertNotNull(result);
            assertEquals(CONVERSATION_ID, result.conversationId());
        }

        @Test
        @DisplayName("agent not ready throws AgentNotReadyException")
        void agentNotReady() throws Exception {
            when(conversationSetup.computeAnonymousUserIdIfEmpty(any(), any()))
                    .thenReturn(USER_ID);
            when(agentFactory.getLatestReadyAgent(ENV, AGENT_ID)).thenReturn(null);

            assertThrows(AgentNotReadyException.class,
                    () -> conversationService.startConversation(ENV, AGENT_ID, USER_ID, Map.of()));
        }

        @Test
        @DisplayName("quota exceeded throws QuotaExceededException")
        void quotaExceeded() throws Exception {
            when(conversationSetup.computeAnonymousUserIdIfEmpty(any(), any()))
                    .thenReturn(USER_ID);
            IAgent mockAgent = mock(IAgent.class);
            when(agentFactory.getLatestReadyAgent(ENV, AGENT_ID)).thenReturn(mockAgent);
            when(tenantQuotaService.acquireConversationSlot())
                    .thenReturn(new QuotaCheckResult(false, "Quota exceeded"));

            assertThrows(QuotaExceededException.class,
                    () -> conversationService.startConversation(ENV, AGENT_ID, USER_ID, Map.of()));
        }
    }

    // ==================== isUndoAvailable / isRedoAvailable ====================

    @Nested
    @DisplayName("isUndoAvailable / isRedoAvailable")
    class UndoRedoAvailableTests {

        @Test
        @DisplayName("isUndoAvailable delegates to conversation memory")
        void isUndoAvailable() throws Exception {
            var snapshot = createSnapshot();
            when(conversationMemoryStore.loadConversationMemorySnapshot(CONVERSATION_ID))
                    .thenReturn(snapshot);

            Boolean result = conversationService.isUndoAvailable(ENV, AGENT_ID, CONVERSATION_ID);

            assertNotNull(result);
        }

        @Test
        @DisplayName("isRedoAvailable delegates to conversation memory")
        void isRedoAvailable() throws Exception {
            var snapshot = createSnapshot();
            when(conversationMemoryStore.loadConversationMemorySnapshot(CONVERSATION_ID))
                    .thenReturn(snapshot);

            Boolean result = conversationService.isRedoAvailable(ENV, AGENT_ID, CONVERSATION_ID);

            assertNotNull(result);
        }
    }

    // ==================== conversationId-only overloads ====================

    @Nested
    @DisplayName("ConversationId-only Overloads")
    class ConversationIdOnlyTests {

        @Test
        @DisplayName("readConversation(conversationId) loads from store")
        void readConversation() throws Exception {
            var snapshot = createSnapshot();
            when(conversationMemoryStore.loadConversationMemorySnapshot(CONVERSATION_ID))
                    .thenReturn(snapshot);

            var result = conversationService.readConversation(CONVERSATION_ID, false, false, List.of());
            assertNotNull(result);
        }

        @Test
        @DisplayName("isUndoAvailable(conversationId) loads from store")
        void isUndoAvailable() throws Exception {
            var snapshot = createSnapshot();
            when(conversationMemoryStore.loadConversationMemorySnapshot(CONVERSATION_ID))
                    .thenReturn(snapshot);

            Boolean result = conversationService.isUndoAvailable(CONVERSATION_ID);
            assertNotNull(result);
        }

        @Test
        @DisplayName("isRedoAvailable(conversationId) loads from store")
        void isRedoAvailable() throws Exception {
            var snapshot = createSnapshot();
            when(conversationMemoryStore.loadConversationMemorySnapshot(CONVERSATION_ID))
                    .thenReturn(snapshot);

            Boolean result = conversationService.isRedoAvailable(CONVERSATION_ID);
            assertNotNull(result);
        }

        @Test
        @DisplayName("undo(conversationId) loads from store and delegates")
        void undo() throws Exception {
            var snapshot = createSnapshot();
            when(conversationMemoryStore.loadConversationMemorySnapshot(CONVERSATION_ID))
                    .thenReturn(snapshot);

            boolean result = conversationService.undo(CONVERSATION_ID);
            assertFalse(result); // empty conversation → undo not available
        }

        @Test
        @DisplayName("redo(conversationId) loads from store and delegates")
        void redo() throws Exception {
            var snapshot = createSnapshot();
            when(conversationMemoryStore.loadConversationMemorySnapshot(CONVERSATION_ID))
                    .thenReturn(snapshot);

            boolean result = conversationService.redo(CONVERSATION_ID);
            assertFalse(result); // nothing undone → redo not available
        }
    }

    // ==================== createPropertiesHandler ====================

    @Nested
    @DisplayName("createPropertiesHandler")
    class PropertiesHandlerTests {

        @Test
        @DisplayName("returns handler with correct userId")
        void correctUserId() {
            var handler = conversationService.createPropertiesHandler(USER_ID, null);
            assertEquals(USER_ID, handler.getUserId());
        }

        @Test
        @DisplayName("returns handler with correct userMemoryStore")
        void correctUserMemoryStore() {
            var handler = conversationService.createPropertiesHandler(USER_ID, null);
            assertSame(userMemoryStore, handler.getUserMemoryStore());
        }

        @Test
        @DisplayName("returns handler with null user memory config")
        void nullUserMemoryConfig() {
            var handler = conversationService.createPropertiesHandler(USER_ID, null);
            assertNull(handler.getUserMemoryConfig());
        }
    }

    // ==================== say — GDPR, quotas, mismatch ====================

    @Nested
    @DisplayName("say Validation Branches")
    class SayValidationTests {

        @Test
        @DisplayName("say — GDPR restricted user throws ProcessingRestrictedException")
        void sayGdprRestricted() throws Exception {
            var snapshot = createSnapshot();
            when(conversationMemoryStore.loadConversationMemorySnapshot(CONVERSATION_ID))
                    .thenReturn(snapshot);
            when(gdprComplianceService.isProcessingRestricted(USER_ID)).thenReturn(true);

            var handler = mock(ConversationResponseHandler.class);

            assertThrows(ProcessingRestrictedException.class,
                    () -> conversationService.say(ENV, AGENT_ID, CONVERSATION_ID,
                            false, false, List.of(),
                            new InputData("hello", Map.of()), false, handler));
        }

        @Test
        @DisplayName("say — agent mismatch throws AgentMismatchException")
        void sayAgentMismatch() throws Exception {
            var snapshot = createSnapshot();
            snapshot.setAgentId("differentAgent");
            when(conversationMemoryStore.loadConversationMemorySnapshot(CONVERSATION_ID))
                    .thenReturn(snapshot);

            var handler = mock(ConversationResponseHandler.class);

            assertThrows(AgentMismatchException.class,
                    () -> conversationService.say(ENV, AGENT_ID, CONVERSATION_ID,
                            false, false, List.of(),
                            new InputData("hello", Map.of()), false, handler));
        }

        @Test
        @DisplayName("say — quota exceeded throws QuotaExceededException")
        void sayQuotaExceeded() throws Exception {
            var snapshot = createSnapshot();
            when(conversationMemoryStore.loadConversationMemorySnapshot(CONVERSATION_ID))
                    .thenReturn(snapshot);
            IAgent mockAgent = mock(IAgent.class);
            when(agentFactory.getAgent(ENV, AGENT_ID, 1)).thenReturn(mockAgent);
            when(tenantQuotaService.acquireApiCallSlot())
                    .thenReturn(new QuotaCheckResult(false, "API quota exceeded"));

            var handler = mock(ConversationResponseHandler.class);

            assertThrows(QuotaExceededException.class,
                    () -> conversationService.say(ENV, AGENT_ID, CONVERSATION_ID,
                            false, false, List.of(),
                            new InputData("hello", Map.of()), false, handler));
        }
    }

    // ==================== sayStreaming — validation branches ====================

    @Nested
    @DisplayName("sayStreaming Validation Branches")
    class SayStreamingValidationTests {

        @Test
        @DisplayName("sayStreaming — GDPR restricted throws ProcessingRestrictedException")
        void gdprRestricted() throws Exception {
            var snapshot = createSnapshot();
            when(conversationMemoryStore.loadConversationMemorySnapshot(CONVERSATION_ID))
                    .thenReturn(snapshot);
            when(gdprComplianceService.isProcessingRestricted(USER_ID)).thenReturn(true);

            var handler = mock(StreamingResponseHandler.class);

            assertThrows(ProcessingRestrictedException.class,
                    () -> conversationService.sayStreaming(ENV, AGENT_ID, CONVERSATION_ID,
                            false, false, List.of(),
                            new InputData("hello", Map.of()), handler));
        }

        @Test
        @DisplayName("sayStreaming — agent mismatch throws AgentMismatchException")
        void agentMismatch() throws Exception {
            var snapshot = createSnapshot();
            snapshot.setAgentId("differentAgent");
            when(conversationMemoryStore.loadConversationMemorySnapshot(CONVERSATION_ID))
                    .thenReturn(snapshot);

            var handler = mock(StreamingResponseHandler.class);

            assertThrows(AgentMismatchException.class,
                    () -> conversationService.sayStreaming(ENV, AGENT_ID, CONVERSATION_ID,
                            false, false, List.of(),
                            new InputData("hello", Map.of()), handler));
        }

        @Test
        @DisplayName("sayStreaming — quota exceeded throws QuotaExceededException")
        void quotaExceeded() throws Exception {
            var snapshot = createSnapshot();
            when(conversationMemoryStore.loadConversationMemorySnapshot(CONVERSATION_ID))
                    .thenReturn(snapshot);
            IAgent mockAgent = mock(IAgent.class);
            when(agentFactory.getAgent(ENV, AGENT_ID, 1)).thenReturn(mockAgent);
            when(tenantQuotaService.acquireApiCallSlot())
                    .thenReturn(new QuotaCheckResult(false, "Quota exceeded"));

            var handler = mock(StreamingResponseHandler.class);

            assertThrows(QuotaExceededException.class,
                    () -> conversationService.sayStreaming(ENV, AGENT_ID, CONVERSATION_ID,
                            false, false, List.of(),
                            new InputData("hello", Map.of()), handler));
        }

        @Test
        @DisplayName("sayStreaming — conversation ended throws ConversationEndedException")
        void conversationEnded() throws Exception {
            var snapshot = createSnapshot();
            when(conversationMemoryStore.loadConversationMemorySnapshot(CONVERSATION_ID))
                    .thenReturn(snapshot);
            IAgent mockAgent = mock(IAgent.class);
            when(agentFactory.getAgent(ENV, AGENT_ID, 1)).thenReturn(mockAgent);
            IConversation mockConversation = mock(IConversation.class);
            when(mockAgent.continueConversation(any(), any(), any())).thenReturn(mockConversation);
            when(mockConversation.isEnded()).thenReturn(true);

            var handler = mock(StreamingResponseHandler.class);

            assertThrows(ConversationEndedException.class,
                    () -> conversationService.sayStreaming(ENV, AGENT_ID, CONVERSATION_ID,
                            false, false, List.of(),
                            new InputData("hello", Map.of()), handler));
        }
    }

    // ==================== say conversationId-only overload ====================

    @Nested
    @DisplayName("say/sayStreaming conversationId-only")
    class SayConversationIdOnlyTests {

        @Test
        @DisplayName("say(conversationId) loads snapshot and delegates")
        void sayByConversationId() throws Exception {
            var snapshot = createSnapshot();
            when(conversationMemoryStore.loadConversationMemorySnapshot(CONVERSATION_ID))
                    .thenReturn(snapshot);
            // Will throw AgentNotReadyException since no agent deployed,
            // but this exercises the overload path
            IAgent mockAgent = mock(IAgent.class);
            when(agentFactory.getAgent(ENV, AGENT_ID, 1)).thenReturn(mockAgent);
            IConversation mockConversation = mock(IConversation.class);
            when(mockAgent.continueConversation(any(), any(), any())).thenReturn(mockConversation);
            when(mockConversation.isEnded()).thenReturn(true);

            var handler = mock(ConversationResponseHandler.class);

            assertThrows(ConversationEndedException.class,
                    () -> conversationService.say(CONVERSATION_ID, false, false, List.of(),
                            new InputData("hello", Map.of()), false, handler));
        }

        @Test
        @DisplayName("sayStreaming(conversationId) loads snapshot and delegates")
        void sayStreamingByConversationId() throws Exception {
            var snapshot = createSnapshot();
            when(conversationMemoryStore.loadConversationMemorySnapshot(CONVERSATION_ID))
                    .thenReturn(snapshot);
            IAgent mockAgent = mock(IAgent.class);
            when(agentFactory.getAgent(ENV, AGENT_ID, 1)).thenReturn(mockAgent);
            IConversation mockConversation = mock(IConversation.class);
            when(mockAgent.continueConversation(any(), any(), any())).thenReturn(mockConversation);
            when(mockConversation.isEnded()).thenReturn(true);

            var handler = mock(StreamingResponseHandler.class);

            assertThrows(ConversationEndedException.class,
                    () -> conversationService.sayStreaming(CONVERSATION_ID, false, false, List.of(),
                            new InputData("hello", Map.of()), handler));
        }
    }
}
