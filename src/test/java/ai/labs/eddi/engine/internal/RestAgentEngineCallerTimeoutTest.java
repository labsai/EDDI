/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.api.IConversationService.ConversationResponseHandler;
import ai.labs.eddi.engine.api.IGroupConversationService;
import ai.labs.eddi.engine.hitl.HitlAccessGuard;
import ai.labs.eddi.engine.hitl.tools.IHitlToolJournalStore;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.memory.descriptor.IConversationDescriptorStore;
import ai.labs.eddi.engine.memory.descriptor.model.ConversationDescriptor;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot;
import ai.labs.eddi.engine.model.Deployment;
import ai.labs.eddi.engine.model.InputData;
import ai.labs.eddi.engine.security.ConversationAccessGuard;
import ai.labs.eddi.engine.security.OwnershipValidator;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import com.mongodb.MongoTimeoutException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.ws.rs.container.AsyncResponse;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The {@code 408} a caller of {@code say} gets at
 * {@code systemRuntime.agentTimeoutInSeconds} is measured from when the request
 * <em>arrived</em>, not from when it got a thread, and the turn learns when its
 * caller gives up.
 */
@DisplayName("RestAgentEngine — the caller's timeout counts from arrival")
class RestAgentEngineCallerTimeoutTest {

    private static final int TIMEOUT_SECONDS = 30;

    private IConversationService conversationService;
    private SimpleMeterRegistry meterRegistry;
    private TurnDeadlineHeaderReader headerReader;
    private RestAgentEngine restAgentEngine;

    @BeforeEach
    void setUp() throws Exception {
        conversationService = mock(IConversationService.class);
        var descriptorStore = mock(IConversationDescriptorStore.class);
        var identity = mock(SecurityIdentity.class);
        var ownershipValidator = mock(OwnershipValidator.class);
        when(ownershipValidator.validateAndResolveUserId(any(), any())).thenAnswer(inv -> inv.getArgument(1));
        var ownedDescriptor = new ConversationDescriptor();
        ownedDescriptor.setUserId("test-user");
        when(descriptorStore.readDescriptor(anyString(), anyInt())).thenReturn(ownedDescriptor);
        var hitlAccessGuard = new HitlAccessGuard(identity, ownershipValidator, descriptorStore, conversationService,
                mock(IGroupConversationService.class));
        restAgentEngine = new RestAgentEngine(conversationService, mock(IConversationMemoryStore.class), identity, ownershipValidator,
                new ConversationAccessGuard(identity, ownershipValidator, descriptorStore), mock(ResourceAccessGuard.class), hitlAccessGuard,
                mock(IHitlToolJournalStore.class), mock(AgentDisplayNameResolver.class), TIMEOUT_SECONDS);
        meterRegistry = new SimpleMeterRegistry();
        restAgentEngine.meterRegistry = meterRegistry;
        // The arrival comes from the request (the router's first route), which a
        // directly constructed engine has none of: the reader stands in for it.
        headerReader = mock(TurnDeadlineHeaderReader.class);
        // Explicit: an unstubbed Long would be 0L (epoch 0), not "no arrival".
        when(headerReader.requestArrivalEpochMs()).thenReturn(null);
        restAgentEngine.turnDeadlineHeaderReader = headerReader;
    }

    /** A turn whose request arrived {@code ago} ms before now. */
    private InputData arrivedMsAgo(long ago) {
        long arrival = System.currentTimeMillis() - ago;
        when(headerReader.requestArrivalEpochMs()).thenReturn(arrival);
        var inputData = new InputData("Hello", Map.of());
        inputData.setRequestArrivalEpochMs(arrival);
        return inputData;
    }

    private static int statusOfResume(AsyncResponse response) {
        var captor = ArgumentCaptor.forClass(Response.class);
        verify(response).resume(captor.capture());
        return captor.getValue().getStatus();
    }

    @Test
    @DisplayName("a request that waited 10 s for a thread has 20 s left, and the turn is told when its caller gives up")
    void timeoutCountsFromArrival() throws Exception {
        var response = mock(AsyncResponse.class);
        var inputData = arrivedMsAgo(10_000);
        long arrival = inputData.getRequestArrivalEpochMs();

        restAgentEngine.sayWithinContext("conv-1", false, false, List.of(), inputData, response);

        var remaining = ArgumentCaptor.forClass(Long.class);
        verify(response).setTimeout(remaining.capture(), eq(TimeUnit.MILLISECONDS));
        assertTrue(remaining.getValue() > 19_000 && remaining.getValue() <= 20_000,
                "the 30 s budget minus the 10 s the request already waited, was " + remaining.getValue());
        assertEquals(arrival + 30_000, inputData.getCallerGivesUpAtEpochMs());
        verify(conversationService).say(eq("conv-1"), eq(false), eq(false), eq(List.of()), eq(inputData), eq(false), any());
    }

    @Test
    @DisplayName("a request whose whole budget went on waiting for a thread is answered 408 at once and queues no turn")
    void spentBudgetIsAnswered408WithoutATurn() throws Exception {
        var response = mock(AsyncResponse.class);

        restAgentEngine.sayWithinContext("conv-1", false, false, List.of(), arrivedMsAgo(31_000), response);

        assertEquals(408, statusOfResume(response));
        verify(conversationService, never()).say(anyString(), any(), any(), any(), any(), anyBoolean(), any());
        verify(response, never()).setTimeout(anyLong(), any());
        assertEquals(1.0, meterRegistry.counter("eddi.turn.rejected", "reason", "caller_timed_out").count());
    }

    @Test
    @DisplayName("no recorded arrival (not over HTTP): the full timeout from now, and no caller deadline on the turn")
    void noArrivalKeepsTheFullTimeout() throws Exception {
        var response = mock(AsyncResponse.class);
        var inputData = new InputData("Hello", Map.of());

        restAgentEngine.sayWithinContext("conv-1", false, false, List.of(), inputData, response);

        verify(response).setTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertNull(inputData.getCallerGivesUpAtEpochMs());
    }

    @Test
    @DisplayName("a turn that found no free database connection in time is refused 503 with Retry-After, not a 500")
    void poolWaitTimeoutOnATurnIs503() throws Exception {
        var response = mock(AsyncResponse.class);
        doThrow(new MongoTimeoutException("Timed out after 10000 ms while waiting for a connection")).when(conversationService)
                .say(anyString(), any(), any(), any(), any(), anyBoolean(), any());

        restAgentEngine.sayWithinContext("conv-1", false, false, List.of(), arrivedMsAgo(1_000), response);

        var captor = ArgumentCaptor.forClass(Response.class);
        verify(response).resume(captor.capture());
        assertEquals(503, captor.getValue().getStatus());
        assertEquals("5", captor.getValue().getHeaderString("Retry-After"));
    }

    @Test
    @DisplayName("a conversation start that found no free database connection in time is refused 503 with Retry-After")
    void poolWaitTimeoutOnAStartIs503() throws Exception {
        when(conversationService.startConversation(any(), anyString(), any(), any()))
                .thenThrow(new MongoTimeoutException("Timed out after 10000 ms while waiting for a connection"));

        Response response = restAgentEngine.startConversation("6a0f5d783ab1ce8997018cb1", Deployment.Environment.production, "u");

        assertEquals(503, response.getStatus());
        assertEquals("5", response.getHeaderString("Retry-After"));
    }

    @Test
    @DisplayName("a turn dropped because its caller gave up answers 408, whether or not the timer already did")
    void callerGoneAnswers408() throws Exception {
        var response = mock(AsyncResponse.class);

        restAgentEngine.sayWithinContext("conv-1", false, false, List.of(), arrivedMsAgo(1_000), response);
        var handler = ArgumentCaptor.forClass(ConversationResponseHandler.class);
        verify(conversationService).say(anyString(), any(), any(), any(), any(), anyBoolean(), handler.capture());
        handler.getValue().onCallerGone(new SimpleConversationMemorySnapshot());

        assertEquals(408, statusOfResume(response));
    }
}
