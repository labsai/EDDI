/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.configs.properties.IUserMemoryStore;
import ai.labs.eddi.datastore.IResourceStore.ResourceNotFoundException;
import ai.labs.eddi.datastore.IResourceStore.ResourceStoreException;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.api.IConversationService.AgentMismatchException;
import ai.labs.eddi.engine.api.IConversationService.AgentNotReadyException;
import ai.labs.eddi.engine.api.IConversationService.ConversationNotFoundException;
import ai.labs.eddi.engine.api.IConversationService.ConversationResult;
import ai.labs.eddi.engine.audit.AuditLedgerService;
import ai.labs.eddi.engine.caching.ICache;
import ai.labs.eddi.engine.caching.ICacheFactory;
import ai.labs.eddi.engine.gdpr.GdprComplianceService;
import ai.labs.eddi.engine.gdpr.ProcessingRestrictedException;
import ai.labs.eddi.engine.lifecycle.IConversation;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.memory.IPropertiesHandler;
import ai.labs.eddi.engine.memory.descriptor.IConversationDescriptorStore;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot.ConversationStepSnapshot;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot.WorkflowRunSnapshot;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot.ResultSnapshot;
import ai.labs.eddi.engine.memory.model.ConversationOutput;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot;
import ai.labs.eddi.engine.model.Context;
import ai.labs.eddi.engine.model.Deployment.Environment;
import ai.labs.eddi.engine.runtime.IAgent;
import ai.labs.eddi.engine.runtime.IAgentFactory;
import ai.labs.eddi.engine.runtime.IConversationCoordinator;
import ai.labs.eddi.engine.runtime.IConversationSetup;
import ai.labs.eddi.engine.runtime.IRuntime;
import ai.labs.eddi.engine.tenancy.QuotaExceededException;
import ai.labs.eddi.engine.tenancy.TenantQuotaService;
import ai.labs.eddi.engine.tenancy.model.QuotaCheckResult;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Comprehensive unit tests for {@link ConversationService}.
 */
class ConversationServiceTest {

    private static final Environment ENV = Environment.production;
    private static final String AGENT_ID = "agent-123";
    private static final String CONVERSATION_ID = "conv-456";
    private static final String USER_ID = "user-789";
    private static final int AGENT_TIMEOUT = 30;

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
                gdprComplianceService, tenantQuotaService,
                new SimpleMeterRegistry(), AGENT_TIMEOUT);
    }

    // =========================================================================
    // startConversation
    // =========================================================================

    @Nested
    @DisplayName("startConversation")
    class StartConversation {

        @Test
        @DisplayName("null environment → IllegalArgumentException")
        void nullEnvironment_throwsIllegalArgument() {
            var ex = assertThrows(IllegalArgumentException.class,
                    () -> conversationService.startConversation(null, AGENT_ID, USER_ID, Map.of()));
            assertTrue(ex.getMessage().contains("must not be null"));
        }

        @Test
        @DisplayName("null agentId → IllegalArgumentException")
        void nullAgentId_throwsIllegalArgument() {
            var ex = assertThrows(IllegalArgumentException.class,
                    () -> conversationService.startConversation(ENV, null, USER_ID, Map.of()));
            assertTrue(ex.getMessage().contains("must not be null"));
        }

        @Test
        @DisplayName("null context defaults to LinkedHashMap, proceeds normally")
        void nullContext_defaultsToEmptyMap() throws Exception {
            // Arrange
            IAgent agent = mock(IAgent.class);
            IConversation conversation = mock(IConversation.class);
            IConversationMemory memory = mock(IConversationMemory.class);

            doReturn(USER_ID).when(conversationSetup).computeAnonymousUserIdIfEmpty(eq(USER_ID), isNull());
            doReturn(false).when(gdprComplianceService).isProcessingRestricted(USER_ID);
            doReturn(agent).when(agentFactory).getLatestReadyAgent(ENV, AGENT_ID);
            doReturn(new QuotaCheckResult(true, null)).when(tenantQuotaService).acquireConversationSlot();
            doReturn(conversation).when(agent).startConversation(eq(USER_ID), anyMap(), any(), isNull());
            doReturn(memory).when(conversation).getConversationMemory();
            doReturn(ConversationState.READY).when(memory).getConversationState();
            doReturn(new java.util.Stack<>()).when(memory).getRedoCache();
            doReturn(mock(IConversationMemory.IConversationStepStack.class)).when(memory).getAllSteps();
            doReturn(new ai.labs.eddi.engine.memory.model.ConversationProperties(memory)).when(memory).getConversationProperties();
            doReturn(CONVERSATION_ID).when(conversationMemoryStore).storeConversationMemorySnapshot(any());

            // Act — null context should NOT throw
            ConversationResult result = conversationService.startConversation(ENV, AGENT_ID, USER_ID, null);

            // Assert
            assertNotNull(result);
            assertEquals(CONVERSATION_ID, result.conversationId());
        }

        @Test
        @DisplayName("GDPR restricted user → ProcessingRestrictedException")
        void gdprRestricted_throwsProcessingRestrictedException() {
            doReturn(USER_ID).when(conversationSetup).computeAnonymousUserIdIfEmpty(eq(USER_ID), isNull());
            doReturn(true).when(gdprComplianceService).isProcessingRestricted(USER_ID);

            assertThrows(ProcessingRestrictedException.class,
                    () -> conversationService.startConversation(ENV, AGENT_ID, USER_ID, null));
        }

        @Test
        @DisplayName("agent not ready → AgentNotReadyException")
        void agentNotReady_throwsAgentNotReadyException() throws Exception {
            doReturn(USER_ID).when(conversationSetup).computeAnonymousUserIdIfEmpty(eq(USER_ID), isNull());
            doReturn(false).when(gdprComplianceService).isProcessingRestricted(USER_ID);
            doReturn(null).when(agentFactory).getLatestReadyAgent(ENV, AGENT_ID);

            var ex = assertThrows(AgentNotReadyException.class,
                    () -> conversationService.startConversation(ENV, AGENT_ID, USER_ID, null));
            assertTrue(ex.getMessage().contains(AGENT_ID));
        }

        @Test
        @DisplayName("quota exceeded → QuotaExceededException")
        void quotaExceeded_throwsQuotaExceededException() throws Exception {
            IAgent agent = mock(IAgent.class);

            doReturn(USER_ID).when(conversationSetup).computeAnonymousUserIdIfEmpty(eq(USER_ID), isNull());
            doReturn(false).when(gdprComplianceService).isProcessingRestricted(USER_ID);
            doReturn(agent).when(agentFactory).getLatestReadyAgent(ENV, AGENT_ID);
            doReturn(new QuotaCheckResult(false, "limit reached")).when(tenantQuotaService).acquireConversationSlot();

            var ex = assertThrows(QuotaExceededException.class,
                    () -> conversationService.startConversation(ENV, AGENT_ID, USER_ID, null));
            assertTrue(ex.getMessage().contains("limit reached"));
        }

        @Test
        @DisplayName("happy path → returns ConversationResult with id and URI")
        void happyPath_returnsConversationResult() throws Exception {
            IAgent agent = mock(IAgent.class);
            IConversation conversation = mock(IConversation.class);
            IConversationMemory memory = mock(IConversationMemory.class);
            Map<String, Context> context = new LinkedHashMap<>();

            doReturn(USER_ID).when(conversationSetup).computeAnonymousUserIdIfEmpty(eq(USER_ID), isNull());
            doReturn(false).when(gdprComplianceService).isProcessingRestricted(USER_ID);
            doReturn(agent).when(agentFactory).getLatestReadyAgent(ENV, AGENT_ID);
            doReturn(new QuotaCheckResult(true, null)).when(tenantQuotaService).acquireConversationSlot();
            doReturn(conversation).when(agent).startConversation(eq(USER_ID), eq(context), any(), isNull());
            doReturn(memory).when(conversation).getConversationMemory();
            doReturn(ConversationState.READY).when(memory).getConversationState();
            doReturn(new java.util.Stack<>()).when(memory).getRedoCache();
            doReturn(mock(IConversationMemory.IConversationStepStack.class)).when(memory).getAllSteps();
            doReturn(new ai.labs.eddi.engine.memory.model.ConversationProperties(memory)).when(memory).getConversationProperties();
            doReturn(CONVERSATION_ID).when(conversationMemoryStore).storeConversationMemorySnapshot(any());

            ConversationResult result = conversationService.startConversation(ENV, AGENT_ID, USER_ID, context);

            assertNotNull(result);
            assertEquals(CONVERSATION_ID, result.conversationId());
            assertNotNull(result.conversationUri());
            assertTrue(result.conversationUri().toString().contains(CONVERSATION_ID));

            verify(conversationStateCache).put(eq(CONVERSATION_ID), eq(ConversationState.READY));
            verify(conversationSetup).createConversationDescriptor(eq(AGENT_ID), eq(agent), eq(USER_ID), eq(CONVERSATION_ID), any());
        }
    }

    // =========================================================================
    // endConversation
    // =========================================================================

    @Nested
    @DisplayName("endConversation")
    class EndConversation {

        @Test
        @DisplayName("sets state to ENDED and records metrics")
        void setsStateToEnded() {
            conversationService.endConversation(CONVERSATION_ID);

            verify(conversationMemoryStore).setConversationState(CONVERSATION_ID, ConversationState.ENDED);
            verify(conversationStateCache).put(CONVERSATION_ID, ConversationState.ENDED);
        }
    }

    // =========================================================================
    // getConversationState(Environment, conversationId)
    // =========================================================================

    @Nested
    @DisplayName("getConversationState(env, convId)")
    class GetConversationStateWithEnv {

        @Test
        @DisplayName("cache hit → returns state directly")
        void cacheHit_returnsDirectly() {
            doReturn(ConversationState.READY).when(conversationStateCache).get(CONVERSATION_ID);

            ConversationState result = conversationService.getConversationState(ENV, CONVERSATION_ID);

            assertEquals(ConversationState.READY, result);
            verify(conversationMemoryStore, never()).getConversationState(any());
        }

        @Test
        @DisplayName("cache miss → loads from store and caches")
        void cacheMiss_loadsFromStore() {
            doReturn(null).when(conversationStateCache).get(CONVERSATION_ID);
            doReturn(ConversationState.IN_PROGRESS).when(conversationMemoryStore).getConversationState(CONVERSATION_ID);

            ConversationState result = conversationService.getConversationState(ENV, CONVERSATION_ID);

            assertEquals(ConversationState.IN_PROGRESS, result);
            verify(conversationStateCache).put(CONVERSATION_ID, ConversationState.IN_PROGRESS);
        }

        @Test
        @DisplayName("not found in cache or store → ConversationNotFoundException")
        void notFound_throwsConversationNotFoundException() {
            doReturn(null).when(conversationStateCache).get(CONVERSATION_ID);
            doReturn(null).when(conversationMemoryStore).getConversationState(CONVERSATION_ID);

            var ex = assertThrows(ConversationNotFoundException.class,
                    () -> conversationService.getConversationState(ENV, CONVERSATION_ID));
            assertTrue(ex.getMessage().contains(CONVERSATION_ID));
        }
    }

    // =========================================================================
    // getConversationState(conversationId) — single-arg overload
    // =========================================================================

    @Nested
    @DisplayName("getConversationState(convId)")
    class GetConversationStateSingleArg {

        @Test
        @DisplayName("cache hit → returns state directly")
        void cacheHit_returnsDirectly() {
            doReturn(ConversationState.ENDED).when(conversationStateCache).get(CONVERSATION_ID);

            ConversationState result = conversationService.getConversationState(CONVERSATION_ID);

            assertEquals(ConversationState.ENDED, result);
        }

        @Test
        @DisplayName("cache miss → loads from store")
        void cacheMiss_loadsFromStore() {
            doReturn(null).when(conversationStateCache).get(CONVERSATION_ID);
            doReturn(ConversationState.READY).when(conversationMemoryStore).getConversationState(CONVERSATION_ID);

            assertEquals(ConversationState.READY, conversationService.getConversationState(CONVERSATION_ID));
        }

        @Test
        @DisplayName("not found → ConversationNotFoundException")
        void notFound_throwsConversationNotFoundException() {
            doReturn(null).when(conversationStateCache).get(CONVERSATION_ID);
            doReturn(null).when(conversationMemoryStore).getConversationState(CONVERSATION_ID);

            assertThrows(ConversationNotFoundException.class,
                    () -> conversationService.getConversationState(CONVERSATION_ID));
        }
    }

    // =========================================================================
    // readConversation(env, agentId, convId, ...)
    // =========================================================================

    @Nested
    @DisplayName("readConversation(env, agentId, convId, ...)")
    class ReadConversationWithEnv {

        @Test
        @DisplayName("agent mismatch → AgentMismatchException")
        void agentMismatch_throwsAgentMismatchException() throws Exception {
            var snapshot = createMinimalSnapshot("different-agent", USER_ID);
            doReturn(snapshot).when(conversationMemoryStore).loadConversationMemorySnapshot(CONVERSATION_ID);
            doReturn(new HashMap<>()).when(contextLogger).createLoggingContext(any(), any(), any(), any());

            assertThrows(AgentMismatchException.class,
                    () -> conversationService.readConversation(ENV, AGENT_ID, CONVERSATION_ID, false, false, null));
        }

        @Test
        @DisplayName("matching agentId → returns SimpleConversationMemorySnapshot")
        void matchingAgent_returnsSnapshot() throws Exception {
            var snapshot = createMinimalSnapshot(AGENT_ID, USER_ID);
            doReturn(snapshot).when(conversationMemoryStore).loadConversationMemorySnapshot(CONVERSATION_ID);
            doReturn(new HashMap<>()).when(contextLogger).createLoggingContext(any(), any(), any(), any());

            SimpleConversationMemorySnapshot result = conversationService.readConversation(ENV, AGENT_ID, CONVERSATION_ID, false, false, null);

            assertNotNull(result);
            assertEquals(AGENT_ID, result.getAgentId());
        }
    }

    // =========================================================================
    // readConversation(conversationId, ...) — single-arg overload
    // =========================================================================

    @Nested
    @DisplayName("readConversation(convId, ...)")
    class ReadConversationSingleArg {

        @Test
        @DisplayName("loads from store and returns snapshot")
        void loadsAndReturnsSnapshot() throws Exception {
            var snapshot = createMinimalSnapshot(AGENT_ID, USER_ID);
            snapshot.setEnvironment(ENV);
            doReturn(snapshot).when(conversationMemoryStore).loadConversationMemorySnapshot(CONVERSATION_ID);
            doReturn(new HashMap<>()).when(contextLogger).createLoggingContext(any(), any(), any(), any());

            SimpleConversationMemorySnapshot result = conversationService.readConversation(CONVERSATION_ID, false, false, null);

            assertNotNull(result);
            assertEquals(AGENT_ID, result.getAgentId());
        }
    }

    // =========================================================================
    // readConversationLog
    // =========================================================================

    @Nested
    @DisplayName("readConversationLog")
    class ReadConversationLog {

        @Test
        @DisplayName("text output type → TEXT_PLAIN media type")
        void textOutput_returnsTextPlain() throws Exception {
            var snapshot = createMinimalSnapshot(AGENT_ID, USER_ID);
            doReturn(snapshot).when(conversationMemoryStore).loadConversationMemorySnapshot(CONVERSATION_ID);

            IConversationService.ConversationLogResult result = conversationService.readConversationLog(CONVERSATION_ID, "text", null);

            assertNotNull(result);
            assertEquals("text/plain", result.mediaType());
        }

        @Test
        @DisplayName("string output type → TEXT_PLAIN media type")
        void stringOutput_returnsTextPlain() throws Exception {
            var snapshot = createMinimalSnapshot(AGENT_ID, USER_ID);
            doReturn(snapshot).when(conversationMemoryStore).loadConversationMemorySnapshot(CONVERSATION_ID);

            IConversationService.ConversationLogResult result = conversationService.readConversationLog(CONVERSATION_ID, "string", null);

            assertNotNull(result);
            assertEquals("text/plain", result.mediaType());
        }

        @Test
        @DisplayName("json output type → APPLICATION_JSON media type")
        void jsonOutput_returnsApplicationJson() throws Exception {
            var snapshot = createMinimalSnapshot(AGENT_ID, USER_ID);
            doReturn(snapshot).when(conversationMemoryStore).loadConversationMemorySnapshot(CONVERSATION_ID);

            IConversationService.ConversationLogResult result = conversationService.readConversationLog(CONVERSATION_ID, "json", null);

            assertNotNull(result);
            assertEquals("application/json", result.mediaType());
        }
    }

    // =========================================================================
    // undo
    // =========================================================================

    @Nested
    @DisplayName("undo")
    class Undo {

        @Test
        @DisplayName("undo available → undoes step, stores memory, returns true")
        void undoAvailable_returnsTrue() throws Exception {
            var snapshot = createSnapshotWithMultipleSteps(AGENT_ID);
            doReturn(snapshot).when(conversationMemoryStore).loadConversationMemorySnapshot(CONVERSATION_ID);
            doReturn(CONVERSATION_ID).when(conversationMemoryStore).storeConversationMemorySnapshot(any());

            boolean result = conversationService.undo(ENV, AGENT_ID, CONVERSATION_ID);

            assertTrue(result);
            verify(conversationMemoryStore).storeConversationMemorySnapshot(any());
        }

        @Test
        @DisplayName("undo not available → returns false")
        void undoNotAvailable_returnsFalse() throws Exception {
            // Single step snapshot — undo not available
            var snapshot = createMinimalSnapshot(AGENT_ID, USER_ID);
            doReturn(snapshot).when(conversationMemoryStore).loadConversationMemorySnapshot(CONVERSATION_ID);

            boolean result = conversationService.undo(ENV, AGENT_ID, CONVERSATION_ID);

            assertFalse(result);
        }
    }

    // =========================================================================
    // redo
    // =========================================================================

    @Nested
    @DisplayName("redo")
    class Redo {

        @Test
        @DisplayName("redo available → redoes step, stores memory, returns true")
        void redoAvailable_returnsTrue() throws Exception {
            var snapshot = createSnapshotWithRedoCache(AGENT_ID);
            doReturn(snapshot).when(conversationMemoryStore).loadConversationMemorySnapshot(CONVERSATION_ID);
            doReturn(CONVERSATION_ID).when(conversationMemoryStore).storeConversationMemorySnapshot(any());

            boolean result = conversationService.redo(ENV, AGENT_ID, CONVERSATION_ID);

            assertTrue(result);
            verify(conversationMemoryStore).storeConversationMemorySnapshot(any());
        }

        @Test
        @DisplayName("redo not available → returns false")
        void redoNotAvailable_returnsFalse() throws Exception {
            var snapshot = createMinimalSnapshot(AGENT_ID, USER_ID);
            doReturn(snapshot).when(conversationMemoryStore).loadConversationMemorySnapshot(CONVERSATION_ID);

            boolean result = conversationService.redo(ENV, AGENT_ID, CONVERSATION_ID);

            assertFalse(result);
        }
    }

    // =========================================================================
    // isUndoAvailable / isRedoAvailable
    // =========================================================================

    @Nested
    @DisplayName("isUndoAvailable / isRedoAvailable")
    class UndoRedoAvailability {

        @Test
        @DisplayName("isUndoAvailable with multiple steps → true")
        void isUndoAvailable_multipleSteps_returnsTrue() throws Exception {
            var snapshot = createSnapshotWithMultipleSteps(AGENT_ID);
            doReturn(snapshot).when(conversationMemoryStore).loadConversationMemorySnapshot(CONVERSATION_ID);

            Boolean result = conversationService.isUndoAvailable(ENV, AGENT_ID, CONVERSATION_ID);

            assertTrue(result);
        }

        @Test
        @DisplayName("isUndoAvailable with single step → false")
        void isUndoAvailable_singleStep_returnsFalse() throws Exception {
            var snapshot = createMinimalSnapshot(AGENT_ID, USER_ID);
            doReturn(snapshot).when(conversationMemoryStore).loadConversationMemorySnapshot(CONVERSATION_ID);

            Boolean result = conversationService.isUndoAvailable(ENV, AGENT_ID, CONVERSATION_ID);

            assertFalse(result);
        }

        @Test
        @DisplayName("isRedoAvailable with redo cache → true")
        void isRedoAvailable_withRedoCache_returnsTrue() throws Exception {
            var snapshot = createSnapshotWithRedoCache(AGENT_ID);
            doReturn(snapshot).when(conversationMemoryStore).loadConversationMemorySnapshot(CONVERSATION_ID);

            Boolean result = conversationService.isRedoAvailable(ENV, AGENT_ID, CONVERSATION_ID);

            assertTrue(result);
        }

        @Test
        @DisplayName("isRedoAvailable with empty redo cache → false")
        void isRedoAvailable_emptyRedoCache_returnsFalse() throws Exception {
            var snapshot = createMinimalSnapshot(AGENT_ID, USER_ID);
            doReturn(snapshot).when(conversationMemoryStore).loadConversationMemorySnapshot(CONVERSATION_ID);

            Boolean result = conversationService.isRedoAvailable(ENV, AGENT_ID, CONVERSATION_ID);

            assertFalse(result);
        }
    }

    // =========================================================================
    // createPropertiesHandler
    // =========================================================================

    @Nested
    @DisplayName("createPropertiesHandler")
    class CreatePropertiesHandler {

        @Test
        @DisplayName("returns handler with correct userId and store")
        void returnsHandlerWithCorrectUserIdAndStore() {
            IPropertiesHandler handler = conversationService.createPropertiesHandler(USER_ID, null);

            assertNotNull(handler);
            assertEquals(USER_ID, handler.getUserId());
            assertSame(userMemoryStore, handler.getUserMemoryStore());
            assertNull(handler.getUserMemoryConfig());
        }
    }

    // =========================================================================
    // readConversationLog — additional branches
    // =========================================================================

    @Nested
    @DisplayName("readConversationLog — additional branches")
    class ReadConversationLogBranches {

        @Test
        @DisplayName("logSize non-null → passed to generator")
        void logSizeNonNull_passedToGenerator() throws Exception {
            var snapshot = createMinimalSnapshot(AGENT_ID, USER_ID);
            doReturn(snapshot).when(conversationMemoryStore).loadConversationMemorySnapshot(CONVERSATION_ID);

            IConversationService.ConversationLogResult result = conversationService.readConversationLog(CONVERSATION_ID, "json", 5);

            assertNotNull(result);
            assertEquals("application/json", result.mediaType());
        }

        @Test
        @DisplayName("empty outputType → isNullOrEmpty true branch → TEXT_PLAIN")
        void emptyOutputType_returnsTextPlain() throws Exception {
            var snapshot = createMinimalSnapshot(AGENT_ID, USER_ID);
            doReturn(snapshot).when(conversationMemoryStore).loadConversationMemorySnapshot(CONVERSATION_ID);

            IConversationService.ConversationLogResult result = conversationService.readConversationLog(CONVERSATION_ID, "", null);

            assertNotNull(result);
            assertEquals("text/plain", result.mediaType());
        }
    }

    // =========================================================================
    // Single-arg say / sayStreaming
    // =========================================================================

    @Nested
    @DisplayName("say(conversationId, ...) — single-arg overload")
    class SaySingleArg {

        @Test
        @DisplayName("loads snapshot and delegates to env-based say")
        void delegatesToEnvBasedSay() throws Exception {
            var snapshot = createMinimalSnapshot(AGENT_ID, USER_ID);
            snapshot.setEnvironment(ENV);
            doReturn(snapshot).when(conversationMemoryStore).loadConversationMemorySnapshot(CONVERSATION_ID);

            var inputData = new ai.labs.eddi.engine.model.InputData("hello", new LinkedHashMap<>());

            // The env-based say will fail because no agent is deployed.
            // We just verify the snapshot was loaded by the single-arg overload.
            assertThrows(IConversationService.AgentNotReadyException.class,
                    () -> conversationService.say(CONVERSATION_ID, false, false, null, inputData, false, (s) -> {
                    }));

            verify(conversationMemoryStore, atLeastOnce()).loadConversationMemorySnapshot(CONVERSATION_ID);
        }
    }

    @Nested
    @DisplayName("sayStreaming(conversationId, ...) — single-arg overload")
    class SayStreamingSingleArg {

        @Test
        @DisplayName("loads snapshot and delegates to env-based sayStreaming")
        void delegatesToEnvBasedSayStreaming() throws Exception {
            var snapshot = createMinimalSnapshot(AGENT_ID, USER_ID);
            snapshot.setEnvironment(ENV);
            doReturn(snapshot).when(conversationMemoryStore).loadConversationMemorySnapshot(CONVERSATION_ID);

            var inputData = new ai.labs.eddi.engine.model.InputData("hello", new LinkedHashMap<>());

            assertThrows(IConversationService.AgentNotReadyException.class,
                    () -> conversationService.sayStreaming(CONVERSATION_ID, false, false, null, inputData, null));

            verify(conversationMemoryStore, atLeastOnce()).loadConversationMemorySnapshot(CONVERSATION_ID);
        }
    }

    // =========================================================================
    // Single-arg isUndoAvailable / isRedoAvailable
    // =========================================================================

    @Nested
    @DisplayName("isUndoAvailable(conversationId) — single-arg overload")
    class IsUndoAvailableSingleArg {

        @Test
        @DisplayName("loads snapshot and delegates — multiple steps → true")
        void multipleSteps_returnsTrue() throws Exception {
            var snapshot = createSnapshotWithMultipleSteps(AGENT_ID);
            snapshot.setEnvironment(ENV);
            doReturn(snapshot).when(conversationMemoryStore).loadConversationMemorySnapshot(CONVERSATION_ID);

            Boolean result = conversationService.isUndoAvailable(CONVERSATION_ID);

            assertTrue(result);
        }

        @Test
        @DisplayName("loads snapshot and delegates — single step → false")
        void singleStep_returnsFalse() throws Exception {
            var snapshot = createMinimalSnapshot(AGENT_ID, USER_ID);
            snapshot.setEnvironment(ENV);
            doReturn(snapshot).when(conversationMemoryStore).loadConversationMemorySnapshot(CONVERSATION_ID);

            Boolean result = conversationService.isUndoAvailable(CONVERSATION_ID);

            assertFalse(result);
        }
    }

    @Nested
    @DisplayName("isRedoAvailable(conversationId) — single-arg overload")
    class IsRedoAvailableSingleArg {

        @Test
        @DisplayName("loads snapshot and delegates — redo cache present → true")
        void redoCachePresent_returnsTrue() throws Exception {
            var snapshot = createSnapshotWithRedoCache(AGENT_ID);
            snapshot.setEnvironment(ENV);
            doReturn(snapshot).when(conversationMemoryStore).loadConversationMemorySnapshot(CONVERSATION_ID);

            Boolean result = conversationService.isRedoAvailable(CONVERSATION_ID);

            assertTrue(result);
        }

        @Test
        @DisplayName("loads snapshot and delegates — empty redo cache → false")
        void emptyRedoCache_returnsFalse() throws Exception {
            var snapshot = createMinimalSnapshot(AGENT_ID, USER_ID);
            snapshot.setEnvironment(ENV);
            doReturn(snapshot).when(conversationMemoryStore).loadConversationMemorySnapshot(CONVERSATION_ID);

            Boolean result = conversationService.isRedoAvailable(CONVERSATION_ID);

            assertFalse(result);
        }
    }

    // =========================================================================
    // Single-arg undo / redo
    // =========================================================================

    @Nested
    @DisplayName("undo(conversationId) — single-arg overload")
    class UndoSingleArg {

        @Test
        @DisplayName("happy path — undo available → returns true")
        void undoAvailable_returnsTrue() throws Exception {
            var snapshot = createSnapshotWithMultipleSteps(AGENT_ID);
            snapshot.setEnvironment(ENV);
            doReturn(snapshot).when(conversationMemoryStore).loadConversationMemorySnapshot(CONVERSATION_ID);
            doReturn(CONVERSATION_ID).when(conversationMemoryStore).storeConversationMemorySnapshot(any());

            boolean result = conversationService.undo(CONVERSATION_ID);

            assertTrue(result);
            verify(conversationMemoryStore).storeConversationMemorySnapshot(any());
        }

        @Test
        @DisplayName("undo not available → returns false")
        void undoNotAvailable_returnsFalse() throws Exception {
            var snapshot = createMinimalSnapshot(AGENT_ID, USER_ID);
            snapshot.setEnvironment(ENV);
            doReturn(snapshot).when(conversationMemoryStore).loadConversationMemorySnapshot(CONVERSATION_ID);

            boolean result = conversationService.undo(CONVERSATION_ID);

            assertFalse(result);
        }
    }

    @Nested
    @DisplayName("redo(conversationId) — single-arg overload")
    class RedoSingleArg {

        @Test
        @DisplayName("happy path — redo available → returns true")
        void redoAvailable_returnsTrue() throws Exception {
            var snapshot = createSnapshotWithRedoCache(AGENT_ID);
            snapshot.setEnvironment(ENV);
            doReturn(snapshot).when(conversationMemoryStore).loadConversationMemorySnapshot(CONVERSATION_ID);
            doReturn(CONVERSATION_ID).when(conversationMemoryStore).storeConversationMemorySnapshot(any());

            boolean result = conversationService.redo(CONVERSATION_ID);

            assertTrue(result);
            verify(conversationMemoryStore).storeConversationMemorySnapshot(any());
        }

        @Test
        @DisplayName("redo not available → returns false")
        void redoNotAvailable_returnsFalse() throws Exception {
            var snapshot = createMinimalSnapshot(AGENT_ID, USER_ID);
            snapshot.setEnvironment(ENV);
            doReturn(snapshot).when(conversationMemoryStore).loadConversationMemorySnapshot(CONVERSATION_ID);

            boolean result = conversationService.redo(CONVERSATION_ID);

            assertFalse(result);
        }
    }

    // =========================================================================
    // startConversation — ServiceException branch
    // =========================================================================

    @Nested
    @DisplayName("startConversation — ServiceException catch block")
    class StartConversationServiceException {

        @Test
        @DisplayName("agentFactory throws ServiceException → wrapped as ResourceStoreException")
        void serviceException_wrappedAsResourceStoreException() throws Exception {
            doReturn(USER_ID).when(conversationSetup).computeAnonymousUserIdIfEmpty(eq(USER_ID), isNull());
            doReturn(false).when(gdprComplianceService).isProcessingRestricted(USER_ID);
            doReturn(new HashMap<>()).when(contextLogger).createLoggingContext(any(), any(), any(), any());
            doThrow(new ai.labs.eddi.engine.runtime.service.ServiceException("agent factory boom"))
                    .when(agentFactory).getLatestReadyAgent(ENV, AGENT_ID);

            var ex = assertThrows(ResourceStoreException.class,
                    () -> conversationService.startConversation(ENV, AGENT_ID, USER_ID, null));
            assertTrue(ex.getMessage().contains("agent factory boom"));
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Creates a minimal {@link ConversationMemorySnapshot} with one conversation
     * step and one output.
     */
    private ConversationMemorySnapshot createMinimalSnapshot(String agentId, String userId) {
        var snapshot = new ConversationMemorySnapshot();
        snapshot.setAgentId(agentId);
        snapshot.setAgentVersion(1);
        snapshot.setUserId(userId);
        snapshot.setConversationId(CONVERSATION_ID);
        snapshot.setConversationState(ConversationState.READY);
        snapshot.setEnvironment(ENV);

        // One step with one workflow run
        var stepSnapshot = new ConversationStepSnapshot();
        var workflowRun = new WorkflowRunSnapshot();
        workflowRun.getLifecycleTasks().add(new ResultSnapshot("input:initial", "hello", null, new Date(), null, true));
        stepSnapshot.getWorkflows().add(workflowRun);
        snapshot.getConversationSteps().add(stepSnapshot);

        // One conversation output
        var output = new ConversationOutput();
        output.put("input", "hello");
        snapshot.getConversationOutputs().add(output);

        return snapshot;
    }

    /**
     * Creates a snapshot with multiple steps so that undo is available.
     */
    private ConversationMemorySnapshot createSnapshotWithMultipleSteps(String agentId) {
        var snapshot = createMinimalSnapshot(agentId, USER_ID);

        // Add a second step
        var step2 = new ConversationStepSnapshot();
        var workflowRun2 = new WorkflowRunSnapshot();
        workflowRun2.getLifecycleTasks().add(new ResultSnapshot("input:initial", "world", null, new Date(), null, true));
        step2.getWorkflows().add(workflowRun2);
        snapshot.getConversationSteps().add(step2);

        var output2 = new ConversationOutput();
        output2.put("input", "world");
        snapshot.getConversationOutputs().add(output2);

        return snapshot;
    }

    /**
     * Creates a snapshot with a non-empty redo cache so that redo is available.
     */
    private ConversationMemorySnapshot createSnapshotWithRedoCache(String agentId) {
        var snapshot = createMinimalSnapshot(agentId, USER_ID);

        var redoStep = new ConversationStepSnapshot();
        var workflowRun = new WorkflowRunSnapshot();
        workflowRun.getLifecycleTasks().add(new ResultSnapshot("input:initial", "redo-data", null, new Date(), null, true));
        redoStep.getWorkflows().add(workflowRun);
        snapshot.getRedoCache().push(redoStep);

        return snapshot;
    }
}
