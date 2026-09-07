/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.security;

import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.properties.IUserMemoryStore;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.audit.AuditLedgerService;
import ai.labs.eddi.engine.caching.ICache;
import ai.labs.eddi.engine.caching.ICacheFactory;
import ai.labs.eddi.engine.events.HitlResumeCompletedEvent;
import ai.labs.eddi.engine.gdpr.GdprComplianceService;
import ai.labs.eddi.engine.internal.ConversationService;
import ai.labs.eddi.engine.internal.IContextLogger;
import ai.labs.eddi.engine.lifecycle.IConversation;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IConversationMemory.IConversationStepStack;
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
import ai.labs.eddi.engine.security.ResolutionPrincipal.Provenance;
import ai.labs.eddi.engine.tenancy.TenantQuotaService;
import ai.labs.eddi.engine.tenancy.model.QuotaCheckResult;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.enterprise.event.Event;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Stack;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Where a conversation's {@link ResolutionPrincipal} actually comes from.
 * <p>
 * {@code ConnectionResolver} refuses a {@code PER_USER} grant unless the bound
 * principal is {@link Provenance#VERIFIED}, so this derivation is the only
 * thing standing between a caller-asserted user id and that user's live SaaS
 * tokens. The rule is deliberately narrow — an authenticated identity that IS
 * the conversation's user — and every widening of it is a cross-user credential
 * bug, which is why it is pinned here at the call site rather than only in
 * {@code ResolutionPrincipalTest}.
 */
@DisplayName("ConversationService — deriving and binding the conversation's ResolutionPrincipal")
class ConversationServiceResolutionPrincipalTest {

    private static final Environment ENV = Environment.production;
    private static final String AGENT_ID = "agent-123";
    private static final String CONVERSATION_ID = "conv-456";
    private static final String USER_ID = "alice";

    private ConversationService conversationService;
    private CallerIdentityContext callerIdentityContext;
    private ResolutionPrincipalContext resolutionPrincipalContext;

    private IAgent agent;
    private IConversationMemory memory;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        IAgentFactory agentFactory = mock(IAgentFactory.class);
        IConversationMemoryStore conversationMemoryStore = mock(IConversationMemoryStore.class);
        IConversationSetup conversationSetup = mock(IConversationSetup.class);
        GdprComplianceService gdprComplianceService = mock(GdprComplianceService.class);
        TenantQuotaService tenantQuotaService = mock(TenantQuotaService.class);

        ICacheFactory cacheFactory = mock(ICacheFactory.class);
        doReturn(mock(ICache.class)).when(cacheFactory).getCache("conversationState");

        // A real CallerIdentityContext built without a SecurityIdentity: capture()
        // finds no active request and falls back to whatever this thread has bound,
        // which is exactly the knob these tests need.
        callerIdentityContext = new CallerIdentityContext(null, null);
        callerIdentityContext.clear();
        resolutionPrincipalContext = new ResolutionPrincipalContext();
        resolutionPrincipalContext.clear();

        conversationService = new ConversationService(agentFactory, conversationMemoryStore,
                mock(IConversationDescriptorStore.class), mock(IUserMemoryStore.class),
                mock(IConversationCoordinator.class), conversationSetup, cacheFactory, mock(IRuntime.class),
                mock(IContextLogger.class), mock(AuditLedgerService.class), gdprComplianceService, tenantQuotaService,
                mock(IScheduleStore.class), mock(IAgentStore.class), mock(IJsonSerialization.class),
                new SimpleMeterRegistry(), (Event<HitlResumeCompletedEvent>) mock(Event.class), callerIdentityContext, 30);

        agent = mock(IAgent.class);
        memory = mock(IConversationMemory.class);
        IConversation conversation = mock(IConversation.class);

        doReturn(USER_ID).when(conversationSetup).computeAnonymousUserIdIfEmpty(eq(USER_ID), isNull());
        doReturn(false).when(gdprComplianceService).isProcessingRestricted(USER_ID);
        doReturn(agent).when(agentFactory).getLatestReadyAgent(ENV, AGENT_ID);
        doReturn(new QuotaCheckResult(true, null)).when(tenantQuotaService).acquireConversationSlot();
        doReturn(conversation).when(agent).startConversation(eq(USER_ID), anyMap(), any(), isNull());
        doReturn(memory).when(conversation).getConversationMemory();
        doReturn(ConversationState.READY).when(memory).getConversationState();
        doReturn(new Stack<>()).when(memory).getRedoCache();
        doReturn(mock(IConversationStepStack.class)).when(memory).getAllSteps();
        doReturn(new ConversationProperties(memory)).when(memory).getConversationProperties();
        doReturn(CONVERSATION_ID).when(conversationMemoryStore).storeConversationMemorySnapshot(any());
    }

    @AfterEach
    void tearDown() {
        // Both bindings are static ThreadLocals; leaving one behind would seed the
        // next test class that runs on this thread.
        callerIdentityContext.clear();
        resolutionPrincipalContext.clear();
    }

    @Test
    @DisplayName("an authenticated caller who IS the conversation's user makes the principal VERIFIED")
    void authenticatedCallerMatchingConversationUserIsVerified() throws Exception {
        callerIdentityContext.bind(new CallerIdentity("raw-token", USER_ID, "https://eddi.example.com"));

        conversationService.startConversation(ENV, AGENT_ID, USER_ID, null);

        verify(memory).setResolutionProvenance(Provenance.VERIFIED);
    }

    @Test
    @DisplayName("an authenticated caller opening a conversation for a DIFFERENT user id makes the principal SELF_ASSERTED")
    void authenticatedCallerForAnotherUserIsSelfAsserted() throws Exception {
        // Opening a conversation "on behalf of" someone is an assertion, not a proof.
        // Reading the mere presence of an authenticated caller as verification is what
        // would let one key holder reach every user's SaaS grants.
        callerIdentityContext.bind(new CallerIdentity("raw-token", "operator-bob", "https://eddi.example.com"));

        conversationService.startConversation(ENV, AGENT_ID, USER_ID, null);

        verify(memory).setResolutionProvenance(Provenance.SELF_ASSERTED);
    }

    @Test
    @DisplayName("a user id supplied with no authenticated identity at all makes the principal SELF_ASSERTED")
    void unauthenticatedUserIdIsSelfAsserted() throws Exception {
        // The /v1 api-key shape: the shared key authenticated the request, and
        // X-OpenWebUI-User-Id was believed verbatim. Nothing here authenticated Alice,
        // so nothing may spend Alice's tokens.
        callerIdentityContext.clear();

        conversationService.startConversation(ENV, AGENT_ID, USER_ID, null);

        verify(memory).setResolutionProvenance(Provenance.SELF_ASSERTED);
    }

    @Test
    @DisplayName("a nested conversation for the SAME user inherits the parent turn's provenance")
    void nestedConversationForSameUserInheritsProvenance() throws Exception {
        // A sub-agent conversation is spawned from inside a running pipeline turn,
        // where there is no request to judge by. Deriving from the (absent) caller
        // would downgrade every child to SELF_ASSERTED and silently break PER_USER
        // connections for delegated work.
        injectResolutionPrincipalContext();
        resolutionPrincipalContext.bind(new ResolutionPrincipal(USER_ID, Provenance.VERIFIED));
        // Deliberately no caller: if inheritance were dropped, the fallback would see
        // nobody and answer SELF_ASSERTED, so this assertion is not vacuous.
        callerIdentityContext.clear();

        conversationService.startConversation(ENV, AGENT_ID, USER_ID, null);

        verify(memory).setResolutionProvenance(Provenance.VERIFIED);
    }

    @Test
    @DisplayName("a nested conversation opened for a DIFFERENT user does NOT inherit the parent's verification")
    void nestedConversationForAnotherUserDoesNotInherit() throws Exception {
        // The parent turn belongs to Bob; the child is opened as Alice. Inheriting a
        // verification that was never about Alice is how Bob's pipeline would reach
        // Alice's grants.
        injectResolutionPrincipalContext();
        resolutionPrincipalContext.bind(new ResolutionPrincipal("operator-bob", Provenance.VERIFIED));
        // A caller who WOULD verify Alice on the fallback path, so this test fails if
        // the inherited branch is removed rather than merely narrowing.
        callerIdentityContext.bind(new CallerIdentity("raw-token", USER_ID, "https://eddi.example.com"));

        conversationService.startConversation(ENV, AGENT_ID, USER_ID, null);

        verify(memory).setResolutionProvenance(Provenance.SELF_ASSERTED);
    }

    @Test
    @DisplayName("the derived principal is bound while the agent's CONVERSATION_START turn runs, and released afterwards")
    void principalIsBoundAroundConversationStart() throws Exception {
        // A behavior rule can fire tool calls on the CONVERSATION_START turn, which
        // executes inside startConversation before there is a stored memory to read a
        // principal from. Recording the provenance after the fact is therefore not
        // enough — the binding has to be live during the call.
        injectResolutionPrincipalContext();
        callerIdentityContext.bind(new CallerIdentity("raw-token", USER_ID, "https://eddi.example.com"));

        var boundDuringStart = new AtomicReference<ResolutionPrincipal>();
        // doAnswer, not when(...): the answer has to read thread state at the moment
        // the agent's start turn actually runs, which is the whole question here.
        doAnswer(invocation -> {
            boundDuringStart.set(resolutionPrincipalContext.current());
            IConversation conversation = mock(IConversation.class);
            doReturn(memory).when(conversation).getConversationMemory();
            return conversation;
        }).when(agent).startConversation(eq(USER_ID), anyMap(), any(), isNull());

        conversationService.startConversation(ENV, AGENT_ID, USER_ID, null);

        assertEquals(new ResolutionPrincipal(USER_ID, Provenance.VERIFIED), boundDuringStart.get(),
                "the conversation's own principal must be bound while the CONVERSATION_START turn executes, or a tool "
                        + "call fired by a start-turn behavior rule resolves no per-user credential at all");
        assertNull(resolutionPrincipalContext.current(),
                "the binding must not survive startConversation on this thread — it is a pooled thread that will serve "
                        + "another user next");
    }

    /**
     * {@code ConversationService.resolutionPrincipalContext} is CDI field-injected
     * and package-private, so a directly-constructed service has none. Setting it
     * reflectively is what makes the binding observable from this package; a rename
     * of the field fails this test loudly, which is the intended coupling.
     */
    private void injectResolutionPrincipalContext() throws Exception {
        var field = ConversationService.class.getDeclaredField("resolutionPrincipalContext");
        field.setAccessible(true);
        field.set(conversationService, resolutionPrincipalContext);
    }
}
