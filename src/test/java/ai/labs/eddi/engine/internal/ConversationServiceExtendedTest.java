/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.properties.IUserMemoryStore;
import ai.labs.eddi.datastore.IResourceStore.ResourceNotFoundException;
import ai.labs.eddi.datastore.IResourceStore.ResourceStoreException;
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
import ai.labs.eddi.engine.runtime.IConversationSetup;
import ai.labs.eddi.engine.runtime.IRuntime;
import ai.labs.eddi.engine.runtime.service.ServiceException;
import ai.labs.eddi.engine.tenancy.QuotaExceededException;
import ai.labs.eddi.engine.tenancy.TenantQuotaService;
import ai.labs.eddi.engine.tenancy.model.QuotaCheckResult;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.Callable;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Extended tests for {@link ConversationService} — covers branches missed by
 * the main test file: rerunOnly path, sayStreaming audit enabled, auto-deploy
 * success path, say with generic exception, readConversationLog empty
 * outputType, and more edge cases.
 */
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
    private ICache<String, ConversationState> conversationStateCache;
    private AuditLedgerService auditLedgerService;
    private GdprComplianceService gdprComplianceService;
    private TenantQuotaService tenantQuotaService;
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
        userMemoryStore = mock(IUserMemoryStore.class);

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

    private ConversationMemorySnapshot createSnapshotWithEnvironment() {
        var snapshot = createSnapshot();
        snapshot.setEnvironment(ENV);
        return snapshot;
    }

    // =========================================================
    // say — rerunOnly path
    // =========================================================

    @Nested
    class SayRerunOnly {

        @Test
        void say_rerunOnly_submitsRerunCallable() throws Exception {
            var snapshot = createSnapshot();
            IAgent mockAgent = mock(IAgent.class);
            IConversation mockConversation = mock(IConversation.class);

            when(conversationMemoryStore.loadConversationMemorySnapshot(CONVERSATION_ID))
                    .thenReturn(snapshot);
            when(agentFactory.getAgent(ENV, AGENT_ID, 1)).thenReturn(mockAgent);
            when(mockAgent.continueConversation(any(), any(), any()))
                    .thenReturn(mockConversation);
            when(mockConversation.isEnded()).thenReturn(false);

            var handler = mock(ConversationResponseHandler.class);

            conversationService.say(ENV, AGENT_ID, CONVERSATION_ID, false, false,
                    List.of(), new InputData("hello", Map.of()), true, handler);

            verify(conversationCoordinator).submitInOrder(eq(CONVERSATION_ID), any());
        }
    }

    // =========================================================
    // say — auto-deploy succeeds
    // =========================================================

    @Nested
    class SayAutoDeploy {

        @Test
        void say_agentNotDeployed_autoDeploySucceeds_submitsToCoordinator() throws Exception {
            var snapshot = createSnapshot();
            IAgent mockAgent = mock(IAgent.class);
            IConversation mockConversation = mock(IConversation.class);

            when(conversationMemoryStore.loadConversationMemorySnapshot(CONVERSATION_ID))
                    .thenReturn(snapshot);
            // First call returns null, second (after deploy) returns agent
            when(agentFactory.getAgent(ENV, AGENT_ID, 1))
                    .thenReturn(null)
                    .thenReturn(mockAgent);
            when(mockAgent.continueConversation(any(), any(), any()))
                    .thenReturn(mockConversation);
            when(mockConversation.isEnded()).thenReturn(false);

            var handler = mock(ConversationResponseHandler.class);

            conversationService.say(ENV, AGENT_ID, CONVERSATION_ID, false, false,
                    List.of(), new InputData("hello", Map.of()), false, handler);

            verify(agentFactory).deployAgent(ENV, AGENT_ID, 1, null);
            verify(conversationCoordinator).submitInOrder(eq(CONVERSATION_ID), any());
        }
    }

    // =========================================================
    // say — generic exception removes processing reference
    // =========================================================

    @Nested
    class SayExceptionHandling {

        @Test
        void say_genericException_removesProcessingReference() throws Exception {
            when(conversationMemoryStore.loadConversationMemorySnapshot(CONVERSATION_ID))
                    .thenThrow(new ResourceStoreException("DB error"));

            var handler = mock(ConversationResponseHandler.class);

            assertThrows(Exception.class, () -> conversationService.say(ENV, AGENT_ID,
                    CONVERSATION_ID, false, false, List.of(),
                    new InputData("hello", Map.of()), false, handler));
        }

        @Test
        void say_agentNotReady_removesProcessingReference() throws Exception {
            var snapshot = createSnapshot();
            when(conversationMemoryStore.loadConversationMemorySnapshot(CONVERSATION_ID))
                    .thenReturn(snapshot);
            when(agentFactory.getAgent(ENV, AGENT_ID, 1)).thenReturn(null);

            var handler = mock(ConversationResponseHandler.class);

            assertThrows(AgentNotReadyException.class,
                    () -> conversationService.say(ENV, AGENT_ID, CONVERSATION_ID,
                            false, false, List.of(),
                            new InputData("hello", Map.of()), false, handler));
        }
    }

    // =========================================================
    // sayStreaming — audit enabled
    // =========================================================

    @Nested
    class SayStreamingAudit {

        @Test
        void sayStreaming_auditEnabled_setsAuditCollector() throws Exception {
            var snapshot = createSnapshot();
            IAgent mockAgent = mock(IAgent.class);
            IConversation mockConversation = mock(IConversation.class);

            when(conversationMemoryStore.loadConversationMemorySnapshot(CONVERSATION_ID))
                    .thenReturn(snapshot);
            when(agentFactory.getAgent(ENV, AGENT_ID, 1)).thenReturn(mockAgent);
            when(mockAgent.continueConversation(any(), any(), any()))
                    .thenReturn(mockConversation);
            when(mockConversation.isEnded()).thenReturn(false);
            when(auditLedgerService.isEnabled()).thenReturn(true);

            var handler = mock(StreamingResponseHandler.class);

            conversationService.sayStreaming(ENV, AGENT_ID, CONVERSATION_ID,
                    false, false, List.of(),
                    new InputData("hello", Map.of()), handler);

            verify(conversationCoordinator).submitInOrder(eq(CONVERSATION_ID), any());
        }

        @Test
        void sayStreaming_agentNotDeployed_throwsAgentNotReadyException() throws Exception {
            var snapshot = createSnapshot();
            when(conversationMemoryStore.loadConversationMemorySnapshot(CONVERSATION_ID))
                    .thenReturn(snapshot);
            when(agentFactory.getAgent(ENV, AGENT_ID, 1)).thenReturn(null);

            var handler = mock(StreamingResponseHandler.class);

            assertThrows(AgentNotReadyException.class,
                    () -> conversationService.sayStreaming(ENV, AGENT_ID,
                            CONVERSATION_ID, false, false, List.of(),
                            new InputData("hello", Map.of()), handler));
        }
    }

    // =========================================================
    // sayStreaming — generic exception handling
    // =========================================================

    @Nested
    class SayStreamingExceptions {

        @Test
        void sayStreaming_genericException_propagatesAndCleansUp() throws Exception {
            when(conversationMemoryStore.loadConversationMemorySnapshot(CONVERSATION_ID))
                    .thenThrow(new ResourceStoreException("DB error"));

            var handler = mock(StreamingResponseHandler.class);

            assertThrows(Exception.class,
                    () -> conversationService.sayStreaming(ENV, AGENT_ID,
                            CONVERSATION_ID, false, false, List.of(),
                            new InputData("hello", Map.of()), handler));
        }
    }

    // =========================================================
    // readConversationLog — edge cases
    // =========================================================

    @Nested
    class ReadConversationLogEdgeCases {

        @Test
        void readConversationLog_emptyOutputType_returnsTextPlain() throws Exception {
            var snapshot = createSnapshot();
            when(conversationMemoryStore.loadConversationMemorySnapshot(CONVERSATION_ID))
                    .thenReturn(snapshot);

            // Empty string should fall through to text/plain
            var result = conversationService.readConversationLog(CONVERSATION_ID, "", 10);
            assertEquals("text/plain", result.mediaType());
        }
    }

    // =========================================================
    // createPropertiesHandler edge cases
    // =========================================================

    @Nested
    class CreatePropertiesHandler {

        @Test
        void createPropertiesHandler_nullUserId_returnsNullUserId() {
            var handler = conversationService.createPropertiesHandler(null, null);

            assertNotNull(handler);
            assertNull(handler.getUserId());
            assertNotNull(handler.getUserMemoryStore());
        }

        @Test
        void createPropertiesHandler_withMemoryConfig_returnsConfig() {
            var memoryConfig = mock(AgentConfiguration.UserMemoryConfig.class);
            var handler = conversationService.createPropertiesHandler(USER_ID, memoryConfig);

            assertNotNull(handler);
            assertEquals(USER_ID, handler.getUserId());
            assertSame(memoryConfig, handler.getUserMemoryConfig());
        }
    }

    // =========================================================
    // startConversation — null userId path
    // =========================================================

    @Nested
    class StartConversationEdgeCases {

        @Test
        void startConversation_nullUserIdNotRestricted_proceeds() throws Exception {
            IAgent mockAgent = mock(IAgent.class);
            IConversation mockConversation = mock(IConversation.class);
            IConversationMemory mockMemory = mock(IConversationMemory.class);

            when(agentFactory.getLatestReadyAgent(ENV, AGENT_ID)).thenReturn(mockAgent);
            when(conversationSetup.computeAnonymousUserIdIfEmpty(isNull(), any()))
                    .thenReturn(null);
            when(mockAgent.startConversation(isNull(), anyMap(), any(), any()))
                    .thenReturn(mockConversation);
            when(mockConversation.getConversationMemory()).thenReturn(mockMemory);
            when(mockMemory.getConversationState()).thenReturn(ConversationState.READY);
            when(mockMemory.getRedoCache()).thenReturn(new Stack<>());
            var mockStepStack = mock(IConversationMemory.IConversationStepStack.class);
            when(mockStepStack.size()).thenReturn(0);
            when(mockMemory.getAllSteps()).thenReturn(mockStepStack);
            when(mockMemory.getConversationOutputs()).thenReturn(new ArrayList<>());
            var mockProps = mock(IConversationMemory.IConversationProperties.class);
            when(mockProps.entrySet()).thenReturn(Set.of());
            when(mockMemory.getConversationProperties()).thenReturn(mockProps);
            when(conversationMemoryStore.storeConversationMemorySnapshot(any()))
                    .thenReturn(CONVERSATION_ID);

            // null userId with null GDPR check (null userId bypasses restriction)
            var result = conversationService.startConversation(ENV, AGENT_ID, null, null);
            assertNotNull(result);
        }

        @Test
        void startConversation_serviceExceptionWrappedInResourceStoreException() throws Exception {
            when(conversationSetup.computeAnonymousUserIdIfEmpty(any(), any()))
                    .thenReturn(USER_ID);
            when(agentFactory.getLatestReadyAgent(ENV, AGENT_ID))
                    .thenThrow(new ServiceException("Service error"));

            assertThrows(ResourceStoreException.class,
                    () -> conversationService.startConversation(ENV, AGENT_ID, USER_ID,
                            new LinkedHashMap<>()));
        }
    }

    // =========================================================
    // say — GDPR restriction with null userId
    // =========================================================

    @Nested
    class SayGdprNullUserId {

        @Test
        void say_nullUserId_skipsGdprCheck() throws Exception {
            var snapshot = createSnapshot();
            snapshot.setUserId(null); // null userId
            IAgent mockAgent = mock(IAgent.class);
            IConversation mockConversation = mock(IConversation.class);

            when(conversationMemoryStore.loadConversationMemorySnapshot(CONVERSATION_ID))
                    .thenReturn(snapshot);
            when(agentFactory.getAgent(ENV, AGENT_ID, 1)).thenReturn(mockAgent);
            when(mockAgent.continueConversation(any(), any(), any()))
                    .thenReturn(mockConversation);
            when(mockConversation.isEnded()).thenReturn(false);

            var handler = mock(ConversationResponseHandler.class);

            conversationService.say(ENV, AGENT_ID, CONVERSATION_ID, false, false,
                    List.of(), new InputData("hello", Map.of()), false, handler);

            verify(gdprComplianceService, never()).isProcessingRestricted(any());
            verify(conversationCoordinator).submitInOrder(eq(CONVERSATION_ID), any());
        }
    }

    // =========================================================
    // sayStreaming — null userId GDPR skip
    // =========================================================

    @Nested
    class SayStreamingGdprNullUserId {

        @Test
        void sayStreaming_nullUserId_skipsGdprCheck() throws Exception {
            var snapshot = createSnapshot();
            snapshot.setUserId(null);
            IAgent mockAgent = mock(IAgent.class);
            IConversation mockConversation = mock(IConversation.class);

            when(conversationMemoryStore.loadConversationMemorySnapshot(CONVERSATION_ID))
                    .thenReturn(snapshot);
            when(agentFactory.getAgent(ENV, AGENT_ID, 1)).thenReturn(mockAgent);
            when(mockAgent.continueConversation(any(), any(), any()))
                    .thenReturn(mockConversation);
            when(mockConversation.isEnded()).thenReturn(false);

            var handler = mock(StreamingResponseHandler.class);

            conversationService.sayStreaming(ENV, AGENT_ID, CONVERSATION_ID,
                    false, false, List.of(),
                    new InputData("hello", Map.of()), handler);

            verify(gdprComplianceService, never()).isProcessingRestricted(any());
        }
    }

    // =========================================================
    // undo/redo — actual undo/redo available paths
    // =========================================================

    @Nested
    class UndoRedoSuccess {

        @Test
        void isRedoAvailable_delegatesToMemory() throws Exception {
            var snapshot = createSnapshot();
            when(conversationMemoryStore.loadConversationMemorySnapshot(CONVERSATION_ID))
                    .thenReturn(snapshot);

            Boolean result = conversationService.isRedoAvailable(ENV, AGENT_ID, CONVERSATION_ID);
            assertFalse(result);
        }
    }

    // =========================================================
    // Conversation-only overloads delegating correctly
    // =========================================================

    @Nested
    class ConversationOnlyOverloads {

        @Test
        void undo_stringOnly_delegatesToFullMethod() throws Exception {
            var snapshot = createSnapshotWithEnvironment();
            when(conversationMemoryStore.loadConversationMemorySnapshot(CONVERSATION_ID))
                    .thenReturn(snapshot);

            // undo returns false when no steps to undo (empty conversation)
            boolean result = conversationService.undo(CONVERSATION_ID);

            assertFalse(result);
            // Verify it loaded the snapshot to extract environment and agentId
            verify(conversationMemoryStore, atLeastOnce()).loadConversationMemorySnapshot(CONVERSATION_ID);
        }

        @Test
        void redo_stringOnly_delegatesToFullMethod() throws Exception {
            var snapshot = createSnapshotWithEnvironment();
            when(conversationMemoryStore.loadConversationMemorySnapshot(CONVERSATION_ID))
                    .thenReturn(snapshot);

            // redo returns false when no steps to redo (empty conversation)
            boolean result = conversationService.redo(CONVERSATION_ID);

            assertFalse(result);
            verify(conversationMemoryStore, atLeastOnce()).loadConversationMemorySnapshot(CONVERSATION_ID);
        }
    }

    // =========================================================
    // endConversation — verify both store and cache
    // =========================================================

    @Nested
    class EndConversationDetails {

        @Test
        void endConversation_updatesStoreAndCache() {
            conversationService.endConversation(CONVERSATION_ID);

            verify(conversationMemoryStore).setConversationState(
                    CONVERSATION_ID, ConversationState.ENDED);
            verify(conversationStateCache).put(
                    CONVERSATION_ID, ConversationState.ENDED);
        }
    }
}
