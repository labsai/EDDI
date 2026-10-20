/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.datastore.IResourceStore.ResourceNotFoundException;
import ai.labs.eddi.datastore.IResourceStore.ResourceStoreException;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.model.TurnError;
import ai.labs.eddi.engine.api.IConversationService.ConversationResponseHandler;
import ai.labs.eddi.engine.api.IGroupConversationService;
import ai.labs.eddi.engine.hitl.tools.IHitlToolJournalStore;
import ai.labs.eddi.engine.lifecycle.model.HitlDecision;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.memory.MemoryKeys;
import ai.labs.eddi.engine.memory.descriptor.IConversationDescriptorStore;
import ai.labs.eddi.engine.memory.model.ConversationOutput;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot;
import ai.labs.eddi.engine.memory.descriptor.model.ConversationDescriptor;
import ai.labs.eddi.engine.security.ConversationAccessGuard;
import ai.labs.eddi.engine.hitl.HitlAccessGuard;
import ai.labs.eddi.engine.security.OwnershipValidator;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.ws.rs.container.AsyncResponse;
import jakarta.ws.rs.container.TimeoutHandler;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * The opt-in reply of {@code POST /agents/{id}/resume} and the approver
 * recorded when nobody is authenticated.
 */
@DisplayName("RestAgentEngine.resumeConversation: reply and approver")
class RestAgentEngineResumeReplyTest {

    private static final String CONVERSATION_ID = "conv-resume-reply";
    private static final String USER_ID = "user-1";
    private static final int AGENT_TIMEOUT = 7;

    private IConversationService conversationService;
    private SecurityIdentity identity;
    private Principal principal;
    private AsyncResponse async;
    private RestAgentEngine engine;

    @BeforeEach
    void setUp() throws Exception {
        conversationService = mock(IConversationService.class);
        identity = mock(SecurityIdentity.class);
        principal = mock(Principal.class);
        async = mock(AsyncResponse.class);
        var ownershipValidator = mock(OwnershipValidator.class);
        var descriptorStore = mock(IConversationDescriptorStore.class);
        var descriptor = new ConversationDescriptor();
        descriptor.setUserId(USER_ID);
        doReturn(descriptor).when(descriptorStore).readDescriptor(anyString(), anyInt());
        doNothing().when(ownershipValidator).requireOwnerOrAdmin(any(), any(), any());
        doReturn(principal).when(identity).getPrincipal();
        doReturn(USER_ID).when(principal).getName();

        var hitlAccessGuard = new HitlAccessGuard(identity, ownershipValidator, descriptorStore, conversationService,
                mock(IGroupConversationService.class));
        var conversationAccessGuard = new ConversationAccessGuard(identity, ownershipValidator, descriptorStore);
        engine = new RestAgentEngine(conversationService, mock(IConversationMemoryStore.class), identity, ownershipValidator,
                conversationAccessGuard, mock(ResourceAccessGuard.class), hitlAccessGuard, mock(IHitlToolJournalStore.class),
                mock(AgentDisplayNameResolver.class), AGENT_TIMEOUT);
    }

    private static HitlDecision approved() {
        var decision = new HitlDecision();
        decision.setVerdict(HitlDecision.HitlVerdict.APPROVED);
        return decision;
    }

    private Response responseSentTo(AsyncResponse target) {
        var captor = ArgumentCaptor.forClass(Object.class);
        verify(target).resume(captor.capture());
        return (Response) captor.getValue();
    }

    // ---- reply ----

    @Test
    @DisplayName("no flag: an empty 200, no handler, the three-argument service call as before")
    void noFlagIsAnEmpty200() throws Exception {
        engine.resumeConversation(CONVERSATION_ID, approved(), null, null, null, async);

        Response response = responseSentTo(async);
        assertEquals(200, response.getStatus());
        assertNull(response.getEntity());
        verify(conversationService).resumeConversation(eq(CONVERSATION_ID), any(), isNull());
        verify(conversationService, never()).resumeConversation(anyString(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("returnCurrentStepOnly: waits for the resumed turn and answers with the snapshot, secret context as its placeholder")
    void flagAnswersWithTheTurn() throws Exception {
        var snapshot = new SimpleConversationMemorySnapshot();
        snapshot.setConversationState(ConversationState.READY);
        var output = new ConversationOutput();
        output.put("context", Map.of("userToken", MemoryKeys.SECRET_CONTEXT_PLACEHOLDER));
        output.put("output", List.of("done"));
        snapshot.setConversationOutputs(List.of(output));
        doAnswer(inv -> {
            ConversationResponseHandler handler = inv.getArgument(2);
            // The service has not answered yet: nothing may have been sent.
            verify(async, never()).resume(any(Object.class));
            handler.onComplete(snapshot);
            return null;
        }).when(conversationService).resumeConversation(eq(CONVERSATION_ID), any(), any(), eq(false), eq(true), eq(List.of()));

        engine.resumeConversation(CONVERSATION_ID, approved(), null, true, null, async);

        var captor = ArgumentCaptor.forClass(Object.class);
        verify(async).resume(captor.capture());
        assertSame(snapshot, captor.getValue());
        assertTrue(snapshot.getConversationOutputs().getFirst().toString().contains(MemoryKeys.SECRET_CONTEXT_PLACEHOLDER));
    }

    @Test
    @DisplayName("the say flags are passed through to the service unchanged")
    void flagsArePassedThrough() throws Exception {
        engine.resumeConversation(CONVERSATION_ID, approved(), true, false, List.of("conversationOutputs"), async);

        verify(conversationService).resumeConversation(eq(CONVERSATION_ID), any(), any(), eq(true), eq(false), eq(List.of("conversationOutputs")));
    }

    @Test
    @DisplayName("a failed turn's Retry-After rides along, as on say")
    void retryAfterRidesAlong() throws Exception {
        var snapshot = new SimpleConversationMemorySnapshot();
        snapshot.setError(new TurnError("PROVIDER_RATE_LIMITED", true, 12_000L, "slow down"));
        doAnswer(inv -> {
            ((ConversationResponseHandler) inv.getArgument(2)).onComplete(snapshot);
            return null;
        }).when(conversationService).resumeConversation(eq(CONVERSATION_ID), any(), any(), any(), any(), any());

        engine.resumeConversation(CONVERSATION_ID, approved(), null, true, null, async);

        Response response = responseSentTo(async);
        assertEquals(12L, response.getHeaders().getFirst("Retry-After"));
    }

    @Test
    @DisplayName("the request gets say's timeout and a 408 when the turn outlives it")
    void timeoutIs408() throws Exception {
        engine.resumeConversation(CONVERSATION_ID, approved(), null, true, null, async);

        verify(async).setTimeout(AGENT_TIMEOUT, TimeUnit.SECONDS);
        var handler = ArgumentCaptor.forClass(TimeoutHandler.class);
        verify(async).setTimeoutHandler(handler.capture());
        var timedOut = mock(AsyncResponse.class);
        handler.getValue().handleTimeout(timedOut);
        assertEquals(408, responseSentTo(timedOut).getStatus());
    }

    @Test
    @DisplayName("errors keep their mapping with the flag set: 400, 404, 409, 500")
    void errorsKeepTheirMapping() throws Exception {
        assertErrorStatus(new IllegalArgumentException("context['x'] must be marked secret: true"), 400);
        assertErrorStatus(new ResourceNotFoundException("gone"), 404);
        assertErrorStatus(new IllegalStateException("not awaiting"), 409);
        assertErrorStatus(new ResourceStoreException("db"), 500);
    }

    private void assertErrorStatus(Exception failure, int status) throws Exception {
        var target = mock(AsyncResponse.class);
        doThrow(failure).when(conversationService).resumeConversation(eq(CONVERSATION_ID), any(), any(), any(), any(), any());
        doReturn(ConversationState.READY).when(conversationService).getConversationState(CONVERSATION_ID);

        engine.resumeConversation(CONVERSATION_ID, approved(), null, true, null, target);

        assertEquals(status, responseSentTo(target).getStatus(), failure.toString());
    }

    @Test
    @DisplayName("missing verdict is a 400 and the service is never called")
    void missingVerdictIs400() throws Exception {
        engine.resumeConversation(CONVERSATION_ID, new HitlDecision(), null, true, null, async);

        assertEquals(400, responseSentTo(async).getStatus());
        verify(conversationService, never()).resumeConversation(anyString(), any(), any(), any(), any(), any());
    }

    // ---- approver ----

    private String decidedByAfterResume(boolean authenticated, String claimed) throws Exception {
        if (!authenticated) {
            doReturn(null).when(identity).getPrincipal();
            doReturn(true).when(identity).isAnonymous();
        }
        var decision = approved();
        decision.setDecidedBy(claimed);
        var captor = ArgumentCaptor.forClass(HitlDecision.class);
        engine.resumeConversation(CONVERSATION_ID, decision, null, null, null, async);
        verify(conversationService).resumeConversation(eq(CONVERSATION_ID), captor.capture(), isNull());
        return captor.getValue().getDecidedBy();
    }

    @Test
    @DisplayName("anonymous with a body decidedBy: recorded as unverified:<value>")
    void anonymousClaimIsRecordedUnverified() throws Exception {
        assertEquals("unverified:alice@example.com", decidedByAfterResume(false, "alice@example.com"));
    }

    @Test
    @DisplayName("authenticated: the principal wins and the body is ignored")
    void principalWins() throws Exception {
        assertEquals(USER_ID, decidedByAfterResume(true, "mallory"));
    }

    @Test
    @DisplayName("anonymous without a body decidedBy (or a blank one): stays null")
    void anonymousWithoutClaimStaysNull() throws Exception {
        assertNull(decidedByAfterResume(false, null));
    }

    @Test
    @DisplayName("a blank claim stays null")
    void blankClaimStaysNull() throws Exception {
        assertNull(decidedByAfterResume(false, "   "));
    }

    @Test
    @DisplayName("an invalid or oversized claim is trimmed, capped at 128 and restricted to [A-Za-z0-9._@:+-]")
    void claimIsSanitised() throws Exception {
        String result = decidedByAfterResume(false, "  ev il\n<b>x</b>" + "a".repeat(300));

        assertTrue(result.startsWith("unverified:ev_il__b_x__b_a"), result);
        assertEquals("unverified:".length() + 128, result.length(), "the claim is capped at 128 characters");
        assertTrue(result.substring("unverified:".length()).matches("[A-Za-z0-9._@:+-]+"), result);
    }
}
