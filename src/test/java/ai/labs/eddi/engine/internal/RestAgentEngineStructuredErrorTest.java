/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.api.IConversationService.ConversationResponseHandler;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.memory.descriptor.IConversationDescriptorStore;
import ai.labs.eddi.engine.memory.descriptor.model.ConversationDescriptor;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot;
import ai.labs.eddi.engine.model.InputData;
import ai.labs.eddi.engine.model.TurnError;
import ai.labs.eddi.engine.security.ConversationAccessGuard;
import ai.labs.eddi.engine.hitl.HitlAccessGuard;
import ai.labs.eddi.engine.hitl.tools.IHitlToolJournalStore;
import ai.labs.eddi.engine.api.IGroupConversationService;
import ai.labs.eddi.engine.security.OwnershipValidator;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.container.AsyncResponse;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** The caller contract of {@code say}: structured errors and key validation. */
@DisplayName("say: structured errors and idempotency headers")
class RestAgentEngineStructuredErrorTest {

    private IConversationService conversationService;
    private RestAgentEngine engine;

    @BeforeEach
    void setUp() throws Exception {
        conversationService = mock(IConversationService.class);
        var descriptorStore = mock(IConversationDescriptorStore.class);
        var identity = mock(SecurityIdentity.class);
        var ownershipValidator = mock(OwnershipValidator.class);
        when(ownershipValidator.validateAndResolveUserId(any(), any())).thenAnswer(inv -> inv.getArgument(1));
        var descriptor = new ConversationDescriptor();
        descriptor.setUserId("test-user");
        when(descriptorStore.readDescriptor(anyString(), anyInt())).thenReturn(descriptor);
        engine = new RestAgentEngine(conversationService, mock(IConversationMemoryStore.class), identity, ownershipValidator,
                new ConversationAccessGuard(identity, ownershipValidator, descriptorStore), mock(ResourceAccessGuard.class),
                new HitlAccessGuard(identity, ownershipValidator, descriptorStore, conversationService,
                        mock(IGroupConversationService.class)),
                mock(IHitlToolJournalStore.class), mock(AgentDisplayNameResolver.class), 30);
    }

    private Response answerWith(SimpleConversationMemorySnapshot snapshot) throws Exception {
        doAnswer(inv -> {
            ConversationResponseHandler handler = inv.getArgument(6);
            handler.onComplete(snapshot);
            return null;
        }).when(conversationService).say(anyString(), any(), any(), any(), any(), anyBoolean(), any());
        var async = mock(AsyncResponse.class);
        engine.sayWithinContext("a1b2c3d4e5f6a7b8c9d0e1f2", false, false, List.of(), new InputData("hi", new HashMap<>()), async);
        var captor = ArgumentCaptor.forClass(Object.class);
        verify(async).resume(captor.capture());
        return captor.getValue() instanceof Response r ? r : Response.ok(captor.getValue()).build();
    }

    @Test
    @DisplayName("a failed turn keeps its status, carries the structured error and Retry-After when the provider gave one")
    void failedTurnCarriesErrorAndRetryAfter() throws Exception {
        var snapshot = new SimpleConversationMemorySnapshot();
        snapshot.setConversationState(ConversationState.ERROR);
        snapshot.setError(new TurnError("RATE_LIMITED", true, 1500L, "slow down"));

        Response response = answerWith(snapshot);

        assertEquals(200, response.getStatus(), "the status is what it always was");
        assertEquals("2", response.getHeaderString("Retry-After"));
        assertSame(snapshot, response.getEntity());
    }

    @Test
    @DisplayName("an error without a retry hint sets no Retry-After; a good turn is untouched")
    void noRetryAfterWithoutHint() throws Exception {
        var failed = new SimpleConversationMemorySnapshot();
        failed.setError(new TurnError(TurnError.TURN_FAILED, false, null, "boom"));
        assertNull(answerWith(failed).getHeaderString("Retry-After"));

        reset(conversationService);
        var ok = new SimpleConversationMemorySnapshot();
        assertNull(answerWith(ok).getHeaderString("Retry-After"));
    }

    @Test
    @DisplayName("the error serialises as {error:{code,retryable,retryAfterMs,message}} with an explicit null")
    void errorJsonShape() throws Exception {
        var body = new com.fasterxml.jackson.databind.ObjectMapper()
                .writeValueAsString(RestAgentEngine.errorBody(new TurnError("TURN_FAILED", false, null, "m")));
        assertEquals("{\"error\":{\"code\":\"TURN_FAILED\",\"retryable\":false,\"retryAfterMs\":null,\"message\":\"m\"}}", body);
        var clean = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(new SimpleConversationMemorySnapshot());
        assertFalse(clean.contains("\"error\""), "a good turn has no error key");
    }

    @Test
    @DisplayName("an unexpected exception is still a 500, now with the structured body and no exception text")
    void internalErrorBody() throws Exception {
        doThrow(new IllegalStateException("secret-host.internal:5432 refused")).when(conversationService)
                .say(anyString(), any(), any(), any(), any(), anyBoolean(), any());

        var thrown = assertThrows(InternalServerErrorException.class, () -> engine.sayWithinContext("a1b2c3d4e5f6a7b8c9d0e1f2", false, false,
                List.of(), new InputData("hi", new HashMap<>()), mock(AsyncResponse.class)));

        assertEquals(500, thrown.getResponse().getStatus());
        @SuppressWarnings("unchecked")
        var entity = (Map<String, TurnError>) thrown.getResponse().getEntity();
        assertEquals(TurnError.INTERNAL_ERROR, entity.get("error").code());
        assertFalse(entity.get("error").message().contains("secret-host"));
    }

    @Test
    @DisplayName("a malformed idempotency key is a 400 with the structured body, and no turn runs")
    void invalidKeyIs400() throws Exception {
        var current = mock(io.quarkus.vertx.http.runtime.CurrentVertxRequest.class);
        var routing = mock(io.vertx.ext.web.RoutingContext.class);
        var request = mock(io.vertx.core.http.HttpServerRequest.class);
        when(current.getCurrent()).thenReturn(routing);
        when(routing.request()).thenReturn(request);
        when(request.getHeader(IdempotencyKeyHeaderReader.HEADER)).thenReturn("k".repeat(129));
        engine.idempotencyKeyHeaderReader = new IdempotencyKeyHeaderReader(current);
        var async = mock(AsyncResponse.class);

        engine.sayWithinContext("a1b2c3d4e5f6a7b8c9d0e1f2", false, false, List.of(), new InputData("hi", new HashMap<>()), async);

        var captor = ArgumentCaptor.forClass(Response.class);
        verify(async).resume(captor.capture());
        assertEquals(400, captor.getValue().getStatus());
        @SuppressWarnings("unchecked")
        var entity = (Map<String, TurnError>) captor.getValue().getEntity();
        assertEquals("INVALID_IDEMPOTENCY_KEY", entity.get("error").code());
        verify(conversationService, never()).say(anyString(), any(), any(), any(), any(), anyBoolean(), any());
    }
}
