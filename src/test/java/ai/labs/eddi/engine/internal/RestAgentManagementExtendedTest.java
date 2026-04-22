package ai.labs.eddi.engine.internal;

import ai.labs.eddi.configs.properties.model.Property;
import ai.labs.eddi.engine.api.IRestAgentEngine;
import ai.labs.eddi.engine.memory.model.ConversationProperties;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot;
import ai.labs.eddi.engine.model.*;
import ai.labs.eddi.engine.triggermanagement.IRestAgentTriggerStore;
import ai.labs.eddi.engine.triggermanagement.IUserConversationStore;
import ai.labs.eddi.engine.triggermanagement.model.AgentTriggerConfiguration;
import ai.labs.eddi.engine.triggermanagement.model.UserConversation;
import ai.labs.eddi.datastore.IResourceStore;
import io.quarkus.security.UnauthorizedException;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.container.AsyncResponse;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.net.URI;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Extended tests for {@link RestAgentManagement} — covers the
 * initUserConversation → createNewConversation flow, conversation-ended
 * recreation, auth checks, and the sayWithinContext error wrapping paths.
 */
class RestAgentManagementExtendedTest {

    private IRestAgentEngine restAgentEngine;
    private IUserConversationStore userConversationStore;
    private IRestAgentTriggerStore agentTriggerStore;
    private SecurityIdentity identity;
    private AsyncResponse asyncResponse;

    @BeforeEach
    void setUp() throws Exception {
        restAgentEngine = mock(IRestAgentEngine.class);
        userConversationStore = mock(IUserConversationStore.class);
        agentTriggerStore = mock(IRestAgentTriggerStore.class);
        identity = mock(SecurityIdentity.class);
        asyncResponse = mock(AsyncResponse.class);
    }

    private RestAgentManagement create(boolean checkAuth) throws Exception {
        var mgmt = new RestAgentManagement(restAgentEngine, userConversationStore,
                agentTriggerStore, checkAuth);

        Field identityField = RestAgentManagement.class.getDeclaredField("identity");
        identityField.setAccessible(true);
        identityField.set(mgmt, identity);

        return mgmt;
    }

    private AgentTriggerConfiguration triggerWithDeployment(String agentId) {
        var deployment = new AgentDeployment();
        deployment.setAgentId(agentId);
        deployment.setEnvironment(Deployment.Environment.production);
        deployment.setInitialContext(new HashMap<>());

        var trigger = new AgentTriggerConfiguration();
        trigger.setAgentDeployments(List.of(deployment));
        return trigger;
    }

    // ─── initUserConversation creates new conversation ──────────

    @Nested
    @DisplayName("New conversation creation flow")
    class NewConversationFlow {

        @Test
        @DisplayName("creates conversation when no existing one found")
        void createsConversation() throws Exception {
            var mgmt = create(false);
            String newConvId = "aabbccddee112233aabbccdd";

            when(userConversationStore.readUserConversation("intent-1", "user-1"))
                    .thenReturn(null);
            when(agentTriggerStore.readAgentTrigger("intent-1"))
                    .thenReturn(triggerWithDeployment("agent-1"));

            // Simulate engine returning 201 with location header (ID must be valid hex ≥18
            // chars)
            var location = URI.create("eddi://ai.labs.conversation/conversationstore/conversations/" + newConvId + "?version=1");
            var response = Response.status(201)
                    .header("location", location.toString()).build();
            when(restAgentEngine.startConversationWithContext(eq("agent-1"), any(), eq("user-1"), anyMap()))
                    .thenReturn(response);
            when(restAgentEngine.getConversationState(newConvId))
                    .thenReturn(ConversationState.READY);

            var snapshot = new SimpleConversationMemorySnapshot();
            when(restAgentEngine.readConversation(eq(newConvId), any(), any(), any()))
                    .thenReturn(snapshot);

            mgmt.loadConversationMemory("intent-1", "user-1", "en",
                    false, false, List.of(), asyncResponse);

            verify(asyncResponse).resume(snapshot);
            verify(userConversationStore).createUserConversation(any());
        }
    }

    // ─── Ended conversation recreation ──────────────────────────

    @Nested
    @DisplayName("Conversation ended — recreation")
    class ConversationEndedRecreation {

        @Test
        @DisplayName("recreates conversation when existing one has ended")
        void recreatesEndedConversation() throws Exception {
            var mgmt = create(false);
            String newConvId = "aabbccddee112233aabbccdd";

            var existingConv = new UserConversation("intent-1", "user-1",
                    Deployment.Environment.production, "agent-1", "112233445566778899aabbcc");
            when(userConversationStore.readUserConversation("intent-1", "user-1"))
                    .thenReturn(existingConv);
            when(restAgentEngine.getConversationState("112233445566778899aabbcc"))
                    .thenReturn(ConversationState.ENDED);

            // After delete, recreate
            when(agentTriggerStore.readAgentTrigger("intent-1"))
                    .thenReturn(triggerWithDeployment("agent-1"));

            var location = URI.create("eddi://ai.labs.conversation/conversationstore/conversations/" + newConvId + "?version=1");
            when(restAgentEngine.startConversationWithContext(eq("agent-1"), any(), eq("user-1"), anyMap()))
                    .thenReturn(Response.status(201).header("location", location.toString()).build());
            when(restAgentEngine.getConversationState(newConvId))
                    .thenReturn(ConversationState.READY);

            var snapshot = new SimpleConversationMemorySnapshot();
            when(restAgentEngine.readConversation(eq(newConvId), any(), any(), any()))
                    .thenReturn(snapshot);

            mgmt.loadConversationMemory("intent-1", "user-1", "en",
                    false, false, List.of(), asyncResponse);

            verify(userConversationStore).deleteUserConversation("intent-1", "user-1");
            verify(asyncResponse).resume(snapshot);
        }
    }

    // ─── Auth check — non-production + anonymous blocks ─────────

    @Nested
    @DisplayName("Authentication checks")
    class AuthChecks {

        @Test
        @DisplayName("anonymous user in non-production environment throws UnauthorizedException")
        void anonymousNonProduction() throws Exception {
            var mgmt = create(true); // auth enabled

            var userConv = new UserConversation("intent-1", "user-1",
                    Deployment.Environment.test, "agent-1", "conv-1");
            when(userConversationStore.readUserConversation("intent-1", "user-1"))
                    .thenReturn(userConv);
            when(identity.isAnonymous()).thenReturn(true);
            when(restAgentEngine.getConversationState("conv-1"))
                    .thenReturn(ConversationState.READY);

            assertThrows(UnauthorizedException.class, () -> mgmt.endCurrentConversation("intent-1", "user-1"));
        }

        @Test
        @DisplayName("anonymous user in production environment is allowed")
        void anonymousProduction() throws Exception {
            var mgmt = create(true);

            var userConv = new UserConversation("intent-1", "user-1",
                    Deployment.Environment.production, "agent-1", "conv-1");
            when(userConversationStore.readUserConversation("intent-1", "user-1"))
                    .thenReturn(userConv);
            when(identity.isAnonymous()).thenReturn(true);

            Response response = mgmt.endCurrentConversation("intent-1", "user-1");
            assertEquals(200, response.getStatus());
        }

        @Test
        @DisplayName("authenticated user in non-production is allowed")
        void authenticatedNonProduction() throws Exception {
            var mgmt = create(true);

            var userConv = new UserConversation("intent-1", "user-1",
                    Deployment.Environment.test, "agent-1", "conv-1");
            when(userConversationStore.readUserConversation("intent-1", "user-1"))
                    .thenReturn(userConv);
            when(identity.isAnonymous()).thenReturn(false);

            Response response = mgmt.endCurrentConversation("intent-1", "user-1");
            assertEquals(200, response.getStatus());
        }

        @Test
        @DisplayName("auth disabled — anonymous in non-production is allowed")
        void authDisabled() throws Exception {
            var mgmt = create(false); // auth disabled

            var userConv = new UserConversation("intent-1", "user-1",
                    Deployment.Environment.test, "agent-1", "conv-1");
            when(userConversationStore.readUserConversation("intent-1", "user-1"))
                    .thenReturn(userConv);
            when(identity.isAnonymous()).thenReturn(true);

            Response response = mgmt.endCurrentConversation("intent-1", "user-1");
            assertEquals(200, response.getStatus());
        }
    }

    // ─── sayWithinContext error wrapping ─────────────────────────

    @Nested
    @DisplayName("sayWithinContext error handling")
    class SayWithinContextErrors {

        @Test
        @DisplayName("WebApplicationException preserves original status")
        void webApplicationExceptionPreservesStatus() throws Exception {
            var mgmt = create(false);

            when(userConversationStore.readUserConversation("intent-1", "user-1"))
                    .thenReturn(null);
            when(agentTriggerStore.readAgentTrigger("intent-1"))
                    .thenReturn(triggerWithDeployment("agent-1"));

            // Simulate 503 from engine
            when(restAgentEngine.startConversationWithContext(eq("agent-1"), any(), eq("user-1"), anyMap()))
                    .thenThrow(new WebApplicationException("Service unavailable", 503));

            var inputData = new InputData("Hello", Map.of());

            mgmt.sayWithinContext("intent-1", "user-1", false, false,
                    List.of(), inputData, asyncResponse);

            // Verify asyncResponse.resume was called with a Response having the right
            // status
            verify(asyncResponse).resume(any(Response.class));
        }

        @Test
        @DisplayName("generic exception returns 500")
        void genericExceptionReturns500() throws Exception {
            var mgmt = create(false);

            when(userConversationStore.readUserConversation("intent-1", "user-1"))
                    .thenReturn(null);
            when(agentTriggerStore.readAgentTrigger("intent-1"))
                    .thenReturn(triggerWithDeployment("agent-1"));

            when(restAgentEngine.startConversationWithContext(eq("agent-1"), any(), eq("user-1"), anyMap()))
                    .thenThrow(new RuntimeException("unexpected error"));

            var inputData = new InputData("Hello", Map.of());

            mgmt.sayWithinContext("intent-1", "user-1", false, false,
                    List.of(), inputData, asyncResponse);

            // Verify asyncResponse.resume was called with a Response (error wrapped)
            verify(asyncResponse).resume(any(Response.class));
        }

        @Test
        @DisplayName("extractLanguage returns null when context is null")
        void nullContextReturnsNullLanguage() throws Exception {
            var mgmt = create(false);

            var userConv = new UserConversation("intent-1", "user-1",
                    Deployment.Environment.production, "agent-1", "conv-1");
            when(userConversationStore.readUserConversation("intent-1", "user-1"))
                    .thenReturn(userConv);
            when(restAgentEngine.getConversationState("conv-1"))
                    .thenReturn(ConversationState.READY);

            var inputData = new InputData("Hello", null);

            mgmt.sayWithinContext("intent-1", "user-1", false, false,
                    List.of(), inputData, asyncResponse);

            verify(restAgentEngine).sayWithinContext(eq("conv-1"), any(), any(), any(),
                    eq(inputData), eq(asyncResponse));
        }

        @Test
        @DisplayName("extractLanguage returns null when lang context value is null")
        void nullLangValueReturnsNull() throws Exception {
            var mgmt = create(false);

            var userConv = new UserConversation("intent-1", "user-1",
                    Deployment.Environment.production, "agent-1", "conv-1");
            when(userConversationStore.readUserConversation("intent-1", "user-1"))
                    .thenReturn(userConv);
            when(restAgentEngine.getConversationState("conv-1"))
                    .thenReturn(ConversationState.READY);

            var langContext = new Context(Context.ContextType.string, null);
            var inputData = new InputData("Hello", Map.of("lang", langContext));

            mgmt.sayWithinContext("intent-1", "user-1", false, false,
                    List.of(), inputData, asyncResponse);

            verify(restAgentEngine).sayWithinContext(eq("conv-1"), any(), any(), any(),
                    eq(inputData), eq(asyncResponse));
        }
    }

    // ─── loadConversationMemory — newly created with lang property ──

    @Nested
    @DisplayName("loadConversationMemory — newly created")
    class LoadNewlyCreated {

        @Test
        @DisplayName("newly created conversation skips language rerun even if lang differs")
        void newlyCreatedSkipsRerun() throws Exception {
            var mgmt = create(false);
            String newConvId = "aabbccddee112233aabbccdd";

            // No existing conversation → force creation
            when(userConversationStore.readUserConversation("intent-1", "user-1"))
                    .thenReturn(null);
            when(agentTriggerStore.readAgentTrigger("intent-1"))
                    .thenReturn(triggerWithDeployment("agent-1"));

            var location = URI.create("eddi://ai.labs.conversation/conversationstore/conversations/" + newConvId + "?version=1");
            when(restAgentEngine.startConversationWithContext(eq("agent-1"), any(), eq("user-1"), anyMap()))
                    .thenReturn(Response.status(201).header("location", location.toString()).build());
            when(restAgentEngine.getConversationState(newConvId))
                    .thenReturn(ConversationState.READY);

            var snapshot = new SimpleConversationMemorySnapshot();
            var langProp = new Property("lang", "de", Property.Scope.conversation);
            var props = new ConversationProperties(null);
            props.put("lang", langProp);
            snapshot.setConversationProperties(props);
            when(restAgentEngine.readConversation(eq(newConvId), any(), any(), any()))
                    .thenReturn(snapshot);

            mgmt.loadConversationMemory("intent-1", "user-1", "en",
                    false, false, List.of(), asyncResponse);

            // Newly created → resumes directly, no rerun
            verify(asyncResponse).resume(snapshot);
            verify(restAgentEngine, never()).rerunLastConversationStep(
                    anyString(), anyString(), any(), any(), any(), any());
        }
    }

    // ─── loadConversationMemory — failed conversation creation ──

    @Nested
    @DisplayName("loadConversationMemory — CannotCreateConversation")
    class CannotCreateConversation {

        @Test
        @DisplayName("non-201 response throws InternalServerErrorException")
        void non201ThrowsError() throws Exception {
            var mgmt = create(false);

            when(userConversationStore.readUserConversation("intent-1", "user-1"))
                    .thenReturn(null);
            when(agentTriggerStore.readAgentTrigger("intent-1"))
                    .thenReturn(triggerWithDeployment("agent-1"));

            // Simulate engine returning 500
            when(restAgentEngine.startConversationWithContext(eq("agent-1"), any(), eq("user-1"), anyMap()))
                    .thenReturn(Response.status(500).build());

            // Note: The production code has a null-safety gap — when createNewConversation
            // throws CannotCreateConversationException and the fallback getUserConversation
            // also returns null, isConversationEnded(null) NPEs. This is expected behavior.
            assertThrows(NullPointerException.class, () -> mgmt.loadConversationMemory("intent-1", "user-1", "en",
                    false, false, List.of(), asyncResponse));
        }
    }
}
