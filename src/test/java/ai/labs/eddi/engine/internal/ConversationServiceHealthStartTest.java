/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.properties.IUserMemoryStore;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.audit.AuditLedgerService;
import ai.labs.eddi.engine.caching.ICache;
import ai.labs.eddi.engine.caching.ICacheFactory;
import ai.labs.eddi.engine.events.HitlResumeCompletedEvent;
import ai.labs.eddi.engine.gdpr.GdprComplianceService;
import ai.labs.eddi.engine.health.HealthTurnRecorder;
import ai.labs.eddi.engine.lifecycle.IConversation;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.memory.descriptor.IConversationDescriptorStore;
import ai.labs.eddi.engine.memory.model.ConversationProperties;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.model.Deployment.Environment;
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
import jakarta.enterprise.event.Event;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Map;
import java.util.Stack;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * The CONVERSATION_START turn runs inline in {@code startConversation} and
 * never passes the step runner's hook, so the service reports it to Agent
 * Health itself — once, under the conversation id the store assigned.
 */
@DisplayName("ConversationService — the start turn reaches Agent Health")
class ConversationServiceHealthStartTest {

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
    private HealthTurnRecorder recorder;
    private IConversationMemory memory;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        MockitoAnnotations.openMocks(this);
        doReturn(conversationStateCache).when(cacheFactory).getCache("conversationState", ConversationService.CONVERSATION_STATE_CACHE_TTL);
        conversationService = new ConversationService(agentFactory, conversationMemoryStore, conversationDescriptorStore, userMemoryStore,
                conversationCoordinator, conversationSetup, cacheFactory, runtime, contextLogger, auditLedgerService, gdprComplianceService,
                tenantQuotaService, scheduleStore, agentStore, jsonSerialization, new SimpleMeterRegistry(),
                (Event<HitlResumeCompletedEvent>) mock(Event.class), new CallerIdentityContext(null, null), 30);
        recorder = mock(HealthTurnRecorder.class);
        conversationService.healthTurnRecorder = recorder;

        IAgent agent = mock(IAgent.class);
        IConversation conversation = mock(IConversation.class);
        memory = mock(IConversationMemory.class);
        doReturn(USER_ID).when(conversationSetup).computeAnonymousUserIdIfEmpty(eq(USER_ID), isNull());
        doReturn(false).when(gdprComplianceService).isProcessingRestricted(USER_ID);
        doReturn(agent).when(agentFactory).getLatestReadyAgent(ENV, AGENT_ID);
        doReturn(new QuotaCheckResult(true, null)).when(tenantQuotaService).acquireConversationSlot();
        doReturn(conversation).when(agent).startConversation(eq(USER_ID), anyMap(), any(), isNull());
        doReturn(memory).when(conversation).getConversationMemory();
        doReturn(ConversationState.READY).when(memory).getConversationState();
        doReturn(new Stack<>()).when(memory).getRedoCache();
        doReturn(mock(IConversationMemory.IConversationStepStack.class)).when(memory).getAllSteps();
        doReturn(new ConversationProperties(memory)).when(memory).getConversationProperties();
    }

    @Test
    @DisplayName("a started conversation reports its start turn once, named by the stored id, as settled")
    void reportsStartTurnOnce() throws Exception {
        doReturn(CONVERSATION_ID).when(conversationMemoryStore).storeConversationMemorySnapshot(any());

        conversationService.startConversation(ENV, AGENT_ID, USER_ID, Map.of());

        verify(recorder, times(1)).record(eq(memory), eq(CONVERSATION_ID), isNull(), anyLong(), eq(false), eq(true));
    }

    @Test
    @DisplayName("a conversation that could not be stored reports nothing")
    void failedStartReportsNothing() throws Exception {
        doThrow(new IllegalStateException("store down")).when(conversationMemoryStore).storeConversationMemorySnapshot(any());

        assertThrows(RuntimeException.class, () -> conversationService.startConversation(ENV, AGENT_ID, USER_ID, Map.of()));

        verify(recorder, never()).record(any(), any(), any(), any(), anyBoolean(), anyBoolean());
    }
}
