/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.properties.IUserMemoryStore;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.api.IConversationService.ConversationResponseHandler;
import ai.labs.eddi.engine.api.IConversationService.StreamingResponseHandler;
import ai.labs.eddi.engine.audit.AuditLedgerService;
import ai.labs.eddi.engine.caching.ICache;
import ai.labs.eddi.engine.caching.ICacheFactory;
import ai.labs.eddi.engine.gdpr.GdprComplianceService;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.memory.descriptor.IConversationDescriptorStore;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.model.InputData;
import ai.labs.eddi.engine.runtime.IAgentFactory;
import ai.labs.eddi.engine.runtime.IConversationCoordinator;
import ai.labs.eddi.engine.runtime.IConversationSetup;
import ai.labs.eddi.engine.runtime.IRuntime;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.engine.security.CallerIdentityContext;
import ai.labs.eddi.engine.security.ConversationAccessGuard;
import ai.labs.eddi.engine.tenancy.TenantQuotaService;
import ai.labs.eddi.engine.tenancy.model.QuotaCheckResult;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.quarkus.security.ForbiddenException;
import jakarta.enterprise.context.ContextNotActiveException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Finding A1 — the conversation-ownership gate must live in
 * {@link ConversationService}, not only in the REST adapters, so a new adapter
 * cannot re-open the hole by forgetting the check.
 * <p>
 * Both conversationId-only entry points ({@code say} and {@code sayStreaming})
 * are covered: a caller denied by {@link ConversationAccessGuard} must be
 * rejected <em>before</em> the conversation memory is loaded, because loading
 * and running the turn happens under the TARGET conversation's userId.
 */
class ConversationServiceAccessGuardTest {

    private static final String FOREIGN_CONVERSATION_ID = "conversation-of-user-a";

    private ConversationService conversationService;
    private IConversationMemoryStore conversationMemoryStore;
    private ConversationAccessGuard conversationAccessGuard;

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
        IJsonSerialization jsonSerialization = mock(IJsonSerialization.class);

        when(tenantQuotaService.acquireApiCallSlot()).thenReturn(QuotaCheckResult.OK);
        when(auditLedgerService.isEnabled()).thenReturn(false);
        doReturn(conversationStateCache).when(cacheFactory).getCache("conversationState");
        when(contextLogger.createLoggingContext(any(), any(), any(), any())).thenReturn(new HashMap<>());

        conversationService = new ConversationService(agentFactory, conversationMemoryStore,
                conversationDescriptorStore, userMemoryStore, conversationCoordinator, conversationSetup,
                cacheFactory, runtime, contextLogger, auditLedgerService, gdprComplianceService,
                tenantQuotaService, scheduleStore, agentStore,
                jsonSerialization,
                new SimpleMeterRegistry(), ConversationServiceTestFixtures.hitlResumeEvent(),
                new CallerIdentityContext(null, null), 60);

        conversationAccessGuard = mock(ConversationAccessGuard.class);
        conversationService.conversationAccessGuard = conversationAccessGuard;
    }

    private InputData input() {
        return new InputData("hello", Map.of());
    }

    @Test
    @DisplayName("say(conversationId, …) is rejected with 403 for a conversation the caller does not own")
    void sayDeniesForeignConversation() throws Exception {
        doThrow(new ForbiddenException("Access denied: you do not own this conversation"))
                .when(conversationAccessGuard).requireConversationOwner(FOREIGN_CONVERSATION_ID);

        var handler = mock(ConversationResponseHandler.class);

        assertThrows(ForbiddenException.class,
                () -> conversationService.say(FOREIGN_CONVERSATION_ID, false, false, List.of(), input(), false, handler));

        // Denied before the victim's memory (and with it their long-term
        // properties) was ever loaded.
        verify(conversationMemoryStore, never()).loadConversationMemorySnapshot(anyString());
        verify(handler, never()).onComplete(any());
    }

    @Test
    @DisplayName("sayStreaming(conversationId, …) is rejected with 403 for a conversation the caller does not own")
    void sayStreamingDeniesForeignConversation() throws Exception {
        doThrow(new ForbiddenException("Access denied: you do not own this conversation"))
                .when(conversationAccessGuard).requireConversationOwner(FOREIGN_CONVERSATION_ID);

        var handler = mock(StreamingResponseHandler.class);

        assertThrows(ForbiddenException.class,
                () -> conversationService.sayStreaming(FOREIGN_CONVERSATION_ID, false, false, List.of(), input(), handler));

        verify(conversationMemoryStore, never()).loadConversationMemorySnapshot(anyString());
        verify(handler, never()).onComplete(any());
    }

    @Test
    @DisplayName("an owned conversation passes the gate and proceeds to load its memory")
    void ownedConversationProceeds() throws Exception {
        // Guard admits the caller; the turn then fails on the (unstubbed) snapshot,
        // which is enough to prove the gate did not short-circuit it.
        when(conversationMemoryStore.loadConversationMemorySnapshot("my-conversation"))
                .thenThrow(new IllegalStateException("boom"));

        assertThrows(IllegalStateException.class,
                () -> conversationService.say("my-conversation", false, false, List.of(), input(), false,
                        mock(ConversationResponseHandler.class)));

        verify(conversationAccessGuard).requireConversationOwner("my-conversation");
    }

    @Test
    @DisplayName("a server-internal caller with no request context is not blocked by the gate")
    void noRequestContextIsNotBlocked() throws Exception {
        // e.g. the Slack webhook worker thread: no principal exists to compare
        // against, so ownership is not decidable — this path was never checked
        // before and must keep working rather than blow up on the CDI proxy.
        doThrow(new ContextNotActiveException("no request context"))
                .when(conversationAccessGuard).requireConversationOwner("internal-conversation");
        when(conversationMemoryStore.loadConversationMemorySnapshot("internal-conversation"))
                .thenThrow(new IllegalStateException("boom"));

        assertThrows(IllegalStateException.class,
                () -> conversationService.say("internal-conversation", false, false, List.of(), input(), false,
                        mock(ConversationResponseHandler.class)));
    }
}
