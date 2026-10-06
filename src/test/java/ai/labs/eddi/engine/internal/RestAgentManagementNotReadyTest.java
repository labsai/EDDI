/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.engine.api.IRestAgentEngine;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.model.AgentDeployment;
import ai.labs.eddi.engine.model.Context;
import ai.labs.eddi.engine.model.Deployment;
import ai.labs.eddi.engine.model.InputData;
import ai.labs.eddi.engine.triggermanagement.IRestAgentTriggerStore;
import ai.labs.eddi.engine.triggermanagement.IUserConversationStore;
import ai.labs.eddi.engine.triggermanagement.model.AgentTriggerConfiguration;
import ai.labs.eddi.engine.triggermanagement.model.UserConversation;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.container.AsyncResponse;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * While no agent version is ready (startup, rolling deploy) the engine refuses
 * to start a conversation. The managed endpoints used to dereference the
 * missing user conversation and answer 500; they must answer 503 with a
 * Retry-After.
 */
@DisplayName("RestAgentManagement — no agent version ready answers 503 + Retry-After")
class RestAgentManagementNotReadyTest {

    private static final String INTENT = "support";
    private static final String USER = "user-1";
    private static final String AGENT_ID = "aaaaaaaaaaaaaaaaaaaaaaaa";

    private IRestAgentEngine restAgentEngine;
    private IUserConversationStore userConversationStore;
    private AsyncResponse asyncResponse;
    private RestAgentManagement management;

    @BeforeEach
    void setUp() throws Exception {
        restAgentEngine = mock(IRestAgentEngine.class);
        userConversationStore = mock(IUserConversationStore.class);
        var triggerStore = mock(IRestAgentTriggerStore.class);
        asyncResponse = mock(AsyncResponse.class);

        var deployment = new AgentDeployment();
        deployment.setAgentId(AGENT_ID);
        deployment.setEnvironment(Deployment.Environment.production);
        var trigger = new AgentTriggerConfiguration();
        trigger.setIntent(INTENT);
        trigger.setAgentDeployments(List.of(deployment));
        when(triggerStore.readAgentTrigger(INTENT)).thenReturn(trigger);

        management = new RestAgentManagement(restAgentEngine, userConversationStore, triggerStore, false);
        Field identityField = RestAgentManagement.class.getDeclaredField("identity");
        identityField.setAccessible(true);
        identityField.set(management, mock(SecurityIdentity.class));
    }

    private void agentNotReady(int status) {
        when(restAgentEngine.startConversationWithContext(anyString(), any(), anyString(), any()))
                .thenReturn(Response.status(status).entity("Agent is not deployed or not ready").build());
    }

    private static void assert503WithRetryAfter(Response response) {
        assertEquals(503, response.getStatus());
        assertEquals(String.valueOf(RestAgentManagement.NOT_READY_RETRY_AFTER_SECONDS), String.valueOf(response.getHeaderString("Retry-After")));
        assertEquals(RestAgentManagement.NOT_READY_MESSAGE, response.getEntity());
    }

    @Test
    @DisplayName("load: no existing conversation and agent not ready -> 503")
    void loadNewConversation() throws Exception {
        when(userConversationStore.readUserConversation(INTENT, USER)).thenReturn(null);
        agentNotReady(404);

        var e = assertThrows(WebApplicationException.class,
                () -> management.loadConversationMemory(INTENT, USER, null, false, true, List.of(), asyncResponse));

        assert503WithRetryAfter(e.getResponse());
        verify(asyncResponse, never()).resume(any(Object.class));
    }

    @Test
    @DisplayName("load: ended conversation to replace and agent not ready -> 503")
    void loadEndedConversation() throws Exception {
        var existing = new UserConversation(INTENT, USER, Deployment.Environment.production, AGENT_ID, "bbbbbbbbbbbbbbbbbbbbbbbb");
        when(userConversationStore.readUserConversation(INTENT, USER)).thenReturn(existing);
        when(restAgentEngine.getConversationState("bbbbbbbbbbbbbbbbbbbbbbbb")).thenReturn(ConversationState.ENDED);
        agentNotReady(404);

        var e = assertThrows(WebApplicationException.class,
                () -> management.loadConversationMemory(INTENT, USER, null, false, true, List.of(), asyncResponse));

        assert503WithRetryAfter(e.getResponse());
    }

    @Test
    @DisplayName("load: engine answers 503 (draining) -> 503 with Retry-After too")
    void loadEngineUnavailable() throws Exception {
        when(userConversationStore.readUserConversation(INTENT, USER)).thenReturn(null);
        agentNotReady(503);

        var e = assertThrows(WebApplicationException.class,
                () -> management.loadConversationMemory(INTENT, USER, null, false, true, List.of(), asyncResponse));

        assert503WithRetryAfter(e.getResponse());
    }

    @Test
    @DisplayName("say: no existing conversation and agent not ready -> 503, not an opaque 500")
    void sayNewConversation() throws Exception {
        when(userConversationStore.readUserConversation(INTENT, USER)).thenReturn(null);
        agentNotReady(404);
        var input = new InputData();
        input.setInput("hi");
        input.setContext(Map.of("lang", new Context(Context.ContextType.string, "en")));

        management.sayWithinContext(INTENT, USER, false, true, List.of(), input, asyncResponse);

        var captor = ArgumentCaptor.forClass(Response.class);
        verify(asyncResponse).resume(captor.capture());
        assert503WithRetryAfter(captor.getValue());
    }

    @Test
    @DisplayName("say: ended conversation to replace and agent not ready -> 503")
    void sayEndedConversation() throws Exception {
        var existing = new UserConversation(INTENT, USER, Deployment.Environment.production, AGENT_ID, "bbbbbbbbbbbbbbbbbbbbbbbb");
        when(userConversationStore.readUserConversation(INTENT, USER)).thenReturn(existing);
        when(restAgentEngine.getConversationState("bbbbbbbbbbbbbbbbbbbbbbbb")).thenReturn(ConversationState.ENDED);
        agentNotReady(404);
        var input = new InputData();
        input.setInput("hi");

        management.sayWithinContext(INTENT, USER, false, true, List.of(), input, asyncResponse);

        var captor = ArgumentCaptor.forClass(Response.class);
        verify(asyncResponse).resume(captor.capture());
        assert503WithRetryAfter(captor.getValue());
    }
}
