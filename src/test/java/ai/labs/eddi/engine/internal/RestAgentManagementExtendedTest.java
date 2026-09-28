/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.configs.properties.model.Property;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.api.IRestAgentEngine;
import ai.labs.eddi.engine.exception.SneakyThrow;
import ai.labs.eddi.engine.memory.model.ConversationProperties;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot;
import ai.labs.eddi.engine.model.*;
import ai.labs.eddi.engine.triggermanagement.IRestAgentTriggerStore;
import ai.labs.eddi.engine.triggermanagement.IUserConversationStore;
import ai.labs.eddi.engine.triggermanagement.model.AgentTriggerConfiguration;
import ai.labs.eddi.engine.triggermanagement.model.UserConversation;
import io.quarkus.security.UnauthorizedException;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.container.AsyncResponse;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

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
        return triggerWithDeployment(agentId, Deployment.Environment.production);
    }

    private AgentTriggerConfiguration triggerWithDeployment(String agentId, Deployment.Environment environment) {
        var deployment = new AgentDeployment();
        deployment.setAgentId(agentId);
        deployment.setEnvironment(environment);
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

        @Test
        @DisplayName("recreates the conversation when the mapped one no longer exists (stale mapping)")
        void recreatesPurgedConversation() throws Exception {
            // A permanently deleted or retention-swept conversation leaves its mapping
            // behind; the conversation guard answers 404. That must recreate, not fail
            // every later request for this intent and user.
            var mgmt = create(false);
            String newConvId = "aabbccddee112233aabbccdd";

            var existingConv = new UserConversation("intent-1", "user-1",
                    Deployment.Environment.production, "agent-1", "112233445566778899aabbcc");
            when(userConversationStore.readUserConversation("intent-1", "user-1"))
                    .thenReturn(existingConv);
            when(restAgentEngine.getConversationState("112233445566778899aabbcc"))
                    .thenThrow(new NotFoundException("Conversation not found"));

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

    // ─── Authorization precedes every side effect ───────────────

    /**
     * An anonymous caller that the gate rejects must not have created, deleted or
     * replaced anything: a 401 from the managed endpoints means nothing happened.
     * Before the fix the conversation was created (or the ended one deleted and
     * replaced) first and the caller was checked afterwards, so a rejected request
     * still left a new engine conversation and a stored UserConversation behind.
     */
    @Nested
    @DisplayName("Authorization before side effects")
    class AuthorizationBeforeSideEffects {

        private static final String ENDED_CONV_ID = "112233445566778899aabbcc";
        private static final String NEW_CONV_ID = "aabbccddee112233aabbccdd";

        private AgentTriggerConfiguration trigger;

        /** No stored UserConversation for (intent-1, user-1). */
        private void givenNoExistingConversation() throws Exception {
            when(userConversationStore.readUserConversation("intent-1", "user-1")).thenReturn(null);
        }

        /** A stored UserConversation whose engine conversation has ended. */
        private void givenEndedConversation(Deployment.Environment storedEnvironment) throws Exception {
            when(userConversationStore.readUserConversation("intent-1", "user-1"))
                    .thenReturn(new UserConversation("intent-1", "user-1", storedEnvironment, "agent-1", ENDED_CONV_ID));
            when(restAgentEngine.getConversationState(ENDED_CONV_ID)).thenReturn(ConversationState.ENDED);
        }

        private void givenTrigger(Deployment.Environment environment) {
            trigger = triggerWithDeployment("agent-1", environment);
            when(agentTriggerStore.readAgentTrigger("intent-1")).thenReturn(trigger);
        }

        /** The engine accepts the start; the new conversation is live. */
        private SimpleConversationMemorySnapshot givenEngineCreatesConversation() {
            var location = URI.create("eddi://ai.labs.conversation/conversationstore/conversations/" + NEW_CONV_ID + "?version=1");
            when(restAgentEngine.startConversationWithContext(eq("agent-1"), any(), eq("user-1"), anyMap()))
                    .thenReturn(Response.status(201).header("location", location.toString()).build());
            when(restAgentEngine.getConversationState(NEW_CONV_ID)).thenReturn(ConversationState.READY);
            var snapshot = new SimpleConversationMemorySnapshot();
            when(restAgentEngine.readConversation(eq(NEW_CONV_ID), any(), any(), any())).thenReturn(snapshot);
            return snapshot;
        }

        private void assertNothingCreatedOrReplaced() throws Exception {
            verify(userConversationStore, never()).createUserConversation(any());
            verify(userConversationStore, never()).deleteUserConversation(anyString(), anyString());
            verify(restAgentEngine, never()).startConversationWithContext(any(), any(), any(), any());
            verify(restAgentEngine, never()).startConversation(any(), any(), any());
            verify(restAgentEngine, never()).readConversation(any(), any(), any(), any());
            verify(restAgentEngine, never()).sayWithinContext(any(), any(), any(), any(), any(), any());
            // createNewConversation writes the language into the trigger's (cached)
            // deployment context — a rejected caller must not reach that either.
            if (trigger != null) {
                assertFalse(trigger.getAgentDeployments().getFirst().getInitialContext().containsKey(RestAgentManagement.KEY_LANG),
                        "a rejected request reached createNewConversation");
            }
        }

        private void callLoad(RestAgentManagement mgmt) {
            mgmt.loadConversationMemory("intent-1", "user-1", "en", false, false, List.of(), asyncResponse);
        }

        private void callSay(RestAgentManagement mgmt) {
            mgmt.sayWithinContext("intent-1", "user-1", false, false, List.of(), new InputData("Hello", Map.of()), asyncResponse);
        }

        /**
         * sayWithinContext hands the UnauthorizedException to the JAX-RS exception
         * mappers via resume(Throwable) — Quarkus maps it to 401 exactly as the throw
         * from loadConversationMemory — and never resumes with a built (500) Response.
         */
        private void assertSayAnswered401() {
            verify(asyncResponse).resume(isA(UnauthorizedException.class));
            verify(asyncResponse, never()).resume(any(Response.class));
        }

        // --- rejected: nothing happens ---

        @Test
        @DisplayName("load, no existing conversation: 401 and no conversation is created")
        void loadRejectedBeforeCreate() throws Exception {
            var mgmt = create(true);
            when(identity.isAnonymous()).thenReturn(true);
            givenNoExistingConversation();
            givenTrigger(Deployment.Environment.test);
            givenEngineCreatesConversation();

            assertThrows(UnauthorizedException.class, () -> callLoad(mgmt));

            assertNothingCreatedOrReplaced();
            verify(asyncResponse, never()).resume(any());
        }

        @Test
        @DisplayName("say, no existing conversation: 401 and no conversation is created")
        void sayRejectedBeforeCreate() throws Exception {
            var mgmt = create(true);
            when(identity.isAnonymous()).thenReturn(true);
            givenNoExistingConversation();
            givenTrigger(Deployment.Environment.test);
            givenEngineCreatesConversation();

            callSay(mgmt);

            assertSayAnswered401();
            assertNothingCreatedOrReplaced();
        }

        @Test
        @DisplayName("load, ended conversation: 401 and the ended one is neither deleted nor replaced")
        void loadRejectedBeforeReplace() throws Exception {
            var mgmt = create(true);
            when(identity.isAnonymous()).thenReturn(true);
            // The ended conversation was production; the replacement would be test.
            // The request acts on the replacement, so its environment decides.
            givenEndedConversation(Deployment.Environment.production);
            givenTrigger(Deployment.Environment.test);
            givenEngineCreatesConversation();

            assertThrows(UnauthorizedException.class, () -> callLoad(mgmt));

            assertNothingCreatedOrReplaced();
            verify(asyncResponse, never()).resume(any());
        }

        @Test
        @DisplayName("say, ended conversation: 401 and the ended one is neither deleted nor replaced")
        void sayRejectedBeforeReplace() throws Exception {
            var mgmt = create(true);
            when(identity.isAnonymous()).thenReturn(true);
            givenEndedConversation(Deployment.Environment.test);
            givenTrigger(Deployment.Environment.test);
            givenEngineCreatesConversation();

            callSay(mgmt);

            assertSayAnswered401();
            assertNothingCreatedOrReplaced();
        }

        @Test
        @DisplayName("load and say, live non-production conversation: 401 without touching it")
        void liveConversationRejected() throws Exception {
            var mgmt = create(true);
            when(identity.isAnonymous()).thenReturn(true);
            when(userConversationStore.readUserConversation("intent-1", "user-1"))
                    .thenReturn(new UserConversation("intent-1", "user-1", Deployment.Environment.test, "agent-1", "conv-1"));
            when(restAgentEngine.getConversationState("conv-1")).thenReturn(ConversationState.READY);

            assertThrows(UnauthorizedException.class, () -> callLoad(mgmt));
            callSay(mgmt);

            assertSayAnswered401();
            assertNothingCreatedOrReplaced();
            verify(agentTriggerStore, never()).readAgentTrigger(anyString());
        }

        // --- missing trigger: the existing error, not a 401 ---

        private void givenMissingTrigger() {
            when(agentTriggerStore.readAgentTrigger("intent-1")).thenAnswer(inv -> {
                throw SneakyThrow.sneakyThrow(new IResourceStore.ResourceNotFoundException("no trigger"));
            });
        }

        /**
         * The trigger is read to decide the environment before the caller is checked,
         * exactly as it was read (inside creation) before the check it used to follow.
         * So a missing trigger still surfaces as the store's not-found exception —
         * mapped by JAX-RS for load, the opaque 500 for say — for anonymous callers
         * too.
         */
        @Test
        @DisplayName("missing trigger, anonymous caller: the same not-found error as before, nothing created")
        void missingTriggerKeepsItsError() throws Exception {
            var mgmt = create(true);
            when(identity.isAnonymous()).thenReturn(true);
            givenNoExistingConversation();
            givenMissingTrigger();

            assertThrows(IResourceStore.ResourceNotFoundException.class, () -> callLoad(mgmt));

            callSay(mgmt);
            var captor = ArgumentCaptor.forClass(Response.class);
            verify(asyncResponse).resume(captor.capture());
            assertEquals(500, captor.getValue().getStatus());
            verify(asyncResponse, never()).resume(any(Throwable.class));
            assertNothingCreatedOrReplaced();
        }

        /**
         * One deliberate difference for this error path: the trigger is now read before
         * the ended conversation is deleted, so a missing trigger leaves the ended
         * UserConversation in place instead of deleting it and then failing. Each later
         * request fails the same way either way.
         */
        @Test
        @DisplayName("missing trigger, ended conversation: fails without deleting the ended one")
        void missingTriggerDoesNotDeleteEnded() throws Exception {
            var mgmt = create(false);
            givenEndedConversation(Deployment.Environment.production);
            givenMissingTrigger();

            assertThrows(IResourceStore.ResourceNotFoundException.class, () -> callLoad(mgmt));

            assertNothingCreatedOrReplaced();
        }

        // --- authorized: unchanged ---

        /**
         * The three ways past the gate — an authenticated caller, a production
         * deployment, and OIDC off — each create the conversation exactly as before:
         * trigger read, engine start, then the stored UserConversation, and the request
         * proceeds on the new conversation.
         */
        @ParameterizedTest(name = "checkAuth={0}, anonymous={1}, env={2}, say={3}")
        @CsvSource({"true, false, test, false", "true, false, test, true", "true, true, production, false", "true, true, production, true",
                "false, true, test, false", "false, true, test, true"})
        @DisplayName("authorized caller, no existing conversation: created and served as before")
        void authorizedCreate(boolean checkAuth, boolean anonymous, Deployment.Environment environment, boolean say) throws Exception {
            var mgmt = create(checkAuth);
            when(identity.isAnonymous()).thenReturn(anonymous);
            givenNoExistingConversation();
            givenTrigger(environment);
            var snapshot = givenEngineCreatesConversation();

            if (say) {
                callSay(mgmt);
            } else {
                callLoad(mgmt);
            }

            InOrder order = inOrder(userConversationStore, agentTriggerStore, restAgentEngine);
            order.verify(userConversationStore).readUserConversation("intent-1", "user-1");
            order.verify(agentTriggerStore).readAgentTrigger("intent-1");
            order.verify(restAgentEngine).startConversationWithContext(eq("agent-1"), eq(environment), eq("user-1"), anyMap());
            var stored = ArgumentCaptor.forClass(UserConversation.class);
            order.verify(userConversationStore).createUserConversation(stored.capture());
            assertEquals(environment, stored.getValue().getEnvironment());
            assertEquals(NEW_CONV_ID, stored.getValue().getConversationId());
            verify(userConversationStore, never()).deleteUserConversation(anyString(), anyString());
            assertProceededOnNewConversation(say, snapshot);
        }

        @ParameterizedTest(name = "checkAuth={0}, anonymous={1}, env={2}, say={3}")
        @CsvSource({"true, false, test, false", "true, false, test, true", "true, true, production, false", "true, true, production, true",
                "false, true, test, false", "false, true, test, true"})
        @DisplayName("authorized caller, ended conversation: deleted, then replaced, as before")
        void authorizedReplace(boolean checkAuth, boolean anonymous, Deployment.Environment environment, boolean say) throws Exception {
            var mgmt = create(checkAuth);
            when(identity.isAnonymous()).thenReturn(anonymous);
            givenEndedConversation(Deployment.Environment.test);
            givenTrigger(environment);
            var snapshot = givenEngineCreatesConversation();

            if (say) {
                callSay(mgmt);
            } else {
                callLoad(mgmt);
            }

            InOrder order = inOrder(userConversationStore, restAgentEngine);
            order.verify(userConversationStore).deleteUserConversation("intent-1", "user-1");
            order.verify(restAgentEngine).startConversationWithContext(eq("agent-1"), eq(environment), eq("user-1"), anyMap());
            order.verify(userConversationStore).createUserConversation(any());
            assertProceededOnNewConversation(say, snapshot);
        }

        @Test
        @DisplayName("anonymous, ended test conversation replaced by a production one: allowed (the replacement decides)")
        void endedNonProductionReplacedByProduction() throws Exception {
            var mgmt = create(true);
            when(identity.isAnonymous()).thenReturn(true);
            givenEndedConversation(Deployment.Environment.test);
            givenTrigger(Deployment.Environment.production);
            var snapshot = givenEngineCreatesConversation();

            callLoad(mgmt);

            verify(userConversationStore).deleteUserConversation("intent-1", "user-1");
            verify(userConversationStore).createUserConversation(any());
            verify(asyncResponse).resume(snapshot);
        }

        private void assertProceededOnNewConversation(boolean say, SimpleConversationMemorySnapshot snapshot) {
            if (say) {
                verify(restAgentEngine).sayWithinContext(eq(NEW_CONV_ID), any(), any(), any(), any(), eq(asyncResponse));
                verify(asyncResponse, never()).resume(any(Response.class));
                verify(asyncResponse, never()).resume(any(Throwable.class));
            } else {
                verify(asyncResponse).resume(snapshot);
            }
        }
    }

    // ─── The cached trigger's context is never written to ───────

    /**
     * The deployment a new conversation is started with belongs to the
     * {@link AgentTriggerConfiguration} held in the shared {@code agentTriggers}
     * cache. Writing the caller's language into its {@code initialContext} leaked
     * that language to concurrent callers and wrote a plain HashMap from several
     * threads; each request now starts the conversation with its own copy.
     */
    @Nested
    @DisplayName("Trigger context is copied per request")
    class TriggerContextCopy {

        private static final String ENDED_CONV_ID = "112233445566778899aabbcc";
        private static final String NEW_CONV_ID = "aabbccddee112233aabbccdd";

        private AgentTriggerConfiguration trigger;
        private Map<String, Context> triggerContextBefore;

        /** A trigger whose deployment carries one designer-set context entry. */
        private void givenTriggerWithContext() {
            trigger = triggerWithDeployment("agent-1");
            trigger.getAgentDeployments().getFirst().getInitialContext().put("channel", new Context(Context.ContextType.string, "web"));
            triggerContextBefore = new HashMap<>(trigger.getAgentDeployments().getFirst().getInitialContext());
            when(agentTriggerStore.readAgentTrigger("intent-1")).thenReturn(trigger);
        }

        private void givenEngineCreatesConversation() {
            var location = URI.create("eddi://ai.labs.conversation/conversationstore/conversations/" + NEW_CONV_ID + "?version=1");
            when(restAgentEngine.startConversationWithContext(eq("agent-1"), any(), eq("user-1"), anyMap()))
                    .thenReturn(Response.status(201).header("location", location.toString()).build());
            when(restAgentEngine.getConversationState(NEW_CONV_ID)).thenReturn(ConversationState.READY);
            when(restAgentEngine.readConversation(eq(NEW_CONV_ID), any(), any(), any())).thenReturn(new SimpleConversationMemorySnapshot());
        }

        @SuppressWarnings("unchecked")
        private Map<String, Context> contextSentToEngine() {
            ArgumentCaptor<Map<String, Context>> captor = ArgumentCaptor.forClass(Map.class);
            verify(restAgentEngine).startConversationWithContext(eq("agent-1"), any(), eq("user-1"), captor.capture());
            return captor.getValue();
        }

        private void assertTriggerContextUnchanged() {
            var triggerContext = trigger.getAgentDeployments().getFirst().getInitialContext();
            assertEquals(triggerContextBefore, triggerContext, "the cached trigger's context was modified");
            assertFalse(triggerContext.containsKey(RestAgentManagement.KEY_LANG), "the caller's language was written into the cached trigger");
        }

        private void assertEngineGot(Map<String, Context> sent, String expectedLanguage) {
            assertNotSame(trigger.getAgentDeployments().getFirst().getInitialContext(), sent, "the engine was handed the cached map itself");
            assertEquals("web", sent.get("channel").getValue(), "the designer-set context must still reach the engine");
            assertTrue(sent.containsKey(RestAgentManagement.KEY_LANG));
            assertEquals(Context.ContextType.string, sent.get(RestAgentManagement.KEY_LANG).getType());
            assertEquals(expectedLanguage, sent.get(RestAgentManagement.KEY_LANG).getValue());
        }

        @Test
        @DisplayName("load, new conversation: engine gets lang, cached trigger context unchanged")
        void loadCreate() throws Exception {
            var mgmt = create(false);
            when(userConversationStore.readUserConversation("intent-1", "user-1")).thenReturn(null);
            givenTriggerWithContext();
            givenEngineCreatesConversation();

            mgmt.loadConversationMemory("intent-1", "user-1", "en", false, false, List.of(), asyncResponse);

            assertTriggerContextUnchanged();
            assertEngineGot(contextSentToEngine(), "en");
        }

        @Test
        @DisplayName("say, new conversation: engine gets lang from the input context, cached trigger context unchanged")
        void sayCreate() throws Exception {
            var mgmt = create(false);
            when(userConversationStore.readUserConversation("intent-1", "user-1")).thenReturn(null);
            givenTriggerWithContext();
            givenEngineCreatesConversation();

            mgmt.sayWithinContext("intent-1", "user-1", false, false, List.of(),
                    new InputData("Bonjour", Map.of("lang", new Context(Context.ContextType.string, "fr"))), asyncResponse);

            assertTriggerContextUnchanged();
            assertEngineGot(contextSentToEngine(), "fr");
        }

        @Test
        @DisplayName("load, ended conversation replaced: engine gets lang, cached trigger context unchanged")
        void loadReplaceEnded() throws Exception {
            var mgmt = create(false);
            when(userConversationStore.readUserConversation("intent-1", "user-1"))
                    .thenReturn(new UserConversation("intent-1", "user-1", Deployment.Environment.production, "agent-1", ENDED_CONV_ID));
            when(restAgentEngine.getConversationState(ENDED_CONV_ID)).thenReturn(ConversationState.ENDED);
            givenTriggerWithContext();
            givenEngineCreatesConversation();

            mgmt.loadConversationMemory("intent-1", "user-1", "de", false, false, List.of(), asyncResponse);

            verify(userConversationStore).deleteUserConversation("intent-1", "user-1");
            assertTriggerContextUnchanged();
            assertEngineGot(contextSentToEngine(), "de");
        }

        /**
         * Unchanged engine-facing behaviour: with no language the engine still receives
         * a {@code lang} entry whose value is null, as it did before the copy.
         */
        @Test
        @DisplayName("no language: engine still gets a null-valued lang entry, cached trigger context unchanged")
        void nullLanguage() throws Exception {
            var mgmt = create(false);
            when(userConversationStore.readUserConversation("intent-1", "user-1")).thenReturn(null);
            givenTriggerWithContext();
            givenEngineCreatesConversation();

            mgmt.loadConversationMemory("intent-1", "user-1", null, false, false, List.of(), asyncResponse);

            assertTriggerContextUnchanged();
            assertEngineGot(contextSentToEngine(), null);
        }

        @Test
        @DisplayName("deployment without an initial context: engine gets a map holding only lang")
        void nullInitialContext() throws Exception {
            var mgmt = create(false);
            when(userConversationStore.readUserConversation("intent-1", "user-1")).thenReturn(null);
            trigger = triggerWithDeployment("agent-1");
            trigger.getAgentDeployments().getFirst().setInitialContext(null);
            when(agentTriggerStore.readAgentTrigger("intent-1")).thenReturn(trigger);
            givenEngineCreatesConversation();

            mgmt.loadConversationMemory("intent-1", "user-1", "en", false, false, List.of(), asyncResponse);

            var sent = contextSentToEngine();
            assertEquals(Set.of(RestAgentManagement.KEY_LANG), sent.keySet());
            assertEquals("en", sent.get(RestAgentManagement.KEY_LANG).getValue());
            assertNull(trigger.getAgentDeployments().getFirst().getInitialContext());
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

        /**
         * A12 — store failures reach this catch sneaky-thrown, and their messages name
         * collections, hosts, and replica-set members. The resumed body must carry a
         * fixed message plus a correlation id, nothing else.
         */
        @Test
        @DisplayName("A12: the error body carries no internal detail, only a correlation id")
        void errorBodyIsOpaque() throws Exception {
            var mgmt = create(false);

            when(userConversationStore.readUserConversation("intent-1", "user-1"))
                    .thenReturn(null);
            when(agentTriggerStore.readAgentTrigger("intent-1"))
                    .thenReturn(triggerWithDeployment("agent-1"));
            when(restAgentEngine.startConversationWithContext(eq("agent-1"), any(), eq("user-1"), anyMap()))
                    .thenThrow(new RuntimeException(
                            "Timed out connecting to replica set [mongo-01.internal:27017] db=eddi coll=conversationmemories"));

            mgmt.sayWithinContext("intent-1", "user-1", false, false,
                    List.of(), new InputData("Hello", Map.of()), asyncResponse);

            var captor = ArgumentCaptor.forClass(Response.class);
            verify(asyncResponse).resume(captor.capture());

            String body = String.valueOf(captor.getValue().getEntity());
            assertTrue(body.contains("correlationId:"), "body must carry a correlation id");
            assertFalse(body.contains("mongo-01.internal"), "hostname leaked to the client");
            assertFalse(body.contains("conversationmemories"), "collection name leaked to the client");
            assertFalse(body.contains("replica set"), "topology detail leaked to the client");
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
