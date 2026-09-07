/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.a2a;

import ai.labs.eddi.engine.a2a.A2AModels.*;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.api.IConversationService.ConversationResponseHandler;
import ai.labs.eddi.engine.api.IConversationService.ConversationResult;
import ai.labs.eddi.engine.caching.ICache;
import ai.labs.eddi.engine.caching.ICacheFactory;
import ai.labs.eddi.engine.memory.model.ConversationOutput;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot;
import ai.labs.eddi.engine.model.Deployment.Environment;
import io.quarkus.security.identity.SecurityIdentity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.security.Principal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static ai.labs.eddi.engine.a2a.A2ATaskHandler.scopedKey;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link A2ATaskHandler} covering task send/get/cancel,
 * conversation resolution, message extraction, and — since taskId/contextId are
 * caller-supplied — isolation between A2A peers.
 */
class A2ATaskHandlerTest {

    private static final String PEER_A = "peer-a";
    private static final String PEER_B = "peer-b";

    private IConversationService conversationService;
    private ICacheFactory cacheFactory;
    private AgentCardService agentCardService;
    private A2ATaskHandler handler;
    private MapCache<String, String> taskCache;
    private MapCache<String, String> contextCache;

    @BeforeEach
    void setUp() {
        conversationService = mock(IConversationService.class);
        taskCache = new MapCache<>();
        contextCache = new MapCache<>();

        cacheFactory = mock(ICacheFactory.class);
        when(cacheFactory.<String, String>getCache("a2aTaskMapping")).thenReturn(taskCache);
        when(cacheFactory.<String, String>getCache("a2aTaskMapping:context")).thenReturn(contextCache);

        // A2A-enabled by default here; the refusal path has its own test.
        agentCardService = mock(AgentCardService.class);
        when(agentCardService.getAgentCard(anyString())).thenReturn(mock(A2AModels.AgentCard.class));

        handler = handlerFor(PEER_A);
    }

    /**
     * A handler seeing the world as the given authenticated peer. All handlers
     * share the same cache instances — exactly like the singleton bean sharing one
     * global cache across peers in production.
     */
    private A2ATaskHandler handlerFor(String principalName) {
        SecurityIdentity identity = mock(SecurityIdentity.class);
        when(identity.isAnonymous()).thenReturn(false);
        Principal principal = () -> principalName;
        when(identity.getPrincipal()).thenReturn(principal);
        return new A2ATaskHandler(conversationService, cacheFactory, identity, agentCardService);
    }

    private A2ATaskHandler anonymousHandler() {
        SecurityIdentity identity = mock(SecurityIdentity.class);
        when(identity.isAnonymous()).thenReturn(true);
        return new A2ATaskHandler(conversationService, cacheFactory, identity, agentCardService);
    }

    private static Map<String, Object> sendParams(String taskId, String contextId, String text) {
        Map<String, Object> params = new HashMap<>();
        if (taskId != null) {
            params.put("id", taskId);
        }
        if (contextId != null) {
            params.put("contextId", contextId);
        }
        params.put("message", Map.of("parts", List.of(Map.of("type", "text", "text", text))));
        return params;
    }

    /** Answers every {@code say} call with a one-output snapshot. */
    private void stubSay() throws Exception {
        doAnswer(invocation -> {
            ConversationResponseHandler responseHandler = invocation.getArgument(8);
            var snapshot = new SimpleConversationMemorySnapshot();
            var output = new ConversationOutput();
            output.put("output", "Response");
            snapshot.setConversationOutputs(List.of(output));
            responseHandler.onComplete(snapshot);
            return null;
        }).when(conversationService).say(any(), anyString(), anyString(), any(), any(), any(), any(), anyBoolean(), any());
    }

    private static ConversationResult conversation(String conversationId) {
        return new ConversationResult(conversationId, URI.create("/conversations/" + conversationId));
    }

    // ─── handleTaskSend ──────────────────────────────────────────

    @Nested
    @DisplayName("handleTaskSend")
    class HandleTaskSend {

        @Test
        @DisplayName("should create conversation, send input, and return completed A2ATask")
        void happyPath() throws Exception {
            String agentId = "agent-123";
            when(conversationService.startConversation(eq(Environment.production), eq(agentId), anyString(), anyMap()))
                    .thenReturn(conversation("conv-abc"));

            doAnswer(invocation -> {
                ConversationResponseHandler responseHandler = invocation.getArgument(8);
                var snapshot = new SimpleConversationMemorySnapshot();
                var output = new ConversationOutput();
                output.put("output", "Hello from EDDI!");
                snapshot.setConversationOutputs(List.of(output));
                responseHandler.onComplete(snapshot);
                return null;
            }).when(conversationService).say(eq(Environment.production), eq(agentId), eq("conv-abc"),
                    eq(false), eq(true), isNull(), any(), eq(false), any());

            A2ATask result = handler.handleTaskSend(agentId, sendParams("task-1", null, "Hi!"));

            assertNotNull(result);
            assertEquals("task-1", result.id());
            assertEquals(TaskState.completed, result.status());
            assertNotNull(result.history());
            assertEquals(2, result.history().size());
            assertEquals("user", result.history().get(0).role());
            assertEquals("agent", result.history().get(1).role());
            assertNotNull(result.artifacts());
            assertFalse(result.artifacts().isEmpty());

            // Cached under the calling peer, not under the bare taskId
            assertEquals("conv-abc", taskCache.get(scopedKey(PEER_A, "task-1")));
            assertNull(taskCache.get("task-1"));
        }

        @Test
        @DisplayName("refuses an agent that was never opted into A2A, and starts no conversation")
        void refusesAgentNotExposedOverA2A() throws Exception {
            // Discovery already enforced this — listA2AAgents and getAgentCard both hide
            // an agent with a2aEnabled=false. Conversing did not, so a peer that knew an
            // id could talk to an agent nobody had exposed, private ones included.
            // getAgentCard returns null for both "no such agent" and "not enabled", which
            // is the same answer discovery gives.
            when(agentCardService.getAgentCard("not-exposed")).thenReturn(null);

            assertThrows(InvalidA2ARequestException.class,
                    () -> handler.handleTaskSend("not-exposed", sendParams("task-1", null, "Hi!")));

            verify(conversationService, never())
                    .startConversation(any(), anyString(), anyString(), anyMap());
        }

        @Test
        @DisplayName("should throw when message is missing from params")
        void missingMessage() {
            Map<String, Object> params = new HashMap<>();
            params.put("id", "task-2");

            assertThrows(InvalidA2ARequestException.class,
                    () -> handler.handleTaskSend("agent-1", params));
        }

        @Test
        @DisplayName("should throw when message has no text content")
        void blankTextContent() {
            Map<String, Object> params = new HashMap<>();
            params.put("message", Map.of("parts", List.of(Map.of("type", "text", "text", ""))));

            assertThrows(InvalidA2ARequestException.class,
                    () -> handler.handleTaskSend("agent-1", params));
        }

        @Test
        @DisplayName("should throw when message parts list is empty")
        void emptyPartsThrows() {
            Map<String, Object> params = new HashMap<>();
            params.put("message", Map.of("parts", List.of()));

            assertThrows(InvalidA2ARequestException.class,
                    () -> handler.handleTaskSend("agent-1", params));
        }

        @Test
        @DisplayName("should reuse conversation for same contextId")
        void reuseConversationWithContextId() throws Exception {
            // Pre-populate the context cache for THIS peer
            contextCache.put(scopedKey(PEER_A, "ctx-shared"), "existing-conv-id");
            stubSay();

            handler.handleTaskSend("agent-1", sendParams("task-reuse", "ctx-shared", "Hello"));

            // Should not start a new conversation — should reuse existing
            verify(conversationService, never()).startConversation(any(), anyString(), any(), anyMap());
            assertEquals("existing-conv-id", taskCache.get(scopedKey(PEER_A, "task-reuse")));
        }

        @Test
        @DisplayName("should generate taskId when not provided in params")
        void generatedTaskId() throws Exception {
            when(conversationService.startConversation(any(), anyString(), anyString(), anyMap()))
                    .thenReturn(conversation("conv-gen"));
            stubSay();

            // No "id" key — should auto-generate
            A2ATask result = handler.handleTaskSend("agent-1", sendParams(null, null, "Test"));

            assertNotNull(result.id());
            assertFalse(result.id().isBlank());
        }
    }

    // ─── Conversation ownership ─────────────────────────────────

    @Nested
    @DisplayName("conversation ownership")
    class ConversationOwnership {

        @Test
        @DisplayName("should own A2A-created conversations with the calling peer's principal")
        void conversationIsOwnedByCallingPeer() throws Exception {
            when(conversationService.startConversation(any(), anyString(), anyString(), anyMap()))
                    .thenReturn(conversation("conv-owned"));
            stubSay();

            handler.handleTaskSend("agent-1", sendParams("task-owned", null, "Hello"));

            verify(conversationService).startConversation(Environment.production, "agent-1", PEER_A, Map.of());
            verify(conversationService, never()).startConversation(any(), anyString(), isNull(), anyMap());
        }

        @Test
        @DisplayName("should stamp a non-null owner even for an anonymous peer")
        void anonymousPeerStillGetsAnOwner() throws Exception {
            when(conversationService.startConversation(any(), anyString(), anyString(), anyMap()))
                    .thenReturn(conversation("conv-anon"));
            stubSay();

            anonymousHandler().handleTaskSend("agent-1", sendParams("task-anon", null, "Hello"));

            verify(conversationService).startConversation(Environment.production, "agent-1",
                    A2ATaskHandler.ANONYMOUS_PEER, Map.of());
            verify(conversationService, never()).startConversation(any(), anyString(), isNull(), anyMap());
        }
    }

    // ─── Peer isolation ─────────────────────────────────────────

    @Nested
    @DisplayName("peer isolation")
    class PeerIsolation {

        @Test
        @DisplayName("peer B cannot read peer A's task")
        void peerBCannotGetPeerATask() throws Exception {
            when(conversationService.startConversation(any(), anyString(), anyString(), anyMap()))
                    .thenReturn(conversation("conv-a"));
            when(conversationService.getConversationState("conv-a")).thenReturn(ConversationState.READY);
            stubSay();

            A2ATaskHandler peerA = handlerFor(PEER_A);
            A2ATaskHandler peerB = handlerFor(PEER_B);
            peerA.handleTaskSend("agent-1", sendParams("task-shared", null, "Hello"));

            // Control: the creating peer still resolves its own task
            A2ATask ownView = peerA.handleTaskGet("task-shared");
            assertNotNull(ownView);
            assertEquals(TaskState.submitted, ownView.status());

            assertNull(peerB.handleTaskGet("task-shared"),
                    "peer B must not resolve a task created by peer A");
            // Peer A's lookup is the only one that reached the conversation
            verify(conversationService, times(1)).getConversationState("conv-a");
        }

        @Test
        @DisplayName("peer B cannot cancel peer A's task")
        void peerBCannotCancelPeerATask() throws Exception {
            when(conversationService.startConversation(any(), anyString(), anyString(), anyMap()))
                    .thenReturn(conversation("conv-a"));
            stubSay();

            A2ATaskHandler peerA = handlerFor(PEER_A);
            A2ATaskHandler peerB = handlerFor(PEER_B);
            peerA.handleTaskSend("agent-1", sendParams("task-shared", null, "Hello"));

            assertFalse(peerB.handleTaskCancel("task-shared"),
                    "peer B must not cancel a task created by peer A");
            verify(conversationService, never()).endConversation(anyString());

            // Control: the creating peer can still cancel
            assertTrue(peerA.handleTaskCancel("task-shared"));
            verify(conversationService).endConversation("conv-a");
        }

        @Test
        @DisplayName("peer B cannot join peer A's conversation by reusing its contextId")
        void peerBCannotReusePeerAContext() throws Exception {
            when(conversationService.startConversation(any(), anyString(), anyString(), anyMap()))
                    .thenReturn(conversation("conv-a"), conversation("conv-b"));
            stubSay();

            A2ATaskHandler peerA = handlerFor(PEER_A);
            A2ATaskHandler peerB = handlerFor(PEER_B);

            peerA.handleTaskSend("agent-1", sendParams("task-a", "ctx-1", "Hello"));
            peerB.handleTaskSend("agent-1", sendParams("task-b", "ctx-1", "Hello"));

            // Peer B got a fresh conversation instead of joining peer A's
            verify(conversationService).startConversation(Environment.production, "agent-1", PEER_A, Map.of());
            verify(conversationService).startConversation(Environment.production, "agent-1", PEER_B, Map.of());
            assertEquals("conv-a", taskCache.get(scopedKey(PEER_A, "task-a")));
            assertEquals("conv-b", taskCache.get(scopedKey(PEER_B, "task-b")));

            // Peer A's turn went to conv-a exactly once — peer B's did not join it
            verify(conversationService, times(1)).say(any(), anyString(), eq("conv-a"),
                    any(), any(), any(), any(), anyBoolean(), any());
            verify(conversationService).say(eq(Environment.production), eq("agent-1"), eq("conv-b"),
                    any(), any(), any(), any(), anyBoolean(), any());
        }

        @Test
        @DisplayName("scoped keys stay distinct when ids contain the separator")
        void scopedKeyIsInjective() {
            // Naive concatenation would collapse these two into the same key, letting a
            // peer craft an id that lands on another peer's entry.
            assertNotEquals(scopedKey("a", "b|c"), scopedKey("a|b", "c"));
            assertNotEquals(scopedKey(PEER_A, "t"), scopedKey(PEER_B, "t"));
            assertEquals(scopedKey(PEER_A, "t"), scopedKey(PEER_A, "t"));
        }
    }

    // ─── handleTaskGet ──────────────────────────────────────────

    @Nested
    @DisplayName("handleTaskGet")
    class HandleTaskGet {

        @Test
        @DisplayName("should return null for unknown taskId")
        void unknownTaskReturnsNull() {
            assertNull(handler.handleTaskGet("unknown-task"));
        }

        @Test
        @DisplayName("should map READY state to submitted")
        void readyMapsToSubmitted() {
            taskCache.put(scopedKey(PEER_A, "t1"), "conv-1");
            when(conversationService.getConversationState("conv-1")).thenReturn(ConversationState.READY);

            A2ATask result = handler.handleTaskGet("t1");

            assertNotNull(result);
            assertEquals(TaskState.submitted, result.status());
        }

        @Test
        @DisplayName("should map IN_PROGRESS state to working")
        void inProgressMapsToWorking() {
            taskCache.put(scopedKey(PEER_A, "t2"), "conv-2");
            when(conversationService.getConversationState("conv-2")).thenReturn(ConversationState.IN_PROGRESS);

            assertEquals(TaskState.working, handler.handleTaskGet("t2").status());
        }

        @Test
        @DisplayName("should map ENDED state to completed")
        void endedMapsToCompleted() {
            taskCache.put(scopedKey(PEER_A, "t3"), "conv-3");
            when(conversationService.getConversationState("conv-3")).thenReturn(ConversationState.ENDED);

            assertEquals(TaskState.completed, handler.handleTaskGet("t3").status());
        }

        @Test
        @DisplayName("should map ERROR state to failed")
        void errorMapsToFailed() {
            taskCache.put(scopedKey(PEER_A, "t4"), "conv-4");
            when(conversationService.getConversationState("conv-4")).thenReturn(ConversationState.ERROR);

            assertEquals(TaskState.failed, handler.handleTaskGet("t4").status());
        }

        @Test
        @DisplayName("should map EXECUTION_INTERRUPTED state to unknown")
        void executionInterruptedMapsToUnknown() {
            taskCache.put(scopedKey(PEER_A, "t4b"), "conv-4b");
            when(conversationService.getConversationState("conv-4b")).thenReturn(ConversationState.EXECUTION_INTERRUPTED);

            assertEquals(TaskState.unknown, handler.handleTaskGet("t4b").status());
        }

        @Test
        @DisplayName("should return unknown on exception")
        void exceptionReturnsUnknown() {
            taskCache.put(scopedKey(PEER_A, "t5"), "conv-5");
            when(conversationService.getConversationState("conv-5"))
                    .thenThrow(new RuntimeException("DB error"));

            assertEquals(TaskState.unknown, handler.handleTaskGet("t5").status());
        }
    }

    // ─── handleTaskCancel ──────────────────────────────────────────

    @Nested
    @DisplayName("handleTaskCancel")
    class HandleTaskCancel {

        @Test
        @DisplayName("should return false for unknown taskId")
        void unknownTaskReturnsFalse() {
            assertFalse(handler.handleTaskCancel("unknown"));
        }

        @Test
        @DisplayName("should end conversation and return true")
        void successfulCancel() {
            taskCache.put(scopedKey(PEER_A, "t-cancel"), "conv-cancel");
            doNothing().when(conversationService).endConversation("conv-cancel");

            assertTrue(handler.handleTaskCancel("t-cancel"));
            verify(conversationService).endConversation("conv-cancel");
        }

        @Test
        @DisplayName("should return false on exception during cancel")
        void cancelExceptionReturnsFalse() {
            taskCache.put(scopedKey(PEER_A, "t-fail"), "conv-fail");
            doThrow(new RuntimeException("fail")).when(conversationService).endConversation("conv-fail");

            assertFalse(handler.handleTaskCancel("t-fail"));
        }
    }

    // ─── A2AModels helper tests ──────────────────────────────────

    @Nested
    @DisplayName("A2AModels Part factories")
    class PartFactories {

        @Test
        @DisplayName("textPart should create text-type Part")
        void textPartCreation() {
            Part part = Part.textPart("Hello");
            assertEquals("text", part.type());
            assertEquals("Hello", part.text());
            assertNull(part.data());
            assertNull(part.metadata());
        }

        @Test
        @DisplayName("dataPart should create data-type Part")
        void dataPartCreation() {
            Map<String, Object> data = Map.of("key", "value");
            Part part = Part.dataPart(data);
            assertEquals("data", part.type());
            assertNull(part.text());
            assertEquals(data, part.data());
        }
    }

    // ─── Test helper: simple ConcurrentHashMap-based ICache ─────

    private static class MapCache<K, V> extends ConcurrentHashMap<K, V> implements ICache<K, V> {

        @Override
        public String getCacheName() {
            return "test-cache";
        }

        @Override
        public V put(K key, V value, long lifespan, TimeUnit unit) {
            return put(key, value);
        }

        @Override
        public V putIfAbsent(K key, V value, long lifespan, TimeUnit unit) {
            return putIfAbsent(key, value);
        }

        @Override
        public void putAll(Map<? extends K, ? extends V> map, long lifespan, TimeUnit unit) {
            putAll(map);
        }

        @Override
        public V replace(K key, V value, long lifespan, TimeUnit unit) {
            return replace(key, value);
        }

        @Override
        public boolean replace(K key, V oldValue, V value, long lifespan, TimeUnit unit) {
            return replace(key, oldValue, value);
        }

        @Override
        public V put(K key, V value, long lifespan, TimeUnit lifespanUnit, long maxIdleTime, TimeUnit maxIdleTimeUnit) {
            return put(key, value);
        }

        @Override
        public V putIfAbsent(K key, V value, long lifespan, TimeUnit lifespanUnit, long maxIdleTime, TimeUnit maxIdleTimeUnit) {
            return putIfAbsent(key, value);
        }
    }
}
