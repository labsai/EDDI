package ai.labs.eddi.engine.a2a;

import ai.labs.eddi.engine.a2a.A2AModels.*;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.api.IConversationService.ConversationResult;
import ai.labs.eddi.engine.caching.ICache;
import ai.labs.eddi.engine.caching.ICacheFactory;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot;
import ai.labs.eddi.engine.model.Deployment.Environment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link A2ATaskHandler} covering task send/get/cancel,
 * conversation resolution, and message extraction.
 */
class A2ATaskHandlerTest {

    private IConversationService conversationService;
    private A2ATaskHandler handler;
    private MapCache<String, String> taskCache;
    private MapCache<String, String> contextCache;

    @BeforeEach
    void setUp() {
        conversationService = mock(IConversationService.class);
        taskCache = new MapCache<>();
        contextCache = new MapCache<>();

        ICacheFactory cacheFactory = mock(ICacheFactory.class);
        when(cacheFactory.<String, String>getCache("a2aTaskMapping")).thenReturn(taskCache);
        when(cacheFactory.<String, String>getCache("a2aTaskMapping:context")).thenReturn(contextCache);

        handler = new A2ATaskHandler(conversationService, cacheFactory);
    }

    // ─── handleTaskSend ──────────────────────────────────────────

    @Nested
    @DisplayName("handleTaskSend")
    class HandleTaskSend {

        @Test
        @DisplayName("should create conversation, send input, and return completed A2ATask")
        void happyPath() throws Exception {
            String agentId = "agent-123";
            var convResult = new ConversationResult("conv-abc", URI.create("/conversations/conv-abc"));
            when(conversationService.startConversation(eq(Environment.production), eq(agentId), isNull(), anyMap()))
                    .thenReturn(convResult);

            doAnswer(invocation -> {
                IConversationService.ConversationResponseHandler handler = invocation.getArgument(8);
                var snapshot = new SimpleConversationMemorySnapshot();
                var output = new ai.labs.eddi.engine.memory.model.ConversationOutput();
                output.put("output", "Hello from EDDI!");
                snapshot.setConversationOutputs(List.of(output));
                handler.onComplete(snapshot);
                return null;
            }).when(conversationService).say(eq(Environment.production), eq(agentId), eq("conv-abc"),
                    eq(false), eq(true), isNull(), any(), eq(false), any());

            Map<String, Object> params = new HashMap<>();
            params.put("id", "task-1");
            params.put("message", Map.of("parts", List.of(Map.of("type", "text", "text", "Hi!"))));

            A2ATask result = handler.handleTaskSend(agentId, params);

            assertNotNull(result);
            assertEquals("task-1", result.id());
            assertEquals(TaskState.completed, result.status());
            assertNotNull(result.history());
            assertEquals(2, result.history().size());
            assertEquals("user", result.history().get(0).role());
            assertEquals("agent", result.history().get(1).role());
            assertNotNull(result.artifacts());
            assertFalse(result.artifacts().isEmpty());

            // Verify conversation was cached
            assertEquals("conv-abc", taskCache.get("task-1"));
        }

        @Test
        @DisplayName("should throw when message is missing from params")
        void missingMessage() {
            Map<String, Object> params = new HashMap<>();
            params.put("id", "task-2");

            assertThrows(IllegalArgumentException.class,
                    () -> handler.handleTaskSend("agent-1", params));
        }

        @Test
        @DisplayName("should throw when message has no text content")
        void blankTextContent() {
            Map<String, Object> params = new HashMap<>();
            params.put("message", Map.of("parts", List.of(Map.of("type", "text", "text", ""))));

            assertThrows(IllegalArgumentException.class,
                    () -> handler.handleTaskSend("agent-1", params));
        }

        @Test
        @DisplayName("should throw when message parts list is empty")
        void emptyPartsThrows() {
            Map<String, Object> params = new HashMap<>();
            params.put("message", Map.of("parts", List.of()));

            assertThrows(IllegalArgumentException.class,
                    () -> handler.handleTaskSend("agent-1", params));
        }

        @Test
        @DisplayName("should reuse conversation for same contextId")
        void reuseConversationWithContextId() throws Exception {
            // Pre-populate the context cache
            contextCache.put("ctx-shared", "existing-conv-id");

            doAnswer(invocation -> {
                IConversationService.ConversationResponseHandler handler = invocation.getArgument(8);
                var snapshot = new SimpleConversationMemorySnapshot();
                var output = new ai.labs.eddi.engine.memory.model.ConversationOutput();
                output.put("output", "Response");
                snapshot.setConversationOutputs(List.of(output));
                handler.onComplete(snapshot);
                return null;
            }).when(conversationService).say(any(), anyString(), eq("existing-conv-id"),
                    anyBoolean(), anyBoolean(), isNull(), any(), anyBoolean(), any());

            Map<String, Object> params = new HashMap<>();
            params.put("id", "task-reuse");
            params.put("contextId", "ctx-shared");
            params.put("message", Map.of("parts", List.of(Map.of("type", "text", "text", "Hello"))));

            handler.handleTaskSend("agent-1", params);

            // Should not start a new conversation — should reuse existing
            verify(conversationService, never()).startConversation(any(), anyString(), any(), anyMap());
            assertEquals("existing-conv-id", taskCache.get("task-reuse"));
        }

        @Test
        @DisplayName("should generate taskId when not provided in params")
        void generatedTaskId() throws Exception {
            var convResult = new ConversationResult("conv-gen", URI.create("/conversations/conv-gen"));
            when(conversationService.startConversation(any(), anyString(), isNull(), anyMap()))
                    .thenReturn(convResult);

            doAnswer(invocation -> {
                IConversationService.ConversationResponseHandler handler = invocation.getArgument(8);
                handler.onComplete(new SimpleConversationMemorySnapshot());
                return null;
            }).when(conversationService).say(any(), anyString(), eq("conv-gen"),
                    anyBoolean(), anyBoolean(), isNull(), any(), anyBoolean(), any());

            Map<String, Object> params = new HashMap<>();
            // No "id" key — should auto-generate
            params.put("message", Map.of("parts", List.of(Map.of("type", "text", "text", "Test"))));

            A2ATask result = handler.handleTaskSend("agent-1", params);

            assertNotNull(result.id());
            assertFalse(result.id().isBlank());
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
            taskCache.put("t1", "conv-1");
            when(conversationService.getConversationState("conv-1")).thenReturn(ConversationState.READY);

            A2ATask result = handler.handleTaskGet("t1");

            assertNotNull(result);
            assertEquals(TaskState.submitted, result.status());
        }

        @Test
        @DisplayName("should map IN_PROGRESS state to working")
        void inProgressMapsToWorking() {
            taskCache.put("t2", "conv-2");
            when(conversationService.getConversationState("conv-2")).thenReturn(ConversationState.IN_PROGRESS);

            assertEquals(TaskState.working, handler.handleTaskGet("t2").status());
        }

        @Test
        @DisplayName("should map ENDED state to completed")
        void endedMapsToCompleted() {
            taskCache.put("t3", "conv-3");
            when(conversationService.getConversationState("conv-3")).thenReturn(ConversationState.ENDED);

            assertEquals(TaskState.completed, handler.handleTaskGet("t3").status());
        }

        @Test
        @DisplayName("should map ERROR state to failed")
        void errorMapsToFailed() {
            taskCache.put("t4", "conv-4");
            when(conversationService.getConversationState("conv-4")).thenReturn(ConversationState.ERROR);

            assertEquals(TaskState.failed, handler.handleTaskGet("t4").status());
        }

        @Test
        @DisplayName("should map EXECUTION_INTERRUPTED state to unknown")
        void executionInterruptedMapsToUnknown() {
            taskCache.put("t4b", "conv-4b");
            when(conversationService.getConversationState("conv-4b")).thenReturn(ConversationState.EXECUTION_INTERRUPTED);

            assertEquals(TaskState.unknown, handler.handleTaskGet("t4b").status());
        }

        @Test
        @DisplayName("should return unknown on exception")
        void exceptionReturnsUnknown() {
            taskCache.put("t5", "conv-5");
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
            taskCache.put("t-cancel", "conv-cancel");
            doNothing().when(conversationService).endConversation("conv-cancel");

            assertTrue(handler.handleTaskCancel("t-cancel"));
            verify(conversationService).endConversation("conv-cancel");
        }

        @Test
        @DisplayName("should return false on exception during cancel")
        void cancelExceptionReturnsFalse() {
            taskCache.put("t-fail", "conv-fail");
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

    @SuppressWarnings("serial")
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
