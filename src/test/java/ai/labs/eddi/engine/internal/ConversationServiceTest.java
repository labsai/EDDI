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
import ai.labs.eddi.engine.tenancy.TenantQuotaService;
import ai.labs.eddi.engine.tenancy.model.QuotaCheckResult;
import ai.labs.eddi.engine.runtime.IConversationSetup;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.Callable;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for ConversationService — the core conversation lifecycle logic
 * extracted from RestAgentEngine.
 */
class ConversationServiceTest {

    private ConversationService conversationService;

    // Mocked dependencies
    private IAgentFactory AgentFactory;
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
        AgentFactory = mock(IAgentFactory.class);
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
        MeterRegistry meterRegistry = new SimpleMeterRegistry();

        doReturn(conversationStateCache).when(cacheFactory).getCache("conversationState");
        when(contextLogger.createLoggingContext(any(), any(), any(), any())).thenReturn(new HashMap<>());

        conversationService = new ConversationService(AgentFactory, conversationMemoryStore, conversationDescriptorStore, userMemoryStore,
                conversationCoordinator, conversationSetup, cacheFactory, runtime, contextLogger, auditLedgerService, gdprComplianceService,
                tenantQuotaService, meterRegistry, AGENT_TIMEOUT);
    }

    // --- startConversation tests ---

    @Test
    void startConversation_withReadyAgent_returnsConversationResult() throws Exception {
        // Arrange
        IAgent mockAgent = mock(IAgent.class);
        IConversation mockConversation = mock(IConversation.class);
        IConversationMemory mockMemory = mock(IConversationMemory.class);

        when(AgentFactory.getLatestReadyAgent(ENV, AGENT_ID)).thenReturn(mockAgent);
        when(conversationSetup.computeAnonymousUserIdIfEmpty(eq(USER_ID), any())).thenReturn(USER_ID);
        when(mockAgent.startConversation(eq(USER_ID), anyMap(), any(), any())).thenReturn(mockConversation);
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
        when(conversationMemoryStore.storeConversationMemorySnapshot(any())).thenReturn(CONVERSATION_ID);

        // Act
        var result = conversationService.startConversation(ENV, AGENT_ID, USER_ID, new LinkedHashMap<>());

        // Assert
        assertNotNull(result);
        assertEquals(CONVERSATION_ID, result.conversationId());
        assertNotNull(result.conversationUri());
        verify(conversationMemoryStore).storeConversationMemorySnapshot(any());
        verify(conversationStateCache).put(CONVERSATION_ID, ConversationState.READY);
        verify(conversationSetup).createConversationDescriptor(eq(AGENT_ID), eq(mockAgent), eq(USER_ID), eq(CONVERSATION_ID), any());
    }

    @Test
    void startConversation_noAgentReady_throwsAgentNotReadyException() throws Exception {
        when(AgentFactory.getLatestReadyAgent(ENV, AGENT_ID)).thenReturn(null);

        assertThrows(AgentNotReadyException.class, () -> conversationService.startConversation(ENV, AGENT_ID, USER_ID, null));
    }

    @Test
    void startConversation_restrictedUser_throwsProcessingRestrictedException() throws Exception {
        // Given — user processing is restricted
        when(conversationSetup.computeAnonymousUserIdIfEmpty(eq(USER_ID), any())).thenReturn(USER_ID);
        when(gdprComplianceService.isProcessingRestricted(USER_ID)).thenReturn(true);

        // When/Then — should block conversation creation
        assertThrows(ProcessingRestrictedException.class,
                () -> conversationService.startConversation(ENV, AGENT_ID, USER_ID, new LinkedHashMap<>()));

        // Agent factory should never be called
        verifyNoInteractions(AgentFactory);
    }

    @Test
    void startConversation_withNullContext_createsEmptyContext() throws Exception {
        IAgent mockAgent = mock(IAgent.class);
        IConversation mockConversation = mock(IConversation.class);
        IConversationMemory mockMemory = mock(IConversationMemory.class);

        when(AgentFactory.getLatestReadyAgent(ENV, AGENT_ID)).thenReturn(mockAgent);
        when(conversationSetup.computeAnonymousUserIdIfEmpty(eq(USER_ID), any())).thenReturn(USER_ID);
        when(mockAgent.startConversation(eq(USER_ID), anyMap(), any(), any())).thenReturn(mockConversation);
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
        when(conversationMemoryStore.storeConversationMemorySnapshot(any())).thenReturn(CONVERSATION_ID);

        // Should not throw even with null context
        var result = conversationService.startConversation(ENV, AGENT_ID, USER_ID, null);
        assertNotNull(result);
    }

    // --- endConversation tests ---

    @Test
    void endConversation_setsStateToEnded() {
        conversationService.endConversation(CONVERSATION_ID);

        verify(conversationMemoryStore).setConversationState(CONVERSATION_ID, ConversationState.ENDED);
        verify(conversationStateCache).put(CONVERSATION_ID, ConversationState.ENDED);
    }

    // --- getConversationState tests ---

    @Test
    void getConversationState_cached_returnsCachedState() {
        when(conversationStateCache.get(CONVERSATION_ID)).thenReturn(ConversationState.READY);

        ConversationState state = conversationService.getConversationState(ENV, CONVERSATION_ID);

        assertEquals(ConversationState.READY, state);
        verify(conversationMemoryStore, never()).getConversationState(any());
    }

    @Test
    void getConversationState_notCached_loadsFromStoreAndCaches() {
        when(conversationStateCache.get(CONVERSATION_ID)).thenReturn(null);
        when(conversationMemoryStore.getConversationState(CONVERSATION_ID)).thenReturn(ConversationState.READY);

        ConversationState state = conversationService.getConversationState(ENV, CONVERSATION_ID);

        assertEquals(ConversationState.READY, state);
        verify(conversationMemoryStore).getConversationState(CONVERSATION_ID);
        verify(conversationStateCache).put(CONVERSATION_ID, ConversationState.READY);
    }

    @Test
    void getConversationState_notFound_throwsConversationNotFoundException() {
        when(conversationStateCache.get(CONVERSATION_ID)).thenReturn(null);
        when(conversationMemoryStore.getConversationState(CONVERSATION_ID)).thenReturn(null);

        assertThrows(ConversationNotFoundException.class, () -> conversationService.getConversationState(ENV, CONVERSATION_ID));
    }

    // --- readConversation tests ---

    @Test
    void readConversation_agentMismatch_throwsAgentMismatchException() throws Exception {
        var snapshot = new ConversationMemorySnapshot();
        snapshot.setAgentId("different-agent-id");

        when(conversationMemoryStore.loadConversationMemorySnapshot(CONVERSATION_ID)).thenReturn(snapshot);

        assertThrows(AgentMismatchException.class,
                () -> conversationService.readConversation(ENV, AGENT_ID, CONVERSATION_ID, false, false, List.of()));
    }

    @Test
    void readConversation_validRequest_returnsSnapshot() throws Exception {
        var snapshot = new ConversationMemorySnapshot();
        snapshot.setAgentId(AGENT_ID);
        snapshot.setUserId(USER_ID);
        snapshot.setConversationState(ConversationState.READY);
        snapshot.setConversationSteps(new ArrayList<>());

        when(conversationMemoryStore.loadConversationMemorySnapshot(CONVERSATION_ID)).thenReturn(snapshot);

        var result = conversationService.readConversation(ENV, AGENT_ID, CONVERSATION_ID, false, false, List.of());

        assertNotNull(result);
        assertEquals(ConversationState.READY, result.getConversationState());
    }

    // --- undo/redo tests ---

    @Test
    void isUndoAvailable_delegatesToMemory() throws Exception {
        var snapshot = new ConversationMemorySnapshot();
        snapshot.setAgentId(AGENT_ID);
        snapshot.setConversationState(ConversationState.READY);
        snapshot.setConversationSteps(new ArrayList<>());

        when(conversationMemoryStore.loadConversationMemorySnapshot(CONVERSATION_ID)).thenReturn(snapshot);

        // New conversation has no steps to undo
        Boolean result = conversationService.isUndoAvailable(ENV, AGENT_ID, CONVERSATION_ID);
        assertFalse(result);
    }

    @Test
    void undo_agentMismatch_throwsAgentMismatchException() throws Exception {
        var snapshot = new ConversationMemorySnapshot();
        snapshot.setAgentId("different-agent-id");
        snapshot.setConversationState(ConversationState.READY);
        snapshot.setConversationSteps(new ArrayList<>());

        when(conversationMemoryStore.loadConversationMemorySnapshot(CONVERSATION_ID)).thenReturn(snapshot);

        assertThrows(AgentMismatchException.class, () -> conversationService.undo(ENV, AGENT_ID, CONVERSATION_ID));
    }

    @Test
    void redo_agentMismatch_throwsAgentMismatchException() throws Exception {
        var snapshot = new ConversationMemorySnapshot();
        snapshot.setAgentId("different-agent-id");
        snapshot.setConversationState(ConversationState.READY);
        snapshot.setConversationSteps(new ArrayList<>());

        when(conversationMemoryStore.loadConversationMemorySnapshot(CONVERSATION_ID)).thenReturn(snapshot);

        assertThrows(AgentMismatchException.class, () -> conversationService.redo(ENV, AGENT_ID, CONVERSATION_ID));
    }

    // --- say tests ---

    @Test
    void say_agentMismatch_throwsAgentMismatchException() throws Exception {
        var snapshot = new ConversationMemorySnapshot();
        snapshot.setAgentId("different-agent-id");
        snapshot.setUserId(USER_ID);
        snapshot.setAgentVersion(1);
        snapshot.setConversationState(ConversationState.READY);
        snapshot.setConversationSteps(new ArrayList<>());

        when(conversationMemoryStore.loadConversationMemorySnapshot(CONVERSATION_ID)).thenReturn(snapshot);

        var handler = mock(ConversationResponseHandler.class);

        assertThrows(AgentMismatchException.class, () -> conversationService.say(ENV, AGENT_ID, CONVERSATION_ID, false, false, List.of(),
                new InputData("hello", Map.of()), false, handler));
    }

    @Test
    void say_conversationEnded_throwsConversationEndedException() throws Exception {
        var snapshot = new ConversationMemorySnapshot();
        snapshot.setAgentId(AGENT_ID);
        snapshot.setUserId(USER_ID);
        snapshot.setAgentVersion(1);
        snapshot.setConversationState(ConversationState.READY);
        snapshot.setConversationSteps(new ArrayList<>());

        IAgent mockAgent = mock(IAgent.class);
        IConversation mockConversation = mock(IConversation.class);

        when(conversationMemoryStore.loadConversationMemorySnapshot(CONVERSATION_ID)).thenReturn(snapshot);
        when(AgentFactory.getAgent(ENV, AGENT_ID, 1)).thenReturn(mockAgent);
        when(mockAgent.continueConversation(any(), any(), any())).thenReturn(mockConversation);
        when(mockConversation.isEnded()).thenReturn(true);

        var handler = mock(ConversationResponseHandler.class);

        assertThrows(ConversationEndedException.class, () -> conversationService.say(ENV, AGENT_ID, CONVERSATION_ID, false, false, List.of(),
                new InputData("hello", Map.of()), false, handler));
    }

    @Test
    void say_validInput_submitsToCoordinator() throws Exception {
        var snapshot = new ConversationMemorySnapshot();
        snapshot.setAgentId(AGENT_ID);
        snapshot.setUserId(USER_ID);
        snapshot.setAgentVersion(1);
        snapshot.setConversationState(ConversationState.READY);
        snapshot.setConversationSteps(new ArrayList<>());

        IAgent mockAgent = mock(IAgent.class);
        IConversation mockConversation = mock(IConversation.class);

        when(conversationMemoryStore.loadConversationMemorySnapshot(CONVERSATION_ID)).thenReturn(snapshot);
        when(AgentFactory.getAgent(ENV, AGENT_ID, 1)).thenReturn(mockAgent);
        when(mockAgent.continueConversation(any(), any(), any())).thenReturn(mockConversation);
        when(mockConversation.isEnded()).thenReturn(false);

        var handler = mock(ConversationResponseHandler.class);

        conversationService.say(ENV, AGENT_ID, CONVERSATION_ID, false, false, List.of(), new InputData("hello", Map.of()), false, handler);

        // Verify conversation was submitted to the coordinator
        verify(conversationCoordinator).submitInOrder(eq(CONVERSATION_ID), any());
    }

    // --- createPropertiesHandler tests ---

    @Test
    void createPropertiesHandler_providesStoreAndUserId() {
        var handler = conversationService.createPropertiesHandler(USER_ID, null);

        assertNotNull(handler);
        assertEquals(USER_ID, handler.getUserId());
        assertNotNull(handler.getUserMemoryStore());
        assertNull(handler.getUserMemoryConfig());
    }

    // --- sayStreaming tests ---

    @Test
    void sayStreaming_agentMismatch_throwsAgentMismatchException() throws Exception {
        var snapshot = new ConversationMemorySnapshot();
        snapshot.setAgentId("different-agent-id");
        snapshot.setUserId(USER_ID);
        snapshot.setAgentVersion(1);
        snapshot.setConversationState(ConversationState.READY);
        snapshot.setConversationSteps(new ArrayList<>());

        when(conversationMemoryStore.loadConversationMemorySnapshot(CONVERSATION_ID)).thenReturn(snapshot);

        var handler = mock(ai.labs.eddi.engine.api.IConversationService.StreamingResponseHandler.class);

        assertThrows(AgentMismatchException.class, () -> conversationService.sayStreaming(ENV, AGENT_ID, CONVERSATION_ID, false, false, List.of(),
                new InputData("hello", Map.of()), handler));
    }

    @Test
    void sayStreaming_conversationEnded_throwsConversationEndedException() throws Exception {
        var snapshot = new ConversationMemorySnapshot();
        snapshot.setAgentId(AGENT_ID);
        snapshot.setUserId(USER_ID);
        snapshot.setAgentVersion(1);
        snapshot.setConversationState(ConversationState.READY);
        snapshot.setConversationSteps(new ArrayList<>());

        IAgent mockAgent = mock(IAgent.class);
        IConversation mockConversation = mock(IConversation.class);

        when(conversationMemoryStore.loadConversationMemorySnapshot(CONVERSATION_ID)).thenReturn(snapshot);
        when(AgentFactory.getAgent(ENV, AGENT_ID, 1)).thenReturn(mockAgent);
        when(mockAgent.continueConversation(any(), any(), any())).thenReturn(mockConversation);
        when(mockConversation.isEnded()).thenReturn(true);

        var handler = mock(ai.labs.eddi.engine.api.IConversationService.StreamingResponseHandler.class);

        assertThrows(ConversationEndedException.class, () -> conversationService.sayStreaming(ENV, AGENT_ID, CONVERSATION_ID, false, false, List.of(),
                new InputData("hello", Map.of()), handler));
    }

    @SuppressWarnings("unchecked")
    @Test
    void sayStreaming_validInput_submitsToCoordinator() throws Exception {
        var snapshot = new ConversationMemorySnapshot();
        snapshot.setAgentId(AGENT_ID);
        snapshot.setUserId(USER_ID);
        snapshot.setAgentVersion(1);
        snapshot.setConversationState(ConversationState.READY);
        snapshot.setConversationSteps(new ArrayList<>());

        IAgent mockAgent = mock(IAgent.class);
        IConversation mockConversation = mock(IConversation.class);

        when(conversationMemoryStore.loadConversationMemorySnapshot(CONVERSATION_ID)).thenReturn(snapshot);
        when(AgentFactory.getAgent(ENV, AGENT_ID, 1)).thenReturn(mockAgent);
        when(mockAgent.continueConversation(any(), any(), any())).thenReturn(mockConversation);
        when(mockConversation.isEnded()).thenReturn(false);

        var handler = mock(ai.labs.eddi.engine.api.IConversationService.StreamingResponseHandler.class);

        conversationService.sayStreaming(ENV, AGENT_ID, CONVERSATION_ID, false, false, List.of(), new InputData("hello", Map.of()), handler);

        verify(conversationCoordinator).submitInOrder(eq(CONVERSATION_ID), any(Callable.class));
    }
}
