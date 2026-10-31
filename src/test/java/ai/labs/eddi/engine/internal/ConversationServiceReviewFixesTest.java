/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.deployment.IDeploymentStore;
import ai.labs.eddi.configs.deployment.model.DeploymentInfo;
import ai.labs.eddi.configs.properties.IUserMemoryStore;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.audit.AuditLedgerService;
import ai.labs.eddi.engine.caching.ICache;
import ai.labs.eddi.engine.caching.ICacheFactory;
import ai.labs.eddi.engine.gdpr.GdprComplianceService;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.memory.descriptor.IConversationDescriptorStore;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot;
import ai.labs.eddi.engine.model.Deployment.Environment;
import ai.labs.eddi.engine.runtime.IAgent;
import ai.labs.eddi.engine.runtime.IAgentFactory;
import ai.labs.eddi.engine.runtime.IConversationCoordinator;
import ai.labs.eddi.engine.runtime.IConversationSetup;
import ai.labs.eddi.engine.runtime.IRuntime;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.engine.security.CallerIdentityContext;
import ai.labs.eddi.engine.tenancy.TenantQuotaService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Lazy deploy of an undeployed version, the state a cancelled turn reports, and
 * the admin state reset's compare-and-set.
 */
class ConversationServiceReviewFixesTest {

    private static final Environment ENV = Environment.production;
    private static final String AGENT_ID = "agent-123";
    private static final String CONVERSATION_ID = "conv-456";

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
    @Mock
    private IDeploymentStore deploymentStore;

    private ConversationService conversationService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        doReturn(conversationStateCache).when(cacheFactory).getCache("conversationState", ConversationService.CONVERSATION_STATE_CACHE_TTL);
        conversationService = new ConversationService(
                agentFactory, conversationMemoryStore, conversationDescriptorStore,
                userMemoryStore, conversationCoordinator, conversationSetup,
                cacheFactory, runtime, contextLogger, auditLedgerService,
                gdprComplianceService, tenantQuotaService, scheduleStore, agentStore,
                jsonSerialization,
                new SimpleMeterRegistry(), ConversationServiceTestFixtures.hitlResumeEvent(),
                new CallerIdentityContext(null, null), 30);
        conversationService.deploymentStore = deploymentStore;
    }

    private static DeploymentInfo record(DeploymentInfo.DeploymentStatus status) {
        var info = new DeploymentInfo();
        info.setAgentId(AGENT_ID);
        info.setAgentVersion(2);
        info.setDeploymentStatus(status);
        return info;
    }

    // --- lazy deploy ---

    @Test
    @DisplayName("an intentionally undeployed version is not deployed again on demand")
    void undeployedVersionIsNotRedeployed() throws Exception {
        when(agentFactory.getAgent(ENV, AGENT_ID, 2)).thenReturn(null);
        when(deploymentStore.getDeploymentInfo(ENV.toString(), AGENT_ID, 2)).thenReturn(record(DeploymentInfo.DeploymentStatus.undeployed));

        assertNull(conversationService.getAgent(ENV, AGENT_ID, 2));

        verify(agentFactory, never()).deployAgent(any(), anyString(), anyInt(), any());
    }

    @Test
    @DisplayName("a version merely missing on this node is still deployed on demand")
    void missingVersionIsDeployedOnDemand() throws Exception {
        IAgent agent = mock(IAgent.class);
        when(agentFactory.getAgent(ENV, AGENT_ID, 2)).thenReturn(null, agent);
        when(deploymentStore.getDeploymentInfo(ENV.toString(), AGENT_ID, 2)).thenReturn(record(DeploymentInfo.DeploymentStatus.deployed));

        assertSame(agent, conversationService.getAgent(ENV, AGENT_ID, 2));

        verify(agentFactory).deployAgent(ENV, AGENT_ID, 2, null);
    }

    @Test
    @DisplayName("a version with no deployment record (transient) is still deployed on demand")
    void unrecordedVersionIsDeployedOnDemand() throws Exception {
        when(agentFactory.getAgent(ENV, AGENT_ID, 2)).thenReturn(null);
        when(deploymentStore.getDeploymentInfo(ENV.toString(), AGENT_ID, 2)).thenReturn(null);

        conversationService.getAgent(ENV, AGENT_ID, 2);

        verify(agentFactory).deployAgent(ENV, AGENT_ID, 2, null);
    }

    // --- cancelled turn ---

    @Test
    @DisplayName("a cancelled turn whose pipeline stopped as ENDED is reported as EXECUTION_INTERRUPTED")
    void cancelledTurnIsReportedInterrupted() throws Exception {
        IConversationMemory memory = mock(IConversationMemory.class);
        when(memory.isCancelled()).thenReturn(true);
        var snapshot = new SimpleConversationMemorySnapshot();
        snapshot.setConversationState(ConversationState.ENDED);
        when(conversationMemoryStore.getConversationState(CONVERSATION_ID)).thenReturn(ConversationState.READY);

        conversationService.reportCancelledTurnAsInterrupted(CONVERSATION_ID, memory, snapshot);

        assertEquals(ConversationState.EXECUTION_INTERRUPTED, snapshot.getConversationState());
    }

    @Test
    @DisplayName("a turn cancelled by a real end keeps reporting ENDED")
    void endedConversationStaysEnded() throws Exception {
        IConversationMemory memory = mock(IConversationMemory.class);
        when(memory.isCancelled()).thenReturn(true);
        var snapshot = new SimpleConversationMemorySnapshot();
        snapshot.setConversationState(ConversationState.ENDED);
        when(conversationMemoryStore.getConversationState(CONVERSATION_ID)).thenReturn(ConversationState.ENDED);

        conversationService.reportCancelledTurnAsInterrupted(CONVERSATION_ID, memory, snapshot);

        assertEquals(ConversationState.ENDED, snapshot.getConversationState());
    }

    @Test
    @DisplayName("an ordinary STOP_CONVERSATION turn (not cancelled) keeps reporting ENDED")
    void uncancelledEndStaysEnded() {
        IConversationMemory memory = mock(IConversationMemory.class);
        var snapshot = new SimpleConversationMemorySnapshot();
        snapshot.setConversationState(ConversationState.ENDED);

        conversationService.reportCancelledTurnAsInterrupted(CONVERSATION_ID, memory, snapshot);

        assertEquals(ConversationState.ENDED, snapshot.getConversationState());
    }

    // --- admin reset ---

    @Test
    @DisplayName("the admin reset is a compare-and-set from the state the caller read")
    void resetIsCompareAndSet() throws Exception {
        when(conversationMemoryStore.compareAndSetState(CONVERSATION_ID, ConversationState.ERROR, ConversationState.READY)).thenReturn(true);

        assertTrue(conversationService.resetConversationState(CONVERSATION_ID, ConversationState.ERROR, ConversationState.READY));

        verify(conversationStateCache).put(CONVERSATION_ID, ConversationState.READY);
        verify(conversationMemoryStore, never()).setConversationState(anyString(), any());
    }

    @Test
    @DisplayName("a reset that loses the race changes nothing and reports it")
    void lostResetChangesNothing() throws Exception {
        when(conversationMemoryStore.compareAndSetState(CONVERSATION_ID, ConversationState.ERROR, ConversationState.READY)).thenReturn(false);
        when(conversationMemoryStore.getConversationState(CONVERSATION_ID)).thenReturn(ConversationState.IN_PROGRESS);

        assertFalse(conversationService.resetConversationState(CONVERSATION_ID, ConversationState.ERROR, ConversationState.READY));

        verify(conversationStateCache, never()).put(CONVERSATION_ID, ConversationState.READY);
        verify(conversationMemoryStore, never()).setConversationState(anyString(), any());
    }
}
