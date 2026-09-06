/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.openai;

import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.memory.MemoryKeys;
import ai.labs.eddi.engine.memory.model.ConversationOutput;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot;
import ai.labs.eddi.engine.model.Context;
import ai.labs.eddi.engine.model.Deployment.Environment;
import ai.labs.eddi.engine.triggermanagement.IUserConversationStore;
import ai.labs.eddi.engine.triggermanagement.model.UserConversation;
import ai.labs.eddi.integrations.openai.model.ChatCompletionRequest;
import ai.labs.eddi.modules.output.model.QuickReply;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.URI;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import java.io.ByteArrayOutputStream;
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
 * Unit tests for {@link OpenAiConversationBridge}.
 * <p>
 * Two guarantees carry the design and are asserted directly: distinct chat ids
 * must produce distinct conversations (otherwise one user's chat windows share
 * a memory), and a dropped turn must be told apart from a completed one
 * (otherwise a paused conversation replays the previous reply forever).
 */
class OpenAiConversationBridgeTest {

    private static final String CONVERSATION_ID = "66c0ffee0000000000000001";
    private static final String OTHER_CONVERSATION_ID = "66c0ffee0000000000000002";
    private static final String USER_ID = "u_812";

    private IConversationService conversationService;
    private IUserConversationStore userConversationStore;
    private OpenAiConversationBridge bridge;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final AgentModelResolver.ResolvedModel statefulModel = new AgentModelResolver.ResolvedModel(
            AGENT_ID_SUPPORT, Environment.production, "Support", "support-a3f9c1", "support-a3f9c1", 0L, false);
    private final AgentModelResolver.ResolvedModel statelessModel = new AgentModelResolver.ResolvedModel(
            AGENT_ID_SUPPORT, Environment.production, "Support", "support-a3f9c1:stateless", "support-a3f9c1:stateless", 0L, true);

    @BeforeEach
    void setUp() throws Exception {
        conversationService = mock(IConversationService.class);
        userConversationStore = mock(IUserConversationStore.class);

        when(conversationService.startConversation(any(), any(), any(), any()))
                .thenReturn(new IConversationService.ConversationResult(
                        CONVERSATION_ID, URI.create("eddi://conversation/" + CONVERSATION_ID)));
        when(conversationService.getConversationState(any(String.class))).thenReturn(ConversationState.READY);

        bridge = new OpenAiConversationBridge(conversationService, userConversationStore,
                new OpenAiMessageMapper(objectMapper, 5),
                OpenAiTestFixtures.config(b -> b.requestTimeoutSeconds = 2),
                new SimpleMeterRegistry(), permissiveUseGuard());
        bridge.initMetrics();
    }

    private ChatCompletionRequest request(String json) throws Exception {
        return objectMapper.readValue(json, ChatCompletionRequest.class);
    }

    private ChatCompletionRequest simpleRequest() throws Exception {
        return request("""
                {"model":"support-a3f9c1","messages":[{"role":"user","content":"Hi"}]}""");
    }

    private Map<String, String> headers(String chatId) {
        Map<String, String> headers = new HashMap<>();
        if (chatId != null) {
            headers.put(OpenAiAuthFilter.HEADER_CHAT_ID, chatId);
        }
        return headers;
    }

    /** Make the next say() invoke onComplete with the given snapshot. */
    private void givenSayCompletes(SimpleConversationMemorySnapshot snapshot) throws Exception {
        doAnswer(invocation -> {
            IConversationService.ConversationResponseHandler handler = invocation.getArgument(6);
            handler.onComplete(snapshot);
            return null;
        }).when(conversationService).say(any(), anyBoolean(), anyBoolean(), any(), any(), anyBoolean(), any());
    }

    /** Make the next say() invoke onSkipped with the given snapshot. */
    private void givenSaySkipped(SimpleConversationMemorySnapshot snapshot) throws Exception {
        doAnswer(invocation -> {
            IConversationService.ConversationResponseHandler handler = invocation.getArgument(6);
            handler.onSkipped(snapshot);
            return null;
        }).when(conversationService).say(any(), anyBoolean(), anyBoolean(), any(), any(), anyBoolean(), any());
    }

    /** Drive sayStreaming's handler with the given script. */
    private void givenStreamingEmits(Consumer<IConversationService.StreamingResponseHandler> script)
            throws Exception {
        doAnswer(invocation -> {
            script.accept(invocation.getArgument(5));
            return null;
        }).when(conversationService).sayStreaming(any(), anyBoolean(), anyBoolean(), any(), any(), any());
    }

    /** A prepared stateless turn — no store interaction needed. */
    private OpenAiConversationBridge.PreparedTurn statelessTurn() throws Exception {
        return bridge.prepare(statelessModel, simpleRequest(), headers(null), USER_ID);
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int index = haystack.indexOf(needle);
        while (index >= 0) {
            count++;
            index = haystack.indexOf(needle, index + needle.length());
        }
        return count;
    }

    private SimpleConversationMemorySnapshot snapshotWithText(String text, ConversationState state) {
        var snapshot = new SimpleConversationMemorySnapshot();
        snapshot.setConversationId(CONVERSATION_ID);
        snapshot.setConversationState(state);
        var output = new ConversationOutput();
        output.put("output", List.of(text));
        snapshot.setConversationOutputs(List.of(output));
        return snapshot;
    }

    // ─── session keys: the isolation guarantee ───

    @Test
    void chatIdHeaderBecomesTheChatKey() throws Exception {
        assertEquals("chat-4f2b", bridge.resolveChatKey(headers("chat-4f2b"), simpleRequest()));
    }

    @Test
    void chatIdHeaderIsMatchedCaseInsensitively() throws Exception {
        Map<String, String> lowercased = Map.of("x-openwebui-chat-id", "chat-4f2b");

        assertEquals("chat-4f2b", bridge.resolveChatKey(lowercased, simpleRequest()));
    }

    @Test
    void userFieldIsTheFallbackChatKey() throws Exception {
        var request = request("""
                {"model":"m","messages":[{"role":"user","content":"Hi"}],"user":"chat-99"}""");

        assertEquals("chat-99", bridge.resolveChatKey(headers(null), request));
    }

    @Test
    void chatIdHeaderWinsOverUserField() throws Exception {
        var request = request("""
                {"model":"m","messages":[{"role":"user","content":"Hi"}],"user":"chat-99"}""");

        assertEquals("chat-4f2b", bridge.resolveChatKey(headers("chat-4f2b"), request));
    }

    @Test
    void noChatIdentity_yieldsNullKey() throws Exception {
        assertEquals(null, bridge.resolveChatKey(headers(null), simpleRequest()));
    }

    @Test
    void differentChatIds_produceDifferentIntents() {
        String first = bridge.buildIntent(AGENT_ID_SUPPORT, "chat-a");
        String second = bridge.buildIntent(AGENT_ID_SUPPORT, "chat-b");

        assertNotEquals(first, second,
                "two Open WebUI chat windows must not share one EDDI conversation");
        assertTrue(first.startsWith("channel:openai:" + AGENT_ID_SUPPORT + ":"));
    }

    @Test
    void nullChatKey_fallsBackToDefaultSlot() {
        assertEquals("channel:openai:" + AGENT_ID_SUPPORT + ":default",
                bridge.buildIntent(AGENT_ID_SUPPORT, null));
    }

    @Test
    void prepare_isolatesConversationsPerChatId() throws Exception {
        // Two chats, no existing mappings: each must start its own conversation
        // and store its own mapping under its own intent.
        when(userConversationStore.readUserConversation(any(), any())).thenReturn(null);

        bridge.prepare(statefulModel, simpleRequest(), headers("chat-a"), USER_ID);
        bridge.prepare(statefulModel, simpleRequest(), headers("chat-b"), USER_ID);

        ArgumentCaptor<UserConversation> captor = ArgumentCaptor.forClass(UserConversation.class);
        verify(userConversationStore, times(2)).createUserConversation(captor.capture());

        List<UserConversation> mappings = captor.getAllValues();
        assertNotEquals(mappings.get(0).getIntent(), mappings.get(1).getIntent());
        verify(conversationService, times(2)).startConversation(any(), any(), eq(USER_ID), any());
    }

    // ─── conversation reuse ───

    @Test
    void existingActiveMappingIsReused_noNewConversation() throws Exception {
        when(userConversationStore.readUserConversation(any(), any())).thenReturn(new UserConversation(
                "channel:openai:x", USER_ID, Environment.production, AGENT_ID_SUPPORT, CONVERSATION_ID));

        var turn = bridge.prepare(statefulModel, simpleRequest(), headers("chat-a"), USER_ID);

        assertEquals(CONVERSATION_ID, turn.conversationId());
        verify(conversationService, never()).startConversation(any(), any(), any(), any());
    }

    @Test
    void endedMappingIsReplaced_notReused() throws Exception {
        when(userConversationStore.readUserConversation(any(), any())).thenReturn(new UserConversation(
                "channel:openai:x", USER_ID, Environment.production, AGENT_ID_SUPPORT, OTHER_CONVERSATION_ID));
        when(conversationService.getConversationState(OTHER_CONVERSATION_ID)).thenReturn(ConversationState.ENDED);

        var turn = bridge.prepare(statefulModel, simpleRequest(), headers("chat-a"), USER_ID);

        assertEquals(CONVERSATION_ID, turn.conversationId());
        verify(userConversationStore).deleteUserConversation(any(), eq(USER_ID));
        verify(conversationService).startConversation(any(), any(), any(), any());
    }

    @Test
    void unreadableMappedConversationIsTreatedAsStale() throws Exception {
        when(userConversationStore.readUserConversation(any(), any())).thenReturn(new UserConversation(
                "channel:openai:x", USER_ID, Environment.production, AGENT_ID_SUPPORT, OTHER_CONVERSATION_ID));
        when(conversationService.getConversationState(OTHER_CONVERSATION_ID))
                .thenThrow(new RuntimeException("gone"));

        assertEquals(CONVERSATION_ID,
                bridge.prepare(statefulModel, simpleRequest(), headers("chat-a"), USER_ID).conversationId());
    }

    @Test
    void createRace_adoptsTheWinnerAndEndsTheOrphan() throws Exception {
        // Two first messages arrive together. (intent, userId) is uniquely indexed,
        // so one insert loses; that request must adopt the winner's conversation
        // rather than fail, and must not leave its own conversation orphaned.
        when(userConversationStore.readUserConversation(any(), any()))
                .thenReturn(null)
                .thenReturn(new UserConversation("channel:openai:x", USER_ID, Environment.production,
                        AGENT_ID_SUPPORT, OTHER_CONVERSATION_ID));
        doAnswer(invocation -> {
            throw new IResourceStore.ResourceAlreadyExistsException("duplicate");
        }).when(userConversationStore).createUserConversation(any());

        var turn = bridge.prepare(statefulModel, simpleRequest(), headers("chat-a"), USER_ID);

        assertEquals(OTHER_CONVERSATION_ID, turn.conversationId());
        verify(conversationService).endConversation(CONVERSATION_ID);
    }

    @Test
    void createRace_adoptsTheWinner_whateverTheStoreThrows() throws Exception {
        // The Mongo store does not raise ResourceAlreadyExistsException on a
        // duplicate key — it lets a raw MongoWriteException (E11000) out. Catching
        // only the declared type was dead code against the real store, and Open
        // WebUI hits this on every new chat because it fires the completion and
        // its title request concurrently with the same chat id.
        when(userConversationStore.readUserConversation(any(), any()))
                .thenReturn(null)
                .thenReturn(new UserConversation("channel:openai:x", USER_ID, Environment.production,
                        AGENT_ID_SUPPORT, OTHER_CONVERSATION_ID));
        doAnswer(invocation -> {
            throw new RuntimeException("E11000 duplicate key error collection: eddi.userconversations");
        }).when(userConversationStore).createUserConversation(any());

        var turn = bridge.prepare(statefulModel, simpleRequest(), headers("chat-a"), USER_ID);

        assertEquals(OTHER_CONVERSATION_ID, turn.conversationId());
        verify(conversationService).endConversation(CONVERSATION_ID);
    }

    @Test
    void createFailure_withNoWinner_isAServerErrorNotALeakedStackTrace() throws Exception {
        when(userConversationStore.readUserConversation(any(), any())).thenReturn(null);
        doAnswer(invocation -> {
            throw new RuntimeException("datastore unreachable");
        }).when(userConversationStore).createUserConversation(any());

        var exception = assertThrows(OpenAiApiException.class,
                () -> bridge.prepare(statefulModel, simpleRequest(), headers("chat-a"), USER_ID));

        assertEquals(500, exception.getStatus());
        verify(conversationService).endConversation(CONVERSATION_ID);
    }

    // ─── stateless variant ───

    @Test
    void statelessModel_storesNoMapping() throws Exception {
        var turn = bridge.prepare(statelessModel, simpleRequest(), headers("chat-a"), USER_ID);

        assertTrue(turn.stateless());
        verify(userConversationStore, never()).createUserConversation(any());
        verify(userConversationStore, never()).readUserConversation(any(), any());
    }

    @Test
    void statelessTurn_endsTheConversationEvenOnFailure() throws Exception {
        doAnswer(invocation -> {
            throw new IllegalStateException("agent exploded");
        }).when(conversationService).say(any(), anyBoolean(), anyBoolean(), any(), any(), anyBoolean(), any());
        var turn = bridge.prepare(statelessModel, simpleRequest(), headers(null), USER_ID);

        assertThrows(OpenAiApiException.class,
                () -> bridge.say(turn, statelessModel, USER_ID, headers(null), simpleRequest()));

        verify(conversationService).endConversation(CONVERSATION_ID);
    }

    @Test
    void statelessTurn_endsTheConversationOnSuccess() throws Exception {
        givenSayCompletes(snapshotWithText("Title", ConversationState.READY));
        var turn = bridge.prepare(statelessModel, simpleRequest(), headers(null), USER_ID);

        assertEquals("Title", bridge.say(turn, statelessModel, USER_ID, headers(null), simpleRequest()).text());

        verify(conversationService).endConversation(CONVERSATION_ID);
    }

    // ─── HITL discrimination ───

    @Test
    void skippedWhileAwaitingHuman_saysAReviewerMustAct() throws Exception {
        var paused = new SimpleConversationMemorySnapshot();
        paused.setConversationState(ConversationState.AWAITING_HUMAN);
        givenSaySkipped(paused);
        when(userConversationStore.readUserConversation(any(), any())).thenReturn(null);
        var turn = bridge.prepare(statefulModel, simpleRequest(), headers("chat-a"), USER_ID);

        var outcome = bridge.say(turn, statefulModel, USER_ID, headers("chat-a"), simpleRequest());

        assertTrue(outcome.paused());
        assertEquals(OpenAiConversationBridge.STILL_AWAITING_NOTICE, outcome.text());
    }

    @Test
    void skippedWhileBusy_is429_notAPauseNotice() throws Exception {
        var busy = new SimpleConversationMemorySnapshot();
        busy.setConversationState(ConversationState.IN_PROGRESS);
        givenSaySkipped(busy);
        when(userConversationStore.readUserConversation(any(), any())).thenReturn(null);
        var turn = bridge.prepare(statefulModel, simpleRequest(), headers("chat-a"), USER_ID);

        var exception = assertThrows(OpenAiApiException.class,
                () -> bridge.say(turn, statefulModel, USER_ID, headers("chat-a"), simpleRequest()));

        assertEquals(429, exception.getStatus(), "clients back off on 429; a 500 would just surface as an error");
    }

    @Test
    void completedButPaused_returnsOutputPlusNotice_notAnError() throws Exception {
        // A 4xx here would make the client discard the user's message; the pause
        // has to arrive as ordinary assistant text.
        givenSayCompletes(snapshotWithText("Working on it", ConversationState.AWAITING_HUMAN));
        when(userConversationStore.readUserConversation(any(), any())).thenReturn(null);
        var turn = bridge.prepare(statefulModel, simpleRequest(), headers("chat-a"), USER_ID);

        var outcome = bridge.say(turn, statefulModel, USER_ID, headers("chat-a"), simpleRequest());

        assertTrue(outcome.paused());
        assertTrue(outcome.text().startsWith("Working on it"));
        assertTrue(outcome.text().contains(OpenAiConversationBridge.PAUSE_NOTICE_PREFIX));
        assertTrue(outcome.text().contains(CONVERSATION_ID), "the reviewer needs the conversation id");
    }

    @Test
    void pausedWithNoOutput_returnsOnlyTheNotice() {
        var snapshot = new SimpleConversationMemorySnapshot();
        snapshot.setConversationId(CONVERSATION_ID);
        snapshot.setConversationState(ConversationState.AWAITING_HUMAN);

        var outcome = bridge.render(snapshot);

        assertTrue(outcome.paused());
        assertTrue(outcome.text().startsWith(OpenAiConversationBridge.PAUSE_NOTICE_PREFIX));
    }

    // ─── token usage ───

    /** A snapshot whose current step carries the turn's audit token usage. */
    private SimpleConversationMemorySnapshot snapshotWithTokenUsage(Map<String, Object> tokenUsage) {
        var snapshot = snapshotWithText("Hello there", ConversationState.READY);
        var step = new SimpleConversationMemorySnapshot.SimpleConversationStep();
        step.getConversationStep().add(new SimpleConversationMemorySnapshot.ConversationStepData(
                MemoryKeys.AUDIT_TOKEN_USAGE, tokenUsage, new Date(), null));
        snapshot.setConversationSteps(List.of(step));
        return snapshot;
    }

    @Test
    void detailedSnapshotsAreRequested_soTheAuditEntrySurvives() throws Exception {
        // Load-bearing and otherwise invisible: the filtered snapshot keeps only
        // input, actions, output and quick replies, so returnDetailed=false would
        // drop audit:token_usage and usage would silently vanish from every
        // response with nothing failing.
        givenSayCompletes(snapshotWithText("Hi", ConversationState.READY));
        when(userConversationStore.readUserConversation(any(), any())).thenReturn(null);
        var turn = bridge.prepare(statefulModel, simpleRequest(), headers("chat-a"), USER_ID);

        bridge.say(turn, statefulModel, USER_ID, headers("chat-a"), simpleRequest());

        verify(conversationService).say(any(), eq(true), eq(true), any(), any(), anyBoolean(), any());
    }

    @Test
    void streamingAlsoRequestsDetailedSnapshots() throws Exception {
        givenStreamingEmits(handler -> handler.onComplete(snapshotWithText("Hi", ConversationState.READY)));

        bridge.stream(statelessTurn(), new OpenAiSseWriter(new ByteArrayOutputStream(),
                objectMapper, "id", "m", 1L, false));

        verify(conversationService).sayStreaming(any(), eq(true), eq(true), any(), any(), any());
    }

    @Test
    void tokenUsageIsSurfacedFromTheAuditEntry() {
        var outcome = bridge.render(snapshotWithTokenUsage(
                Map.of("inputTokens", 120, "outputTokens", 45, "totalTokens", 165)));

        assertNotNull(outcome.usage());
        assertEquals(120L, outcome.usage().promptTokens());
        assertEquals(45L, outcome.usage().completionTokens());
        assertEquals(165L, outcome.usage().totalTokens());
    }

    @Test
    void missingTotalIsDerivedFromTheParts() {
        // Providers are inconsistent about totalTokens; a usage block whose parts
        // do not add up to its total is worse than one that computes it.
        var outcome = bridge.render(snapshotWithTokenUsage(Map.of("inputTokens", 7, "outputTokens", 3)));

        assertEquals(10L, outcome.usage().totalTokens());
    }

    @Test
    void anAgentThatCalledNoModelReportsNoUsage() {
        // A rule-based agent writes no audit:token_usage entry at all. Reporting
        // zeros would read in a client as a measurement rather than an absence.
        var outcome = bridge.render(snapshotWithText("Hello there", ConversationState.READY));

        assertNull(outcome.usage());
    }

    @Test
    void anEmptyOrUnusableAuditEntryYieldsNoUsage() {
        assertNull(bridge.render(snapshotWithTokenUsage(Map.of())).usage());
        assertNull(bridge.render(snapshotWithTokenUsage(Map.of("somethingElse", 5))).usage());
    }

    @Test
    void skippedTurnsCarryNoUsage() {
        // The sentinels stand for a turn that never ran, so any count would be a
        // fabrication.
        assertNull(bridge.render(OpenAiConversationBridge.SKIPPED_STILL_AWAITING).usage());
    }

    // ─── rendering ───

    @Test
    void quickRepliesReachTheClient() throws Exception {
        // The whole point of the adapter-local renderer: the shared text extractor
        // drops quick replies, which left wizard agents asking questions whose
        // answers were invisible.
        var output = new ConversationOutput();
        output.put("output", List.of("Which provider?"));
        output.put("quickReplies", List.of(new QuickReply("Anthropic", "provider_anthropic", false)));
        var snapshot = new SimpleConversationMemorySnapshot();
        snapshot.setConversationId(CONVERSATION_ID);
        snapshot.setConversationState(ConversationState.READY);
        snapshot.setConversationOutputs(List.of(output));

        var outcome = bridge.render(snapshot);

        assertTrue(outcome.text().startsWith("Which provider?"), outcome.text());
        assertTrue(outcome.text().contains("`Anthropic`"), outcome.text());
    }

    @Test
    void normalCompletion_returnsTheAgentText() throws Exception {
        givenSayCompletes(snapshotWithText("Hello there", ConversationState.READY));
        when(userConversationStore.readUserConversation(any(), any())).thenReturn(null);
        var turn = bridge.prepare(statefulModel, simpleRequest(), headers("chat-a"), USER_ID);

        var outcome = bridge.say(turn, statefulModel, USER_ID, headers("chat-a"), simpleRequest());

        assertEquals("Hello there", outcome.text());
        assertFalse(outcome.paused());
    }

    @Test
    void emptyOutput_returnsAPlaceholder_notAnEmptyMessage() {
        var snapshot = new SimpleConversationMemorySnapshot();
        snapshot.setConversationState(ConversationState.READY);

        assertEquals(OpenAiConversationBridge.NO_OUTPUT_NOTICE, bridge.render(snapshot).text());
    }

    // ─── self-heal ───

    @Test
    void endedConversationMidTurn_retriesOnceOnAFreshConversation() throws Exception {
        when(userConversationStore.readUserConversation(any(), any())).thenReturn(null);
        var turn = bridge.prepare(statefulModel, simpleRequest(), headers("chat-a"), USER_ID);

        var successSnapshot = snapshotWithText("recovered", ConversationState.READY);
        doAnswer(invocation -> {
            throw new IConversationService.ConversationEndedException("ended");
        }).doAnswer(invocation -> {
            IConversationService.ConversationResponseHandler handler = invocation.getArgument(6);
            handler.onComplete(successSnapshot);
            return null;
        }).when(conversationService).say(any(), anyBoolean(), anyBoolean(), any(), any(), anyBoolean(), any());

        var outcome = bridge.say(turn, statefulModel, USER_ID, headers("chat-a"), simpleRequest());

        assertEquals("recovered", outcome.text());
        verify(conversationService, times(2)).say(any(), anyBoolean(), anyBoolean(), any(), any(), anyBoolean(), any());
    }

    @Test
    void endedConversationTwice_failsInsteadOfLooping() throws Exception {
        when(userConversationStore.readUserConversation(any(), any())).thenReturn(null);
        var turn = bridge.prepare(statefulModel, simpleRequest(), headers("chat-a"), USER_ID);

        doAnswer(invocation -> {
            throw new IConversationService.ConversationEndedException("ended");
        }).when(conversationService).say(any(), anyBoolean(), anyBoolean(), any(), any(), anyBoolean(), any());

        assertThrows(OpenAiApiException.class,
                () -> bridge.say(turn, statefulModel, USER_ID, headers("chat-a"), simpleRequest()));

        verify(conversationService, times(2)).say(any(), anyBoolean(), anyBoolean(), any(), any(), anyBoolean(), any());
    }

    // ─── streaming ───

    @Test
    void stream_emitsSnapshotTextOnlyWhenNoTokenArrived() throws Exception {
        // Rule-based agents produce text at onComplete, not as tokens.
        givenStreamingEmits(handler -> handler.onComplete(snapshotWithText("block reply", ConversationState.READY)));
        var turn = statelessTurn();
        var out = new ByteArrayOutputStream();

        bridge.stream(turn, new OpenAiSseWriter(out, objectMapper, "id", "m", 1L, false));

        assertTrue(out.toString(java.nio.charset.StandardCharsets.UTF_8).contains("block reply"));
    }

    @Test
    void stream_doesNotRepeatSnapshotTextAfterTokens() throws Exception {
        // Emitting both would deliver the whole reply twice.
        givenStreamingEmits(handler -> {
            handler.onToken("stream");
            handler.onToken("ed");
            handler.onComplete(snapshotWithText("streamed", ConversationState.READY));
        });
        var turn = statelessTurn();
        var out = new ByteArrayOutputStream();

        bridge.stream(turn, new OpenAiSseWriter(out, objectMapper, "id", "m", 1L, false));

        String body = out.toString(java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(1, countOccurrences(body, "\"content\":\"stream\""));
        assertFalse(body.contains("\"content\":\"streamed\""), "the snapshot text must not follow the tokens");
    }

    @Test
    void stream_errorWithNullMessage_doesNotWriteTheWordNull() throws Exception {
        // NPEs and similar carry a null message; "⚠️ null" is what the user would
        // otherwise read.
        givenStreamingEmits(handler -> handler.onError(new NullPointerException()));
        var turn = statelessTurn();
        var out = new ByteArrayOutputStream();

        bridge.stream(turn, new OpenAiSseWriter(out, objectMapper, "id", "m", 1L, false));

        String body = out.toString(java.nio.charset.StandardCharsets.UTF_8);
        assertFalse(body.contains("null"), "got: " + body);
        assertTrue(body.contains("NullPointerException"));
    }

    @Test
    void stream_waitsForAnAsynchronousTurnBeforeTerminating() throws Exception {
        // sayStreaming hands the turn to the ConversationCoordinator and returns
        // immediately; the callbacks fire later on another thread. Every other
        // test here drives the handler synchronously, which hid a real defect:
        // the terminator was written as soon as sayStreaming returned, so the
        // response closed mid-turn and content arrived after [DONE].
        doAnswer(invocation -> {
            IConversationService.StreamingResponseHandler handler = invocation.getArgument(5);
            Thread worker = new Thread(() -> {
                try {
                    Thread.sleep(120);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                handler.onToken("late");
                handler.onComplete(snapshotWithText("late", ConversationState.READY));
            });
            worker.setDaemon(true);
            worker.start();
            return null;
        }).when(conversationService).sayStreaming(any(), anyBoolean(), anyBoolean(), any(), any(), any());

        var turn = statelessTurn();
        var out = new ByteArrayOutputStream();

        bridge.stream(turn, new OpenAiSseWriter(out, objectMapper, "id", "m", 1L, false));

        String body = out.toString(java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(body.contains("\"content\":\"late\""),
                "the token arrived after sayStreaming returned and must still be sent: " + body);
        assertTrue(body.trim().endsWith("data: [DONE]"),
                "nothing may follow the terminator: " + body);
    }

    @Test
    void stream_alwaysTerminates_evenWhenTheHandlerNeverFires() throws Exception {
        givenStreamingEmits(handler -> {
            // Deliberately silent: an undocumented path must not hang the client.
        });
        var turn = statelessTurn();
        var out = new ByteArrayOutputStream();

        bridge.stream(turn, new OpenAiSseWriter(out, objectMapper, "id", "m", 1L, false));

        assertTrue(out.toString(java.nio.charset.StandardCharsets.UTF_8).endsWith("data: [DONE]\n\n"));
    }

    // ─── request validation ───

    @Test
    void requestWithoutUserMessage_is400() throws Exception {
        var request = request("""
                {"model":"support-a3f9c1","messages":[{"role":"system","content":"be nice"}]}""");

        var exception = assertThrows(OpenAiApiException.class,
                () -> bridge.prepare(statefulModel, request, headers("chat-a"), USER_ID));

        assertEquals(400, exception.getStatus());
        verify(conversationService, never()).startConversation(any(), any(), any(), any());
    }

    @Test
    void agentNotReady_is503() throws Exception {
        when(conversationService.startConversation(any(), any(), any(), any()))
                .thenThrow(new IConversationService.AgentNotReadyException("not deployed"));
        when(userConversationStore.readUserConversation(any(), any())).thenReturn(null);

        var exception = assertThrows(OpenAiApiException.class,
                () -> bridge.prepare(statefulModel, simpleRequest(), headers("chat-a"), USER_ID));

        assertEquals(503, exception.getStatus());
    }

    @Test
    void channelIntentIsRecordedOnTheConversation() throws Exception {
        when(userConversationStore.readUserConversation(any(), any())).thenReturn(null);

        bridge.prepare(statefulModel, simpleRequest(), headers("chat-a"), USER_ID);

        ArgumentCaptor<Map<String, Context>> captor = ArgumentCaptor.forClass(Map.class);
        verify(conversationService).startConversation(any(), any(), any(), captor.capture());
        assertTrue(captor.getValue().containsKey(OpenAiConversationBridge.CONTEXT_CHANNEL_INTENT));
    }

    /**
     * A guard that admits every agent: a bare mock's void requireAgentUseAccess
     * does nothing. The /v1 USE gate has its own tests.
     */
    private static ResourceAccessGuard permissiveUseGuard() {
        return mock(ResourceAccessGuard.class);
    }
}
