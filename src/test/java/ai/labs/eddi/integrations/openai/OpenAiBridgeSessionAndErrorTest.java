/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.openai;

import ai.labs.eddi.configs.properties.IUserMemoryStore;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.memory.MemoryKeys;
import ai.labs.eddi.engine.memory.model.ConversationListingSummary;
import ai.labs.eddi.engine.memory.model.ConversationOutput;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot.ConversationStepData;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot.SimpleConversationStep;
import ai.labs.eddi.engine.model.Deployment.Environment;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import ai.labs.eddi.engine.triggermanagement.IUserConversationStore;
import ai.labs.eddi.engine.triggermanagement.model.UserConversation;
import ai.labs.eddi.integrations.openai.model.ChatCompletionRequest;
import ai.labs.eddi.integrations.openai.model.Choice;
import ai.labs.eddi.integrations.openai.model.OpenAiErrorResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static ai.labs.eddi.integrations.openai.OpenAiTestFixtures.AGENT_ID_SUPPORT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The {@code /v1} chat key for clients that name no chat, the step rollover,
 * and the OpenAI-shaped reporting of failed, truncated and timed-out turns.
 */
class OpenAiBridgeSessionAndErrorTest {

    private static final String CONVERSATION_ID = "66c0ffee0000000000000001";
    private static final String OTHER_CONVERSATION_ID = "66c0ffee0000000000000002";
    private static final String USER_ID = "u_812";

    private IConversationService conversationService;
    private IUserConversationStore userConversationStore;
    private IConversationMemoryStore conversationMemoryStore;
    private SimpleMeterRegistry meterRegistry;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final AgentModelResolver.ResolvedModel statefulModel = new AgentModelResolver.ResolvedModel(
            AGENT_ID_SUPPORT, Environment.production, "Support", "support-a3f9c1", "support-a3f9c1", 0L, false);
    private final AgentModelResolver.ResolvedModel statelessModel = new AgentModelResolver.ResolvedModel(
            AGENT_ID_SUPPORT, Environment.production, "Support", "support-a3f9c1:stateless", "support-a3f9c1:stateless", 0L, true);

    @BeforeEach
    void setUp() throws Exception {
        conversationService = mock(IConversationService.class);
        userConversationStore = mock(IUserConversationStore.class);
        conversationMemoryStore = mock(IConversationMemoryStore.class);
        meterRegistry = new SimpleMeterRegistry();
        when(conversationService.startConversation(any(), any(), any(), any()))
                .thenReturn(new IConversationService.ConversationResult(
                        CONVERSATION_ID, URI.create("eddi://conversation/" + CONVERSATION_ID)));
        when(conversationService.getConversationState(any(String.class))).thenReturn(ConversationState.READY);
    }

    private OpenAiConversationBridge bridge(Consumer<OpenAiTestFixtures.ConfigBuilder> customizer) {
        var bridge = new OpenAiConversationBridge(conversationService, userConversationStore, mock(IUserMemoryStore.class),
                new OpenAiMessageMapper(objectMapper, 5),
                OpenAiTestFixtures.config(b -> {
                    b.requestTimeoutSeconds = 1;
                    customizer.accept(b);
                }),
                meterRegistry, mock(ResourceAccessGuard.class), conversationMemoryStore);
        bridge.initMetrics();
        return bridge;
    }

    private OpenAiConversationBridge bridge() {
        return bridge(b -> {
        });
    }

    private ChatCompletionRequest request(String json) throws Exception {
        return objectMapper.readValue(json, ChatCompletionRequest.class);
    }

    /** The opening turn of a chat as an OpenAI client sends it: no history yet. */
    private ChatCompletionRequest firstTurn(String system, String user) throws Exception {
        return request("""
                {"model":"support-a3f9c1","messages":[{"role":"system","content":"%s"},{"role":"user","content":"%s"}]}"""
                .formatted(system, user));
    }

    /** A later turn of the same chat: the client resends its whole history. */
    private ChatCompletionRequest laterTurn(String system, String firstUser, String reply, String next) throws Exception {
        return request("""
                {"model":"support-a3f9c1","messages":[{"role":"system","content":"%s"},{"role":"user","content":"%s"},
                 {"role":"assistant","content":"%s"},{"role":"user","content":"%s"}]}"""
                .formatted(system, firstUser, reply, next));
    }

    private static Map<String, String> noHeaders() {
        return new HashMap<>();
    }

    private UserConversation mapping(String intent, String conversationId) {
        return new UserConversation(intent, USER_ID, Environment.production, AGENT_ID_SUPPORT, conversationId);
    }

    private static SimpleConversationMemorySnapshot snapshot(ConversationState state, String text) {
        var snapshot = new SimpleConversationMemorySnapshot();
        snapshot.setConversationId(CONVERSATION_ID);
        snapshot.setConversationState(state);
        var output = new ConversationOutput();
        if (text != null) {
            output.put("output", List.of(text));
        }
        snapshot.setConversationOutputs(List.of(output));
        return snapshot;
    }

    private static SimpleConversationMemorySnapshot failedTurn(String digest) {
        var snapshot = snapshot(ConversationState.ERROR, null);
        snapshot.getConversationOutputs().get(0).put(MemoryKeys.TASK_ERRORS,
                List.of(Map.of("type", "errorDigest", "taskId", "ai.labs.llm", "text", digest)));
        return snapshot;
    }

    private static SimpleConversationMemorySnapshot withStepDatum(SimpleConversationMemorySnapshot snapshot, String key,
                                                                  Object value) {
        var step = new SimpleConversationStep();
        step.getConversationStep().add(new ConversationStepData(key, value, new Date(), null));
        snapshot.getConversationSteps().add(step);
        return snapshot;
    }

    private void givenStreamingEmits(Consumer<IConversationService.StreamingResponseHandler> script) throws Exception {
        doAnswer(invocation -> {
            script.accept(invocation.getArgument(5));
            return null;
        }).when(conversationService).sayStreaming(any(), anyBoolean(), anyBoolean(), any(), any(), any());
    }

    private String stream(OpenAiConversationBridge bridge) throws Exception {
        var turn = bridge.prepare(statelessModel, firstTurn("s", "hi"), noHeaders(), USER_ID);
        var out = new ByteArrayOutputStream();
        bridge.stream(turn, new OpenAiSseWriter(out, objectMapper, "id", "m", 1L, false));
        return out.toString(StandardCharsets.UTF_8);
    }

    private static int count(String haystack, String needle) {
        int count = 0;
        for (int index = haystack.indexOf(needle); index >= 0; index = haystack.indexOf(needle, index + needle.length())) {
            count++;
        }
        return count;
    }

    @Nested
    class ChatKeys {

        @Test
        void eddiChatIdHeader_isAnExplicitKey() throws Exception {
            var headers = noHeaders();
            headers.put("x-eddi-chat-id", "thread-7");

            var key = bridge().resolveSessionKey(headers, firstTurn("s", "hi"));

            assertEquals("thread-7", key.key());
            assertEquals(OpenAiConversationBridge.SOURCE_EDDI_HEADER, key.source());
        }

        @Test
        void metadataChatId_isAnExplicitKey() throws Exception {
            var key = bridge().resolveSessionKey(noHeaders(), request("""
                    {"model":"m","metadata":{"chat_id":"thread-9","other":"x"},"messages":[{"role":"user","content":"hi"}]}"""));

            assertEquals("thread-9", key.key());
            assertEquals(OpenAiConversationBridge.SOURCE_METADATA, key.source());
        }

        @Test
        void openWebUiHeader_winsOverTheEddiHeaderAndMetadata() throws Exception {
            var headers = noHeaders();
            headers.put(OpenAiAuthFilter.HEADER_CHAT_ID, "owui");
            headers.put(OpenAiConversationBridge.HEADER_EDDI_CHAT_ID, "eddi");

            var key = bridge().resolveSessionKey(headers, request("""
                    {"model":"m","metadata":{"chat_id":"meta"},"user":"u","messages":[{"role":"user","content":"hi"}]}"""));

            assertEquals("owui", key.key());
        }

        @Test
        void explicitKeyBeatsTheDerivedOne() throws Exception {
            var key = bridge().resolveSessionKey(noHeaders(), request("""
                    {"model":"m","user":"session-1","messages":[{"role":"user","content":"hi"}]}"""));

            assertEquals("session-1", key.key());
            assertEquals(OpenAiConversationBridge.SOURCE_USER, key.source());
        }

        @Test
        void anOverlongExplicitKeyIsStoredAsItsHash() {
            String longKey = "x".repeat(500);

            String bounded = OpenAiConversationBridge.boundExplicitKey(longKey);

            assertTrue(bounded.startsWith(OpenAiConversationBridge.HASHED_KEY_PREFIX));
            assertTrue(bounded.length() < 100);
            assertEquals("short", OpenAiConversationBridge.boundExplicitKey("short"));
        }

        @Test
        void unkeyedChat_getsTheSameDerivedKeyOnEveryTurn() throws Exception {
            var bridge = bridge();

            var turn1 = bridge.resolveSessionKey(noHeaders(), firstTurn("be brief", "plan a trip"));
            var turn2 = bridge.resolveSessionKey(noHeaders(), laterTurn("be brief", "plan a trip", "where to?", "Rome"));

            assertEquals(OpenAiConversationBridge.SOURCE_HISTORY, turn1.source());
            assertTrue(turn1.key().startsWith(OpenAiConversationBridge.HISTORY_KEY_PREFIX));
            assertEquals(turn1.key(), turn2.key());
        }

        @Test
        void twoUnkeyedChats_getDifferentKeys() throws Exception {
            var bridge = bridge();

            var chatA = bridge.resolveSessionKey(noHeaders(), firstTurn("be brief", "plan a trip"));
            var chatB = bridge.resolveSessionKey(noHeaders(), firstTurn("be brief", "fix my bike"));
            var chatC = bridge.resolveSessionKey(noHeaders(), firstTurn("be verbose", "plan a trip"));

            assertNotEquals(chatA.key(), chatB.key());
            assertNotEquals(chatA.key(), chatC.key(), "the system prompt is part of the chat's identity");
        }

        @Test
        void sharedFallback_restoresTheSingleConversation() throws Exception {
            var key = bridge(b -> b.chatKeyFallback = OpenAiCompatConfig.CHAT_KEY_FALLBACK_SHARED)
                    .resolveSessionKey(noHeaders(), firstTurn("s", "plan a trip"));

            assertNull(key.key());
            assertEquals(OpenAiConversationBridge.SOURCE_SHARED, key.source());
        }

        @Test
        void twoUnkeyedChats_mapToTwoConversations() throws Exception {
            var bridge = bridge();
            when(userConversationStore.readUserConversation(any(), any())).thenReturn(null);

            bridge.prepare(statefulModel, firstTurn("s", "plan a trip"), noHeaders(), USER_ID);
            bridge.prepare(statefulModel, firstTurn("s", "fix my bike"), noHeaders(), USER_ID);

            var captor = ArgumentCaptor.forClass(UserConversation.class);
            verify(userConversationStore, times(2)).createUserConversation(captor.capture());
            assertNotEquals(captor.getAllValues().get(0).getIntent(), captor.getAllValues().get(1).getIntent());
            assertTrue(captor.getAllValues().get(0).getIntent().contains(":h:"));
        }

        @Test
        void laterTurnOfAnUnkeyedChat_continuesItsConversation() throws Exception {
            var bridge = bridge();
            String intent = bridge.buildIntent(AGENT_ID_SUPPORT,
                    OpenAiConversationBridge.historyKey(firstTurn("s", "plan a trip")));
            when(userConversationStore.readUserConversation(eq(intent), eq(USER_ID)))
                    .thenReturn(mapping(intent, OTHER_CONVERSATION_ID));

            var turn = bridge.prepare(statefulModel, laterTurn("s", "plan a trip", "where?", "Rome"), noHeaders(), USER_ID);

            assertEquals(OTHER_CONVERSATION_ID, turn.conversationId());
            assertEquals(intent, turn.intent());
            verify(conversationService, never()).startConversation(any(), any(), any(), any());
        }

        @Test
        void openingTurnThatRepeatsAnEarlierChatsOpener_startsAFreshConversation() throws Exception {
            // "Hi" opens many chats. Without history the request is a NEW chat, so an
            // existing mapping under the same derived key must not be continued — and
            // the old conversation is abandoned, not ended.
            var bridge = bridge();
            when(userConversationStore.readUserConversation(any(), eq(USER_ID)))
                    .thenReturn(mapping("i", OTHER_CONVERSATION_ID))
                    .thenReturn(null);

            var turn = bridge.prepare(statefulModel, firstTurn("s", "Hi"), noHeaders(), USER_ID);

            assertEquals(CONVERSATION_ID, turn.conversationId());
            verify(userConversationStore).deleteUserConversation(any(), eq(USER_ID));
            verify(conversationService, never()).endConversation(OTHER_CONVERSATION_ID);
        }

        @Test
        void explicitlyKeyedOpeningTurn_stillContinuesItsMapping() throws Exception {
            // The fresh-chat rule is for derived keys only: an explicit key is never
            // second-guessed, whatever the request's history.
            var bridge = bridge();
            var headers = noHeaders();
            headers.put(OpenAiConversationBridge.HEADER_EDDI_CHAT_ID, "thread-1");
            when(userConversationStore.readUserConversation(any(), eq(USER_ID)))
                    .thenReturn(mapping("i", OTHER_CONVERSATION_ID));

            var turn = bridge.prepare(statefulModel, firstTurn("s", "Hi"), headers, USER_ID);

            assertEquals(OTHER_CONVERSATION_ID, turn.conversationId());
            verify(userConversationStore, never()).deleteUserConversation(any(), any());
        }

        @Test
        void chatKeySourceIsCounted() throws Exception {
            when(userConversationStore.readUserConversation(any(), any())).thenReturn(null);

            bridge().prepare(statefulModel, firstTurn("s", "Hi"), noHeaders(), USER_ID);

            assertEquals(1.0, meterRegistry.counter("eddi.openai.chat_keys", "source", "history").count());
        }
    }

    @Nested
    class Rollover {

        private void givenSize(int steps, ConversationState state) throws Exception {
            when(conversationMemoryStore.loadListingSummaries(any())).thenReturn(Map.of(OTHER_CONVERSATION_ID,
                    new ConversationListingSummary(OTHER_CONVERSATION_ID, USER_ID, Environment.production, state,
                            AGENT_ID_SUPPORT, 1, steps)));
            when(userConversationStore.readUserConversation(any(), eq(USER_ID)))
                    .thenReturn(mapping("i", OTHER_CONVERSATION_ID))
                    .thenReturn(null);
        }

        private ChatCompletionRequest keyed() throws Exception {
            return request("""
                    {"model":"m","user":"thread","messages":[{"role":"user","content":"hi"}]}""");
        }

        @Test
        void aMappedConversationAtTheLimit_isEndedAndReplaced() throws Exception {
            givenSize(10, ConversationState.READY);

            var turn = bridge(b -> b.maxConversationSteps = 10).prepare(statefulModel, keyed(), noHeaders(), USER_ID);

            assertEquals(CONVERSATION_ID, turn.conversationId());
            verify(conversationService).endConversation(OTHER_CONVERSATION_ID);
            assertEquals(1.0, meterRegistry.counter("eddi.openai.conversations.rolled_over").count());
        }

        @Test
        void belowTheLimit_isKept() throws Exception {
            givenSize(9, ConversationState.READY);

            var turn = bridge(b -> b.maxConversationSteps = 10).prepare(statefulModel, keyed(), noHeaders(), USER_ID);

            assertEquals(OTHER_CONVERSATION_ID, turn.conversationId());
            verify(conversationService, never()).endConversation(any());
        }

        @Test
        void aPendingApproval_isNeverRolledOver() throws Exception {
            givenSize(50, ConversationState.AWAITING_HUMAN);

            var turn = bridge(b -> b.maxConversationSteps = 10).prepare(statefulModel, keyed(), noHeaders(), USER_ID);

            assertEquals(OTHER_CONVERSATION_ID, turn.conversationId());
            verify(conversationService, never()).endConversation(any());
        }

        @Test
        void disabled_byDefault_readsNothing() throws Exception {
            givenSize(5000, ConversationState.READY);

            var turn = bridge().prepare(statefulModel, keyed(), noHeaders(), USER_ID);

            assertEquals(OTHER_CONVERSATION_ID, turn.conversationId());
            verify(conversationMemoryStore, never()).loadListingSummaries(any());
        }
    }

    @Nested
    class Errors {

        @Test
        void aFailedTurn_isA500AgentError_notAStop() {
            var failure = assertThrows(OpenAiApiException.class,
                    () -> bridge().render(failedTurn("Task 'eddi://ai.labs.llm' failed: connection refused")));

            assertEquals(500, failure.getStatus());
            assertEquals(OpenAiErrorResponse.TYPE_SERVER_ERROR, failure.getType());
            assertEquals(OpenAiErrorResponse.CODE_AGENT_ERROR, failure.getCode());
            assertTrue(failure.getMessage().contains("connection refused"), failure.getMessage());
        }

        @Test
        void anUnclassifiedException_isNotEchoedToTheCaller() {
            var failure = bridge().asApiException(new IllegalStateException("connect to mongodb://admin:hunter2@db.internal:27017 failed"));

            assertEquals(500, failure.getStatus());
            assertFalse(failure.getMessage().contains("hunter2") || failure.getMessage().contains("db.internal"), failure.getMessage());
        }

        @Test
        void aFailedTurnWithoutDigest_stillFails() {
            var failure = assertThrows(OpenAiApiException.class,
                    () -> bridge().render(snapshot(ConversationState.ERROR, "partial")));

            assertEquals(OpenAiErrorResponse.CODE_AGENT_ERROR, failure.getCode());
        }

        @Test
        void truncatedAnswer_reportsLength() {
            var outcome = bridge().render(withStepDatum(snapshot(ConversationState.READY, "cut of"),
                    MemoryKeys.LLM_FINISH_REASON, "length"));

            assertEquals(Choice.FINISH_LENGTH, outcome.finishReason());
            assertEquals("cut of", outcome.text());
        }

        @Test
        void filteredAnswer_reportsContentFilter() {
            var outcome = bridge().render(withStepDatum(snapshot(ConversationState.READY, "x"),
                    MemoryKeys.LLM_FINISH_REASON, "content_filter"));

            assertEquals(Choice.FINISH_CONTENT_FILTER, outcome.finishReason());
        }

        @Test
        void normalAnswer_reportsStop() {
            var outcome = bridge().render(snapshot(ConversationState.READY, "fine"));

            assertEquals(Choice.FINISH_STOP, outcome.finishReason());
        }

        @Test
        void stream_aFailedTurn_endsWithAnErrorEvent_andOneDone() throws Exception {
            // The engine reports a failed streamed turn twice: onComplete with the ERROR
            // snapshot, then onError. Exactly one terminator may reach the client.
            givenStreamingEmits(handler -> {
                handler.onToken("par");
                handler.onComplete(failedTurn("Task 'eddi://ai.labs.llm' failed: boom"));
                handler.onError(new IllegalStateException("boom"));
            });

            String body = stream(bridge());

            assertEquals(1, count(body, "\"error\":{"), body);
            assertTrue(body.contains("\"code\":\"agent_error\""), body);
            assertFalse(body.contains("\"finish_reason\":\"stop\""), body);
            assertEquals(1, count(body, "data: [DONE]"), body);
            assertTrue(body.endsWith("data: [DONE]\n\n"), body);
        }

        @Test
        void stream_onError_isAnErrorEvent_notWarningText() throws Exception {
            givenStreamingEmits(handler -> handler.onError(new IllegalStateException("model down")));

            String body = stream(bridge());

            assertTrue(body.contains("\"error\":{"), body);
            assertTrue(body.contains("model down"), body);
            assertFalse(body.contains("\"content\""), "a failure is not the model's answer: " + body);
        }

        @Test
        void stream_busy_isARateLimitErrorEvent() throws Exception {
            givenStreamingEmits(handler -> handler.onSkipped(snapshot(ConversationState.IN_PROGRESS, null)));

            String body = stream(bridge());

            assertTrue(body.contains("\"type\":\"" + OpenAiErrorResponse.TYPE_RATE_LIMIT + "\""), body);
            assertEquals(1, count(body, "data: [DONE]"), body);
        }

        @Test
        void stream_truncated_reportsLength() throws Exception {
            givenStreamingEmits(handler -> {
                handler.onToken("cut");
                handler.onComplete(withStepDatum(snapshot(ConversationState.READY, "cut"),
                        MemoryKeys.LLM_FINISH_REASON, "length"));
            });

            String body = stream(bridge());

            assertTrue(body.contains("\"finish_reason\":\"length\""), body);
        }

        @Test
        void stream_timeout_endsWithOneTerminator_andNothingAfterIt() throws Exception {
            // The request thread gives up after request-timeout-seconds; the pipeline
            // keeps running and its callbacks fire afterwards. Before, those late
            // frames were written after [DONE].
            AtomicReference<IConversationService.StreamingResponseHandler> captured = new AtomicReference<>();
            givenStreamingEmits(captured::set);
            var bridge = bridge();
            var turn = bridge.prepare(statelessModel, firstTurn("s", "hi"), noHeaders(), USER_ID);
            var out = new ByteArrayOutputStream();

            bridge.stream(turn, new OpenAiSseWriter(out, objectMapper, "id", "m", 1L, false));
            String atTimeout = out.toString(StandardCharsets.UTF_8);
            captured.get().onToken("late token");
            captured.get().onComplete(snapshot(ConversationState.READY, "late"));
            captured.get().onError(new IllegalStateException("late"));
            String afterwards = out.toString(StandardCharsets.UTF_8);

            assertTrue(atTimeout.contains("\"code\":\"timeout\""), atTimeout);
            assertEquals(1, count(atTimeout, "data: [DONE]"), atTimeout);
            assertEquals(atTimeout, afterwards, "nothing may be written after [DONE]");
        }

        @Test
        void stream_outcomeIsCounted() throws Exception {
            givenStreamingEmits(handler -> handler.onError(new IllegalStateException("x")));

            stream(bridge());

            assertNotNull(meterRegistry.find("eddi.openai.requests").tags("mode", "stream", "outcome", "error").counter());
        }
    }
}
