/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.a2a;

import ai.labs.eddi.datastore.IResourceStore.ResourceNotFoundException;
import ai.labs.eddi.engine.a2a.A2AModels.A2ATask;
import ai.labs.eddi.engine.a2a.A2AModels.Dialect;
import ai.labs.eddi.engine.a2a.A2AModels.FilePart;
import ai.labs.eddi.engine.a2a.A2AModels.InboundFileMode;
import ai.labs.eddi.engine.a2a.A2AModels.InvalidA2ARequestException;
import ai.labs.eddi.engine.a2a.A2AModels.TaskState;
import ai.labs.eddi.engine.a2a.A2ATaskHandler.StreamEvent;
import ai.labs.eddi.engine.a2a.A2AWireFormat.SendRequest;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.api.IConversationService.ConversationResponseHandler;
import ai.labs.eddi.engine.api.IConversationService.ConversationResult;
import ai.labs.eddi.engine.attachments.IAttachmentStore;
import ai.labs.eddi.engine.caching.ICache;
import ai.labs.eddi.engine.caching.ICacheFactory;
import ai.labs.eddi.engine.httpclient.outbound.ScriptedEngine;
import ai.labs.eddi.engine.httpclient.outbound.TestOutbound;
import ai.labs.eddi.engine.memory.model.ConversationOutput;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot;
import ai.labs.eddi.engine.model.Context;
import ai.labs.eddi.engine.model.Deployment.Environment;
import ai.labs.eddi.engine.model.InputData;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static ai.labs.eddi.engine.a2a.A2AModels.ERROR_CONTENT_TYPE_NOT_SUPPORTED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link A2ATaskHandler} with inbound file parts on: the files are stored into
 * the task's conversation before its turn runs and reach the turn as
 * {@code attachment_N}; a refused file leaves no task, no turn and no stored
 * blob behind; a context that moves to a fresh conversation takes its files
 * along; with files off nothing changes.
 */
class A2ATaskHandlerFilesTest {

    private static final String PEER = "peer-a";
    private static final String AGENT = "agent-123";
    private static final byte[] PDF = "%PDF-1.7\n1 0 obj\n<<>>\nendobj\n%%EOF\n".getBytes(StandardCharsets.US_ASCII);

    private IConversationService conversationService;
    private CachedA2ATaskStore taskStore;
    private AgentCardService agentCardService;
    private SimpleMeterRegistry meterRegistry;
    private FakeAttachmentStore store;
    private final AtomicInteger conversations = new AtomicInteger();
    private final Map<String, SimpleConversationMemorySnapshot> stored = new ConcurrentHashMap<>();
    /** The input of every turn that ran, in order. */
    private final List<InputData> turns = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        conversationService = mock(IConversationService.class);
        ICacheFactory cacheFactory = mock(ICacheFactory.class);
        when(cacheFactory.<String, Object>getCache(anyString())).thenAnswer(invocation -> new MapCache<>());
        taskStore = new CachedA2ATaskStore(cacheFactory);
        agentCardService = mock(AgentCardService.class);
        when(agentCardService.getAgentCard(anyString())).thenReturn(mock(A2AModels.AgentCard.class));
        meterRegistry = new SimpleMeterRegistry();
        store = new FakeAttachmentStore(1024);
        when(conversationService.startConversation(eq(Environment.production), anyString(), anyString(), anyMap())).thenAnswer(invocation -> {
            String id = "conv" + conversations.incrementAndGet();
            var snapshot = new SimpleConversationMemorySnapshot();
            snapshot.setUserId(invocation.getArgument(2));
            snapshot.setAgentId(invocation.getArgument(1));
            snapshot.setConversationState(ConversationState.READY);
            stored.put(id, snapshot);
            return new ConversationResult(id, URI.create("/conversations/" + id));
        });
        when(conversationService.readConversation(anyString(), any(), any(), any())).thenAnswer(invocation -> {
            var snapshot = stored.get((String) invocation.getArgument(0));
            if (snapshot == null) {
                throw new ResourceNotFoundException("no such conversation");
            }
            return snapshot;
        });
        doAnswer(invocation -> {
            InputData input = invocation.getArgument(6);
            turns.add(input);
            ConversationResponseHandler handler = invocation.getArgument(8);
            var output = new ConversationOutput();
            output.put("output", List.of(Map.of("type", "text", "text", "read it")));
            output.put("context", Map.of(A2ATaskHandler.CONTEXT_TASK_ID, input.getContext().get(A2ATaskHandler.CONTEXT_TASK_ID).getValue()));
            var turn = new SimpleConversationMemorySnapshot();
            turn.setConversationState(ConversationState.READY);
            turn.setConversationOutputs(List.of(output));
            handler.onComplete(turn);
            return null;
        }).when(conversationService).say(any(), anyString(), anyString(), any(), any(), any(), any(), anyBoolean(), any());
    }

    private A2ATaskHandler handler(InboundFileMode mode, int maxPerMessage) {
        SecurityIdentity identity = mock(SecurityIdentity.class);
        when(identity.isAnonymous()).thenReturn(false);
        when(identity.getPrincipal()).thenReturn((Principal) () -> PEER);
        @SuppressWarnings("unchecked")
        Instance<IAttachmentStore> instance = mock(Instance.class);
        when(instance.isResolvable()).thenReturn(true);
        when(instance.get()).thenReturn(store);
        var inbound = new A2AInboundFiles(mode, maxPerMessage, 1024, instance, TestOutbound.facade(new ScriptedEngine()), meterRegistry);
        return new A2ATaskHandler(conversationService, taskStore, identity, agentCardService, new A2AInFlightLimiter(8, meterRegistry),
                meterRegistry, 60, Optional.empty(), inbound);
    }

    private static FilePart pdf(String name) {
        return new FilePart(name, "application/pdf", Base64.getEncoder().encodeToString(PDF), null);
    }

    private static SendRequest withFiles(String text, String contextId, String taskId, List<FilePart> files) {
        return new SendRequest(text, "m1", contextId, taskId, null, false, null, Dialect.V1_0, files);
    }

    private static String refOf(InputData input, String key) {
        Context context = input.getContext().get(key);
        return (String) ((Map<?, ?>) context.getValue()).get("storageRef");
    }

    @Test
    @DisplayName("a message of files alone runs a turn whose input names the stored file, owned by the task's conversation")
    void fileOnlyMessageRunsWithTheAttachment() throws Exception {
        A2ATask task = handler(InboundFileMode.INLINE, 5).send(AGENT, withFiles("", null, null, List.of(pdf("report.pdf"))));

        assertEquals(TaskState.completed, task.state());
        assertEquals(1, turns.size());
        InputData input = turns.getFirst();
        assertEquals("", input.getInput());
        String ref = refOf(input, "attachment_0");
        assertEquals("conv1", store.blobs.get(ref).conversationId());
        assertEquals(PEER, store.blobs.get(ref).userId());
        assertTrue(input.getContext().containsKey(A2ATaskHandler.CONTEXT_TASK_ID), "the task id still rides along");
    }

    @Test
    @DisplayName("a refused file leaves no task, no bound context, no turn and no blob")
    void refusedFileLeavesNothing() throws Exception {
        A2ATaskHandler handler = handler(InboundFileMode.INLINE, 5);
        FilePart notAPdf = new FilePart("x.pdf", "application/pdf", Base64.getEncoder().encodeToString("plain".getBytes()), null);

        assertThrows(InvalidA2ARequestException.class,
                () -> handler.send(AGENT, withFiles("Read this", null, null, List.of(pdf("ok.pdf"), notAPdf))));

        assertTrue(turns.isEmpty());
        assertTrue(store.blobs.isEmpty());
        verify(conversationService, never()).startConversation(any(), anyString(), anyString(), anyMap());
        verify(conversationService, never()).say(any(), anyString(), anyString(), any(), any(), any(), any(), anyBoolean(), any());
    }

    @Test
    @DisplayName("bytes that are not base64 are refused before any conversation is created")
    void invalidBase64BeforeConversation() {
        A2ATaskHandler handler = handler(InboundFileMode.INLINE, 5);

        assertThrows(InvalidA2ARequestException.class,
                () -> handler.send(AGENT, withFiles("", null, null, List.of(new FilePart("x", null, "!!!not base64", null)))));

        verifyNoConversationStarted();
    }

    @Test
    @DisplayName("an exhausted quota, found once the conversation exists: the files stored first are removed, the conversation ended")
    void quotaRefusalEndsTheFreshConversation() throws Exception {
        store.maxPerUser(1);
        A2ATaskHandler handler = handler(InboundFileMode.INLINE, 5);

        assertThrows(InvalidA2ARequestException.class,
                () -> handler.send(AGENT, withFiles("Read these", null, null, List.of(pdf("a.pdf"), pdf("b.pdf")))));

        assertTrue(store.blobs.isEmpty(), "the file stored first is removed again");
        assertTrue(taskStore.findContextConversation(PEER, "conv1").isEmpty(), "no context was bound");
        verify(conversationService).endConversation(eq("conv1"), eq("a2a:" + PEER), anyString());
        assertTrue(turns.isEmpty());
    }

    private void verifyNoConversationStarted() {
        try {
            verify(conversationService, never()).startConversation(any(), anyString(), anyString(), anyMap());
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    @DisplayName("over the per-message cap: refused before any conversation is created")
    void countCapBeforeConversation() throws Exception {
        A2ATaskHandler handler = handler(InboundFileMode.INLINE, 1);

        assertThrows(InvalidA2ARequestException.class,
                () -> handler.send(AGENT, withFiles("", null, null, List.of(pdf("a.pdf"), pdf("b.pdf")))));

        verify(conversationService, never()).startConversation(any(), anyString(), anyString(), anyMap());
        assertTrue(store.blobs.isEmpty());
    }

    @Test
    @DisplayName("a streamed send whose file is refused is a plain error, before any event")
    void streamRefusalBeforeEvents() {
        List<StreamEvent> events = new ArrayList<>();
        FilePart byUri = new FilePart("a.pdf", null, null, "http://93.184.216.34/a.pdf");

        InvalidA2ARequestException e = assertThrows(InvalidA2ARequestException.class,
                () -> handler(InboundFileMode.INLINE, 5).stream(AGENT, withFiles("", null, null, List.of(byUri)), events::add));

        assertEquals(ERROR_CONTENT_TYPE_NOT_SUPPORTED, e.getCode());
        assertTrue(events.isEmpty());
    }

    @Test
    @DisplayName("a message continuing a task stores its files into that task's conversation")
    void continuationStoresIntoTheTasksConversation() throws Exception {
        A2ATaskHandler handler = handler(InboundFileMode.INLINE, 5);
        // A paused first turn, so the task can take a second message.
        doAnswer(invocation -> {
            InputData input = invocation.getArgument(6);
            turns.add(input);
            ConversationResponseHandler responseHandler = invocation.getArgument(8);
            var output = new ConversationOutput();
            output.put("context", Map.of(A2ATaskHandler.CONTEXT_TASK_ID, input.getContext().get(A2ATaskHandler.CONTEXT_TASK_ID).getValue()));
            var turn = new SimpleConversationMemorySnapshot();
            turn.setConversationState(ConversationState.AWAITING_HUMAN);
            turn.setConversationOutputs(List.of(output));
            responseHandler.onComplete(turn);
            return null;
        }).when(conversationService).say(any(), anyString(), anyString(), any(), any(), any(), any(), anyBoolean(), any());
        A2ATask paused = handler.send(AGENT, withFiles("start", null, null, List.of()));

        handler.send(AGENT, withFiles("", null, paused.id(), List.of(pdf("more.pdf"))));

        assertEquals("conv1", store.blobs.get(refOf(turns.getLast(), "attachment_0")).conversationId());
        assertEquals(1, conversations.get(), "no new conversation for a continued task");
    }

    @Test
    @DisplayName("a context whose conversation ended carries on in a fresh one — with its files moved there")
    void endedContextTakesItsFilesAlong() throws Exception {
        A2ATaskHandler handler = handler(InboundFileMode.INLINE, 5);
        A2ATask first = handler.send(AGENT, withFiles("one", null, null, List.of()));
        List<InputData> attempts = new ArrayList<>();
        doThrow(new IConversationService.ConversationEndedException("ended")).when(conversationService).say(any(), anyString(), eq("conv1"),
                any(), any(), any(), any(), anyBoolean(), any());
        doAnswer(invocation -> {
            attempts.add(invocation.getArgument(6));
            ConversationResponseHandler responseHandler = invocation.getArgument(8);
            InputData input = invocation.getArgument(6);
            var output = new ConversationOutput();
            output.put("output", List.of(Map.of("type", "text", "text", "fresh")));
            output.put("context", Map.of(A2ATaskHandler.CONTEXT_TASK_ID, input.getContext().get(A2ATaskHandler.CONTEXT_TASK_ID).getValue()));
            var turn = new SimpleConversationMemorySnapshot();
            turn.setConversationState(ConversationState.READY);
            turn.setConversationOutputs(List.of(output));
            responseHandler.onComplete(turn);
            return null;
        }).when(conversationService).say(any(), anyString(), eq("conv2"), any(), any(), any(), any(), anyBoolean(), any());

        A2ATask second = handler.send(AGENT, withFiles("two", first.contextId(), null, List.of(pdf("report.pdf"))));

        assertEquals(TaskState.completed, second.state());
        InputData freshTurn = attempts.getLast();
        String ref = refOf(freshTurn, "attachment_0");
        assertEquals("conv2", store.blobs.get(ref).conversationId(), "the fresh conversation owns the file it reads");
        assertEquals(1, store.blobs.size(), "the ended conversation keeps no copy");
        assertFalse(store.listByConversation("conv1").stream().findAny().isPresent());
    }

    @Test
    @DisplayName("with files off, a request carrying files is refused with -32005 and stores nothing")
    void offRefuses() {
        InvalidA2ARequestException e = assertThrows(InvalidA2ARequestException.class,
                () -> handler(InboundFileMode.OFF, 5).send(AGENT, withFiles("", null, null, List.of(pdf("a.pdf")))));
        assertEquals(ERROR_CONTENT_TYPE_NOT_SUPPORTED, e.getCode());
        assertFalse(handler(InboundFileMode.OFF, 5).acceptsFiles());
        assertTrue(store.blobs.isEmpty());
    }

    private static class MapCache<K, V> extends ConcurrentHashMap<K, V> implements ICache<K, V> {

        @Override
        public String getCacheName() {
            return "test";
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
