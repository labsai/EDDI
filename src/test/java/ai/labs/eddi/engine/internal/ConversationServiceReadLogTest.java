/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.configs.properties.IUserMemoryStore;
import ai.labs.eddi.engine.api.IConversationService.ConversationLogResult;
import ai.labs.eddi.engine.audit.AuditLedgerService;
import ai.labs.eddi.engine.gdpr.GdprComplianceService;
import ai.labs.eddi.engine.caching.ICache;
import ai.labs.eddi.engine.caching.ICacheFactory;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.memory.descriptor.IConversationDescriptorStore;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.runtime.IAgentFactory;
import ai.labs.eddi.engine.runtime.IConversationCoordinator;
import ai.labs.eddi.engine.runtime.IConversationSetup;
import ai.labs.eddi.engine.runtime.IRuntime;
import ai.labs.eddi.engine.tenancy.TenantQuotaService;
import ai.labs.eddi.engine.tenancy.model.QuotaCheckResult;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.configs.agents.IAgentStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Regression tests for BUG-4: ConversationService.readConversationLog() NPE
 * when logSize is null.
 * <p>
 * The MCP layer converts {@code logSize=0} to {@code null} before calling
 * {@code readConversationLog}. Before the fix, passing {@code null} for logSize
 * caused a NullPointerException when unboxing the Integer to int.
 */
class ConversationServiceReadLogTest {

    private ConversationService conversationService;
    private IConversationMemoryStore conversationMemoryStore;

    private static final String CONVERSATION_ID = "test-conversation-id";
    private static final String AGENT_ID = "test-agent-id";
    private static final String USER_ID = "test-user-id";

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        IAgentFactory agentFactory = mock(IAgentFactory.class);
        conversationMemoryStore = mock(IConversationMemoryStore.class);
        IConversationDescriptorStore conversationDescriptorStore = mock(IConversationDescriptorStore.class);
        IConversationCoordinator conversationCoordinator = mock(IConversationCoordinator.class);
        IConversationSetup conversationSetup = mock(IConversationSetup.class);
        IRuntime runtime = mock(IRuntime.class);
        IContextLogger contextLogger = mock(IContextLogger.class);
        ICacheFactory cacheFactory = mock(ICacheFactory.class);
        ICache<String, ConversationState> conversationStateCache = mock(ICache.class);
        AuditLedgerService auditLedgerService = mock(AuditLedgerService.class);
        GdprComplianceService gdprComplianceService = mock(GdprComplianceService.class);
        TenantQuotaService tenantQuotaService = mock(TenantQuotaService.class);
        IScheduleStore scheduleStore = mock(IScheduleStore.class);
        IAgentStore agentStore = mock(IAgentStore.class);
        IUserMemoryStore userMemoryStore = mock(IUserMemoryStore.class);

        when(tenantQuotaService.acquireConversationSlot()).thenReturn(QuotaCheckResult.OK);
        when(tenantQuotaService.acquireApiCallSlot()).thenReturn(QuotaCheckResult.OK);
        when(auditLedgerService.isEnabled()).thenReturn(false);

        doReturn(conversationStateCache).when(cacheFactory).getCache("conversationState");
        when(contextLogger.createLoggingContext(any(), any(), any(), any())).thenReturn(new HashMap<>());

        conversationService = new ConversationService(agentFactory, conversationMemoryStore,
                conversationDescriptorStore, userMemoryStore, conversationCoordinator, conversationSetup,
                cacheFactory, runtime, contextLogger, auditLedgerService, gdprComplianceService,
                tenantQuotaService, scheduleStore, agentStore,
                new SimpleMeterRegistry(), 60);
    }

    private ConversationMemorySnapshot createEmptySnapshot() {
        var snapshot = new ConversationMemorySnapshot();
        snapshot.setAgentId(AGENT_ID);
        snapshot.setUserId(USER_ID);
        snapshot.setConversationState(ConversationState.READY);
        snapshot.setConversationSteps(new ArrayList<>());
        return snapshot;
    }

    /**
     * BUG-4: When MCP sends logSize=null (which happens when user doesn't specify
     * logSize), the method must not throw NPE. Before the fix, {@code logSize} was
     * passed directly to {@code generate(int)} causing unboxing NPE.
     */
    @Test
    void readConversationLog_nullLogSize_doesNotThrowNPE() throws Exception {
        // Arrange
        when(conversationMemoryStore.loadConversationMemorySnapshot(CONVERSATION_ID))
                .thenReturn(createEmptySnapshot());

        // Act — null logSize must not cause NPE
        ConversationLogResult result = conversationService.readConversationLog(CONVERSATION_ID, "text", null);

        // Assert
        assertNotNull(result);
        assertEquals("text/plain", result.mediaType());
    }

    /**
     * The MCP layer converts logSize=0 to null. This tests that 0 also works
     * directly (passed as Integer 0) — the fix treats 0 as a valid value meaning
     * "no steps" rather than converting to null.
     */
    @Test
    void readConversationLog_zeroLogSize_doesNotThrowNPE() throws Exception {
        // Arrange
        when(conversationMemoryStore.loadConversationMemorySnapshot(CONVERSATION_ID))
                .thenReturn(createEmptySnapshot());

        // Act — zero logSize should work without error
        ConversationLogResult result = conversationService.readConversationLog(CONVERSATION_ID, "text", 0);

        // Assert
        assertNotNull(result);
        assertEquals("text/plain", result.mediaType());
    }

    /**
     * Normal positive logSize — verifies the standard path still works.
     */
    @Test
    void readConversationLog_positiveLogSize_works() throws Exception {
        // Arrange
        when(conversationMemoryStore.loadConversationMemorySnapshot(CONVERSATION_ID))
                .thenReturn(createEmptySnapshot());

        // Act
        ConversationLogResult result = conversationService.readConversationLog(CONVERSATION_ID, "json", 10);

        // Assert
        assertNotNull(result);
        assertEquals("application/json", result.mediaType());
    }
}
