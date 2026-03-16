package ai.labs.eddi.engine.internal;

import ai.labs.eddi.configs.properties.IPropertiesStore;
import ai.labs.eddi.engine.IConversationService.*;
import ai.labs.eddi.engine.caching.ICache;
import ai.labs.eddi.engine.caching.ICacheFactory;
import ai.labs.eddi.engine.lifecycle.IConversation;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.memory.descriptor.IConversationDescriptorStore;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import ai.labs.eddi.engine.model.ConversationState;
import ai.labs.eddi.engine.model.Deployment.Environment;
import ai.labs.eddi.engine.model.InputData;
import ai.labs.eddi.engine.runtime.IBot;
import ai.labs.eddi.engine.runtime.IBotFactory;
import ai.labs.eddi.engine.runtime.IConversationCoordinator;
import ai.labs.eddi.engine.runtime.IRuntime;
import ai.labs.eddi.engine.utilities.IConversationSetup;
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
 * extracted from RestBotEngine.
 */
class ConversationServiceTest {

    private ConversationService conversationService;

    // Mocked dependencies
    private IBotFactory botFactory;
    private IConversationMemoryStore conversationMemoryStore;
    private IConversationDescriptorStore conversationDescriptorStore;
    private IPropertiesStore propertiesStore;
    private IConversationCoordinator conversationCoordinator;
    private IConversationSetup conversationSetup;
    private IRuntime runtime;
    private IContextLogger contextLogger;
    private ICacheFactory cacheFactory;
    private ICache<String, ConversationState> conversationStateCache;

    private static final Environment ENV = Environment.unrestricted;
    private static final String BOT_ID = "test-bot-id";
    private static final String CONVERSATION_ID = "test-conversation-id";
    private static final String USER_ID = "test-user-id";
    private static final int BOT_TIMEOUT = 60;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        botFactory = mock(IBotFactory.class);
        conversationMemoryStore = mock(IConversationMemoryStore.class);
        conversationDescriptorStore = mock(IConversationDescriptorStore.class);
        propertiesStore = mock(IPropertiesStore.class);
        conversationCoordinator = mock(IConversationCoordinator.class);
        conversationSetup = mock(IConversationSetup.class);
        runtime = mock(IRuntime.class);
        contextLogger = mock(IContextLogger.class);
        cacheFactory = mock(ICacheFactory.class);
        conversationStateCache = mock(ICache.class);
        MeterRegistry meterRegistry = new SimpleMeterRegistry();

        doReturn(conversationStateCache).when(cacheFactory).getCache("conversationState");
        when(contextLogger.createLoggingContext(any(), any(), any(), any()))
                .thenReturn(new HashMap<>());

        conversationService = new ConversationService(
                botFactory, conversationMemoryStore, conversationDescriptorStore,
                propertiesStore, conversationCoordinator, conversationSetup,
                cacheFactory, runtime, contextLogger, meterRegistry, BOT_TIMEOUT);
    }

    // --- startConversation tests ---

    @Test
    void startConversation_withReadyBot_returnsConversationResult() throws Exception {
        // Arrange
        IBot mockBot = mock(IBot.class);
        IConversation mockConversation = mock(IConversation.class);
        IConversationMemory mockMemory = mock(IConversationMemory.class);

        when(botFactory.getLatestReadyBot(ENV, BOT_ID)).thenReturn(mockBot);
        when(conversationSetup.computeAnonymousUserIdIfEmpty(eq(USER_ID), any())).thenReturn(USER_ID);
        when(mockBot.startConversation(eq(USER_ID), anyMap(), any(), any())).thenReturn(mockConversation);
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
        var result = conversationService.startConversation(ENV, BOT_ID, USER_ID, new LinkedHashMap<>());

        // Assert
        assertNotNull(result);
        assertEquals(CONVERSATION_ID, result.conversationId());
        assertNotNull(result.conversationUri());
        verify(conversationMemoryStore).storeConversationMemorySnapshot(any());
        verify(conversationStateCache).put(CONVERSATION_ID, ConversationState.READY);
        verify(conversationSetup).createConversationDescriptor(eq(BOT_ID), eq(mockBot), eq(USER_ID),
                eq(CONVERSATION_ID), any());
    }

    @Test
    void startConversation_noBotReady_throwsBotNotReadyException() throws Exception {
        when(botFactory.getLatestReadyBot(ENV, BOT_ID)).thenReturn(null);

        assertThrows(BotNotReadyException.class,
                () -> conversationService.startConversation(ENV, BOT_ID, USER_ID, null));
    }

    @Test
    void startConversation_withNullContext_createsEmptyContext() throws Exception {
        IBot mockBot = mock(IBot.class);
        IConversation mockConversation = mock(IConversation.class);
        IConversationMemory mockMemory = mock(IConversationMemory.class);

        when(botFactory.getLatestReadyBot(ENV, BOT_ID)).thenReturn(mockBot);
        when(conversationSetup.computeAnonymousUserIdIfEmpty(eq(USER_ID), any())).thenReturn(USER_ID);
        when(mockBot.startConversation(eq(USER_ID), anyMap(), any(), any())).thenReturn(mockConversation);
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
        var result = conversationService.startConversation(ENV, BOT_ID, USER_ID, null);
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

        assertThrows(ConversationNotFoundException.class,
                () -> conversationService.getConversationState(ENV, CONVERSATION_ID));
    }

    // --- readConversation tests ---

    @Test
    void readConversation_botMismatch_throwsBotMismatchException() throws Exception {
        var snapshot = new ConversationMemorySnapshot();
        snapshot.setBotId("different-bot-id");

        when(conversationMemoryStore.loadConversationMemorySnapshot(CONVERSATION_ID)).thenReturn(snapshot);

        assertThrows(BotMismatchException.class,
                () -> conversationService.readConversation(ENV, BOT_ID, CONVERSATION_ID,
                        false, false, List.of()));
    }

    @Test
    void readConversation_validRequest_returnsSnapshot() throws Exception {
        var snapshot = new ConversationMemorySnapshot();
        snapshot.setBotId(BOT_ID);
        snapshot.setUserId(USER_ID);
        snapshot.setConversationState(ConversationState.READY);
        snapshot.setConversationSteps(new ArrayList<>());

        when(conversationMemoryStore.loadConversationMemorySnapshot(CONVERSATION_ID)).thenReturn(snapshot);

        var result = conversationService.readConversation(ENV, BOT_ID, CONVERSATION_ID,
                false, false, List.of());

        assertNotNull(result);
        assertEquals(ConversationState.READY, result.getConversationState());
    }

    // --- undo/redo tests ---

    @Test
    void isUndoAvailable_delegatesToMemory() throws Exception {
        var snapshot = new ConversationMemorySnapshot();
        snapshot.setBotId(BOT_ID);
        snapshot.setConversationState(ConversationState.READY);
        snapshot.setConversationSteps(new ArrayList<>());

        when(conversationMemoryStore.loadConversationMemorySnapshot(CONVERSATION_ID)).thenReturn(snapshot);

        // New conversation has no steps to undo
        Boolean result = conversationService.isUndoAvailable(ENV, BOT_ID, CONVERSATION_ID);
        assertFalse(result);
    }

    @Test
    void undo_botMismatch_throwsBotMismatchException() throws Exception {
        var snapshot = new ConversationMemorySnapshot();
        snapshot.setBotId("different-bot-id");
        snapshot.setConversationState(ConversationState.READY);
        snapshot.setConversationSteps(new ArrayList<>());

        when(conversationMemoryStore.loadConversationMemorySnapshot(CONVERSATION_ID)).thenReturn(snapshot);

        assertThrows(BotMismatchException.class,
                () -> conversationService.undo(ENV, BOT_ID, CONVERSATION_ID));
    }

    @Test
    void redo_botMismatch_throwsBotMismatchException() throws Exception {
        var snapshot = new ConversationMemorySnapshot();
        snapshot.setBotId("different-bot-id");
        snapshot.setConversationState(ConversationState.READY);
        snapshot.setConversationSteps(new ArrayList<>());

        when(conversationMemoryStore.loadConversationMemorySnapshot(CONVERSATION_ID)).thenReturn(snapshot);

        assertThrows(BotMismatchException.class,
                () -> conversationService.redo(ENV, BOT_ID, CONVERSATION_ID));
    }

    // --- say tests ---

    @Test
    void say_botMismatch_throwsBotMismatchException() throws Exception {
        var snapshot = new ConversationMemorySnapshot();
        snapshot.setBotId("different-bot-id");
        snapshot.setUserId(USER_ID);
        snapshot.setBotVersion(1);
        snapshot.setConversationState(ConversationState.READY);
        snapshot.setConversationSteps(new ArrayList<>());

        when(conversationMemoryStore.loadConversationMemorySnapshot(CONVERSATION_ID)).thenReturn(snapshot);

        var handler = mock(ConversationResponseHandler.class);

        assertThrows(BotMismatchException.class,
                () -> conversationService.say(ENV, BOT_ID, CONVERSATION_ID,
                        false, false, List.of(),
                        new InputData("hello", Map.of()), false, handler));
    }

    @Test
    void say_conversationEnded_throwsConversationEndedException() throws Exception {
        var snapshot = new ConversationMemorySnapshot();
        snapshot.setBotId(BOT_ID);
        snapshot.setUserId(USER_ID);
        snapshot.setBotVersion(1);
        snapshot.setConversationState(ConversationState.READY);
        snapshot.setConversationSteps(new ArrayList<>());

        IBot mockBot = mock(IBot.class);
        IConversation mockConversation = mock(IConversation.class);

        when(conversationMemoryStore.loadConversationMemorySnapshot(CONVERSATION_ID)).thenReturn(snapshot);
        when(botFactory.getBot(ENV, BOT_ID, 1)).thenReturn(mockBot);
        when(mockBot.continueConversation(any(), any(), any())).thenReturn(mockConversation);
        when(mockConversation.isEnded()).thenReturn(true);

        var handler = mock(ConversationResponseHandler.class);

        assertThrows(ConversationEndedException.class,
                () -> conversationService.say(ENV, BOT_ID, CONVERSATION_ID,
                        false, false, List.of(),
                        new InputData("hello", Map.of()), false, handler));
    }

    @Test
    void say_validInput_submitsToCoordinator() throws Exception {
        var snapshot = new ConversationMemorySnapshot();
        snapshot.setBotId(BOT_ID);
        snapshot.setUserId(USER_ID);
        snapshot.setBotVersion(1);
        snapshot.setConversationState(ConversationState.READY);
        snapshot.setConversationSteps(new ArrayList<>());

        IBot mockBot = mock(IBot.class);
        IConversation mockConversation = mock(IConversation.class);

        when(conversationMemoryStore.loadConversationMemorySnapshot(CONVERSATION_ID)).thenReturn(snapshot);
        when(botFactory.getBot(ENV, BOT_ID, 1)).thenReturn(mockBot);
        when(mockBot.continueConversation(any(), any(), any())).thenReturn(mockConversation);
        when(mockConversation.isEnded()).thenReturn(false);

        var handler = mock(ConversationResponseHandler.class);

        conversationService.say(ENV, BOT_ID, CONVERSATION_ID,
                false, false, List.of(),
                new InputData("hello", Map.of()), false, handler);

        // Verify conversation was submitted to the coordinator
        verify(conversationCoordinator).submitInOrder(eq(CONVERSATION_ID), any(Callable.class));
    }

    // --- createPropertiesHandler tests ---

    @Test
    void createPropertiesHandler_loadsAndMergesProperties() throws Exception {
        var handler = conversationService.createPropertiesHandler(USER_ID);

        when(propertiesStore.readProperties(USER_ID)).thenReturn(null);

        var props = handler.loadProperties();
        assertNotNull(props);

        verify(propertiesStore).readProperties(USER_ID);
    }

    // --- sayStreaming tests ---

    @Test
    void sayStreaming_botMismatch_throwsBotMismatchException() throws Exception {
        var snapshot = new ConversationMemorySnapshot();
        snapshot.setBotId("different-bot-id");
        snapshot.setUserId(USER_ID);
        snapshot.setBotVersion(1);
        snapshot.setConversationState(ConversationState.READY);
        snapshot.setConversationSteps(new ArrayList<>());

        when(conversationMemoryStore.loadConversationMemorySnapshot(CONVERSATION_ID)).thenReturn(snapshot);

        var handler = mock(ai.labs.eddi.engine.IConversationService.StreamingResponseHandler.class);

        assertThrows(BotMismatchException.class,
                () -> conversationService.sayStreaming(ENV, BOT_ID, CONVERSATION_ID,
                        false, false, List.of(),
                        new InputData("hello", Map.of()), handler));
    }

    @SuppressWarnings("unchecked")
    @Test
    void sayStreaming_conversationEnded_throwsConversationEndedException() throws Exception {
        var snapshot = new ConversationMemorySnapshot();
        snapshot.setBotId(BOT_ID);
        snapshot.setUserId(USER_ID);
        snapshot.setBotVersion(1);
        snapshot.setConversationState(ConversationState.READY);
        snapshot.setConversationSteps(new ArrayList<>());

        IBot mockBot = mock(IBot.class);
        IConversation mockConversation = mock(IConversation.class);

        when(conversationMemoryStore.loadConversationMemorySnapshot(CONVERSATION_ID)).thenReturn(snapshot);
        when(botFactory.getBot(ENV, BOT_ID, 1)).thenReturn(mockBot);
        when(mockBot.continueConversation(any(), any(), any())).thenReturn(mockConversation);
        when(mockConversation.isEnded()).thenReturn(true);

        var handler = mock(ai.labs.eddi.engine.IConversationService.StreamingResponseHandler.class);

        assertThrows(ConversationEndedException.class,
                () -> conversationService.sayStreaming(ENV, BOT_ID, CONVERSATION_ID,
                        false, false, List.of(),
                        new InputData("hello", Map.of()), handler));
    }

    @SuppressWarnings("unchecked")
    @Test
    void sayStreaming_validInput_submitsToCoordinator() throws Exception {
        var snapshot = new ConversationMemorySnapshot();
        snapshot.setBotId(BOT_ID);
        snapshot.setUserId(USER_ID);
        snapshot.setBotVersion(1);
        snapshot.setConversationState(ConversationState.READY);
        snapshot.setConversationSteps(new ArrayList<>());

        IBot mockBot = mock(IBot.class);
        IConversation mockConversation = mock(IConversation.class);

        when(conversationMemoryStore.loadConversationMemorySnapshot(CONVERSATION_ID)).thenReturn(snapshot);
        when(botFactory.getBot(ENV, BOT_ID, 1)).thenReturn(mockBot);
        when(mockBot.continueConversation(any(), any(), any())).thenReturn(mockConversation);
        when(mockConversation.isEnded()).thenReturn(false);

        var handler = mock(ai.labs.eddi.engine.IConversationService.StreamingResponseHandler.class);

        conversationService.sayStreaming(ENV, BOT_ID, CONVERSATION_ID,
                false, false, List.of(),
                new InputData("hello", Map.of()), handler);

        verify(conversationCoordinator).submitInOrder(eq(CONVERSATION_ID), any(Callable.class));
    }
}
