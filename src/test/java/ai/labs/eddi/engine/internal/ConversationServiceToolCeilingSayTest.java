/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.properties.IUserMemoryStore;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.api.IConversationService.ConversationResponseHandler;
import ai.labs.eddi.engine.audit.AuditLedgerService;
import ai.labs.eddi.engine.caching.ICache;
import ai.labs.eddi.engine.caching.ICacheFactory;
import ai.labs.eddi.engine.gdpr.GdprComplianceService;
import ai.labs.eddi.engine.lifecycle.IConversation;
import ai.labs.eddi.engine.lifecycle.IConversation.IConversationOutputRenderer;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.memory.descriptor.IConversationDescriptorStore;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot.ConversationStepSnapshot;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot.ResultSnapshot;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot.WorkflowRunSnapshot;
import ai.labs.eddi.engine.memory.model.ConversationOutput;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.model.Context;
import ai.labs.eddi.engine.model.Deployment.Environment;
import ai.labs.eddi.engine.model.InputData;
import ai.labs.eddi.engine.runtime.IAgent;
import ai.labs.eddi.engine.runtime.IAgentFactory;
import ai.labs.eddi.engine.runtime.IConversationCoordinator;
import ai.labs.eddi.engine.runtime.IConversationSetup;
import ai.labs.eddi.engine.runtime.IRuntime;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.engine.security.CallerIdentityContext;
import ai.labs.eddi.engine.security.ResolutionPrincipalContext;
import ai.labs.eddi.engine.security.TurnToolCeiling;
import ai.labs.eddi.engine.tenancy.TenantQuotaService;
import ai.labs.eddi.engine.tenancy.model.QuotaCheckResult;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * The tool ceiling of an email sender role (email plan v3 §9) through
 * {@code say}: a later turn runs under the stored ceiling, from the memory
 * alone (as a HITL resume or self-continuation does), and a mail turn's ceiling
 * narrows the stored one — never widens it — before the turn's principal is
 * built.
 */
@DisplayName("ConversationService — the sender-role tool ceiling on say")
class ConversationServiceToolCeilingSayTest {

    private static final Environment ENV = Environment.production;
    private static final String AGENT_ID = "0123456789abcdef01234568";
    private static final int AGENT_VERSION = 1;
    private static final String CONVERSATION_ID = "0123456789abcdef89abcd01";
    private static final String USER_ID = "email:aabbccddee1122334455aabb:ann@customer-a.com";

    @Mock
    private IAgentFactory agentFactory;
    @Mock
    private IConversationMemoryStore conversationMemoryStore;
    @Mock
    private IConversationCoordinator conversationCoordinator;
    @Mock
    private ICacheFactory cacheFactory;
    @Mock
    private IRuntime runtime;
    @Mock
    private IContextLogger contextLogger;
    @Mock
    private TenantQuotaService tenantQuotaService;
    @Mock
    private ICache<String, ConversationState> conversationStateCache;
    @Mock
    private IAgent agent;
    @Mock
    private IConversation conversation;

    private final ResolutionPrincipalContext principals = new ResolutionPrincipalContext();
    private ConversationService conversationService;
    private final List<IConversationMemory> builtOver = new ArrayList<>();
    private final List<IConversationOutputRenderer> renderers = new ArrayList<>();
    /** The ceiling bound inside the pipeline, per turn. */
    private final List<Set<String>> boundInPipeline = new ArrayList<>();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        MockitoAnnotations.openMocks(this);
        doReturn(conversationStateCache).when(cacheFactory).getCache("conversationState", ConversationService.CONVERSATION_STATE_CACHE_TTL);
        doReturn(new HashMap<String, String>()).when(contextLogger).createLoggingContext(any(), any(), any(), any());
        doReturn(QuotaCheckResult.OK).when(tenantQuotaService).acquireApiCallSlot();
        doReturn(agent).when(agentFactory).getAgent(ENV, AGENT_ID, AGENT_VERSION);
        doReturn(ConversationState.READY).when(conversationMemoryStore).getConversationState(CONVERSATION_ID);
        doReturn(1L).when(conversationMemoryStore).getRevision(CONVERSATION_ID);
        doReturn(false).when(conversation).isEnded();
        doAnswer(inv -> {
            builtOver.add(inv.getArgument(0));
            renderers.add(inv.getArgument(2));
            return conversation;
        }).when(agent).continueConversation(any(IConversationMemory.class), any(), any());
        // The "pipeline": records the ceiling the turn's tool calls would see.
        doAnswer(inv -> {
            IConversationMemory memory = builtOver.getLast();
            boundInPipeline.add(TurnToolCeiling.current() == null ? null : new TreeSet<>(TurnToolCeiling.current()));
            memory.setConversationState(ConversationState.READY);
            renderers.getLast().renderOutput(memory);
            return null;
        }).when(conversation).say(anyString(), any());
        doAnswer(invocation -> {
            Callable<Object> callable = invocation.getArgument(0);
            IRuntime.IFinishedExecution<Object> listener = invocation.getArgument(1);
            Object result = callable.call();
            listener.onComplete(result);
            return CompletableFuture.completedFuture(result);
        }).when(runtime).submitCallable(any(Callable.class), any(IRuntime.IFinishedExecution.class), any());

        conversationService = new ConversationService(agentFactory, conversationMemoryStore, mock(IConversationDescriptorStore.class),
                mock(IUserMemoryStore.class), conversationCoordinator, mock(IConversationSetup.class), cacheFactory, runtime, contextLogger,
                mock(AuditLedgerService.class), mock(GdprComplianceService.class), tenantQuotaService, mock(IScheduleStore.class),
                mock(IAgentStore.class), mock(IJsonSerialization.class), new SimpleMeterRegistry(),
                ConversationServiceTestFixtures.hitlResumeEvent(), new CallerIdentityContext(null, null), 30);
        conversationService.resolutionPrincipalContext = principals;
        doReturn("default").when(tenantQuotaService).getDefaultTenantId();
    }

    @AfterEach
    void tearDown() {
        principals.clear();
    }

    @Test
    @DisplayName("a later turn runs under the stored ceiling, from the memory alone")
    void storedCeilingIsBound() throws Exception {
        doReturn(snapshot(Set.of("kb_search"))).when(conversationMemoryStore).loadConversationMemorySnapshot(CONVERSATION_ID);

        say(new HashMap<>());
        runQueuedTurn();

        assertEquals(Set.of("kb_search"), boundInPipeline.getFirst());
        assertEquals(Set.of("kb_search"), persistedSnapshot().getToolCeiling());
    }

    @Test
    @DisplayName("a mail turn's ceiling narrows the stored one before the turn runs, and the narrower one is stored")
    void contextNarrows() throws Exception {
        doReturn(snapshot(Set.of("kb_search", "lookup_order"))).when(conversationMemoryStore).loadConversationMemorySnapshot(CONVERSATION_ID);

        say(ceiling("kb_search", "http_post"));
        runQueuedTurn();

        assertEquals(Set.of("kb_search"), boundInPipeline.getFirst(), "intersected, so http_post is not gained");
        assertEquals(Set.of("kb_search"), persistedSnapshot().getToolCeiling());
    }

    @Test
    @DisplayName("a mail turn's ceiling applies to a conversation that had none")
    void contextApplies() throws Exception {
        doReturn(snapshot(null)).when(conversationMemoryStore).loadConversationMemorySnapshot(CONVERSATION_ID);

        say(ceiling("kb_search"));
        runQueuedTurn();

        assertEquals(Set.of("kb_search"), boundInPipeline.getFirst());
    }

    @Test
    @DisplayName("a turn without a ceiling never clears the stored one")
    void neverCleared() throws Exception {
        doReturn(snapshot(Set.of())).when(conversationMemoryStore).loadConversationMemorySnapshot(CONVERSATION_ID);

        say(new HashMap<>());
        runQueuedTurn();

        assertEquals(Set.of(), boundInPipeline.getFirst(), "an empty ceiling (no tools) stays empty");
    }

    @Test
    @DisplayName("without any ceiling nothing is bound")
    void none() throws Exception {
        doReturn(snapshot(null)).when(conversationMemoryStore).loadConversationMemorySnapshot(CONVERSATION_ID);

        say(new HashMap<>());
        runQueuedTurn();

        assertNull(boundInPipeline.getFirst());
        assertNull(persistedSnapshot().getToolCeiling());
    }

    private static Map<String, Context> ceiling(String... tools) {
        return new HashMap<>(Map.of(TurnToolCeiling.CONTEXT_KEY, new Context(Context.ContextType.array, List.of(tools))));
    }

    private void say(Map<String, Context> context) throws Exception {
        conversationService.say(CONVERSATION_ID, false, true, List.of(), new InputData("hello", context), false,
                mock(ConversationResponseHandler.class));
    }

    private void runQueuedTurn() throws Exception {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Callable<Void>> captor = ArgumentCaptor.forClass(Callable.class);
        verify(conversationCoordinator).submitInOrder(eq(CONVERSATION_ID), captor.capture());
        captor.getValue().call();
        assertTrue(boundInPipeline.size() == 1, "the pipeline must have run once");
    }

    private ConversationMemorySnapshot persistedSnapshot() throws Exception {
        ArgumentCaptor<ConversationMemorySnapshot> stored = ArgumentCaptor.forClass(ConversationMemorySnapshot.class);
        verify(conversationMemoryStore, atLeastOnce()).storeConversationMemorySnapshot(stored.capture());
        return stored.getValue();
    }

    private static ConversationMemorySnapshot snapshot(Set<String> ceiling) {
        var snapshot = new ConversationMemorySnapshot();
        snapshot.setConversationId(CONVERSATION_ID);
        snapshot.setAgentId(AGENT_ID);
        snapshot.setAgentVersion(AGENT_VERSION);
        snapshot.setUserId(USER_ID);
        snapshot.setOrigin("email");
        snapshot.setToolCeiling(ceiling);
        snapshot.setConversationState(ConversationState.READY);
        snapshot.setEnvironment(ENV);
        snapshot.setRevision(1L);
        var stepSnapshot = new ConversationStepSnapshot();
        var workflowRun = new WorkflowRunSnapshot();
        workflowRun.getLifecycleTasks().add(new ResultSnapshot("input:initial", "message 0", null, new Date(), null, true));
        stepSnapshot.getWorkflows().add(workflowRun);
        snapshot.getConversationSteps().add(stepSnapshot);
        var output = new ConversationOutput();
        output.put("input", "message 0");
        snapshot.getConversationOutputs().add(output);
        return snapshot;
    }
}
