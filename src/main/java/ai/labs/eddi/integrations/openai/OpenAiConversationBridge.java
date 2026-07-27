/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.openai;

import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.lifecycle.TaskId;
import ai.labs.eddi.engine.memory.ConversationOutputExtractor;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot;
import ai.labs.eddi.engine.model.Context;
import ai.labs.eddi.engine.model.InputData;
import ai.labs.eddi.engine.triggermanagement.IUserConversationStore;
import ai.labs.eddi.engine.triggermanagement.model.UserConversation;
import ai.labs.eddi.integrations.openai.model.ChatCompletionRequest;
import ai.labs.eddi.integrations.openai.model.Choice;
import ai.labs.eddi.integrations.openai.model.OpenAiErrorResponse;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * Bridges the stateless OpenAI protocol onto EDDI's stateful conversations.
 * <p>
 * <b>Session mapping.</b> A conversation is keyed by {@code (intent, userId)}
 * in {@link IUserConversationStore}, exactly as the Slack integration does. The
 * intent is {@code channel:openai:<agentId>:<chatKey>}, where {@code chatKey}
 * comes from {@code X-OpenWebUI-Chat-Id} — the header Open WebUI forwards when
 * {@code ENABLE_FORWARD_USER_INFO_HEADERS} is set. That is what gives each Open
 * WebUI chat window its own EDDI conversation; without it every window a user
 * opens against one agent would share a single conversation and its memory.
 * <p>
 * <b>There is no "new chat" heuristic.</b> Inferring a restart from the message
 * count is both unreliable (regenerate and edit-and-resend look identical to a
 * first message) and destructive, since ending a conversation discards its
 * memory irrecoverably. A new chat produces a new chat key, which is a new
 * intent, which is a new conversation — no inference required.
 *
 * @since 6.1.0
 */
@ApplicationScoped
public class OpenAiConversationBridge {

    private static final Logger LOGGER = Logger.getLogger(OpenAiConversationBridge.class);

    /** Intent prefix, namespacing these mappings against other channels. */
    static final String INTENT_PREFIX = "channel:openai:";

    /** Chat-key stand-in when the client sends no chat or user identifier. */
    static final String DEFAULT_CHAT_KEY = "default";

    /** Context key recording the originating intent on the conversation. */
    static final String CONTEXT_CHANNEL_INTENT = "channelIntent";

    /**
     * Sentinel for a turn dropped because the conversation is still awaiting a
     * human decision. Distinguished from {@link #SKIPPED_NOT_ACTIVE} because the
     * two need opposite messages: one says "a reviewer must act", the other says
     * "try again".
     */
    static final SimpleConversationMemorySnapshot SKIPPED_STILL_AWAITING = new SimpleConversationMemorySnapshot();

    /** Sentinel for a turn dropped because the conversation is busy or ended. */
    static final SimpleConversationMemorySnapshot SKIPPED_NOT_ACTIVE = new SimpleConversationMemorySnapshot();

    static final String PAUSE_NOTICE_PREFIX = "⏸️ Awaiting human approval.";
    static final String STILL_AWAITING_NOTICE = "⏸️ Still awaiting approval — a reviewer must decide before I can continue.";
    static final String NO_OUTPUT_NOTICE = "_The agent produced no text output._";

    private final IConversationService conversationService;
    private final IUserConversationStore userConversationStore;
    private final OpenAiMessageMapper messageMapper;
    private final OpenAiCompatConfig config;
    private final MeterRegistry meterRegistry;

    private Counter conversationsCreated;
    private Timer turnTimer;

    @Inject
    public OpenAiConversationBridge(IConversationService conversationService,
            IUserConversationStore userConversationStore,
            OpenAiMessageMapper messageMapper,
            OpenAiCompatConfig config,
            MeterRegistry meterRegistry) {
        this.conversationService = conversationService;
        this.userConversationStore = userConversationStore;
        this.messageMapper = messageMapper;
        this.config = config;
        this.meterRegistry = meterRegistry;
    }

    @PostConstruct
    void initMetrics() {
        conversationsCreated = meterRegistry.counter("eddi.openai.conversations.created");
        turnTimer = meterRegistry.timer("eddi.openai.request.duration");
    }

    /** One prepared turn: which conversation to talk to, and what to say. */
    public record PreparedTurn(String conversationId, InputData inputData, boolean stateless) {
    }

    /**
     * The rendered outcome of a turn.
     *
     * @param paused
     *            whether the conversation is now (or still) awaiting a human
     *            decision — surfaced as chat text, never as an HTTP error, because
     *            a 4xx makes clients discard the user's message
     */
    public record TurnOutcome(String text, boolean paused) {
    }

    // ─── session keys ───

    /**
     * The chat/session key: which conversation this request belongs to.
     * <p>
     * {@code X-OpenWebUI-Chat-Id} first, then the OpenAI {@code user} field for
     * clients that set it. {@code null} means the caller offered no chat identity,
     * in which case all of that user's requests to this agent share one
     * conversation.
     */
    String resolveChatKey(Map<String, String> headers, ChatCompletionRequest request) {
        String header = headerValue(headers, OpenAiAuthFilter.HEADER_CHAT_ID);
        if (header != null) {
            return header;
        }
        String user = request == null ? null : request.userAsString();
        return user == null || user.isBlank() ? null : user.trim();
    }

    /** The {@link IUserConversationStore} intent for an agent and chat key. */
    String buildIntent(String agentId, String chatKey) {
        return INTENT_PREFIX + agentId + ":" + (chatKey == null ? DEFAULT_CHAT_KEY : chatKey);
    }

    // ─── turn preparation ───

    /**
     * Resolve the conversation and build the input for one turn.
     *
     * @throws OpenAiApiException
     *             when the request cannot be served at all
     */
    public PreparedTurn prepare(AgentModelResolver.ResolvedModel model,
                                ChatCompletionRequest request,
                                Map<String, String> headers,
                                String userId) {
        InputData inputData;
        try {
            inputData = messageMapper.toInputData(request);
        } catch (OpenAiMessageMapper.NoUserMessageException e) {
            throw OpenAiApiException.badRequest(OpenAiErrorResponse.CODE_NO_USER_MESSAGE, e.getMessage());
        }

        String chatKey = resolveChatKey(headers, request);
        String intent = buildIntent(model.agentId(), chatKey);

        String conversationId = model.stateless()
                ? startConversation(model, userId, intent)
                : getOrCreateConversation(model, userId, intent);

        return new PreparedTurn(conversationId, inputData, model.stateless());
    }

    /**
     * Resolve the mapped conversation, creating one when absent or unusable.
     * Mirrors {@code SlackEventHandler.getOrCreateConversation}.
     */
    String getOrCreateConversation(AgentModelResolver.ResolvedModel model, String userId, String intent) {
        UserConversation existing = readMapping(intent, userId);
        if (existing != null) {
            if (isUsable(existing.getConversationId())) {
                return existing.getConversationId();
            }
            // Ended or vanished — drop the stale mapping and start over rather
            // than failing every subsequent message in this chat.
            deleteMapping(intent, userId);
        }

        String conversationId = startConversation(model, userId, intent);
        try {
            userConversationStore.createUserConversation(new UserConversation(
                    intent, userId, model.environment(), model.agentId(), conversationId));
        } catch (IResourceStore.ResourceAlreadyExistsException e) {
            // Two first messages raced. (intent, userId) is uniquely indexed, so
            // exactly one won; adopt the winner and end the conversation we just
            // created so it is not orphaned.
            UserConversation winner = readMapping(intent, userId);
            endQuietly(conversationId);
            if (winner != null) {
                return winner.getConversationId();
            }
            throw OpenAiApiException.serverError(null,
                    "Could not establish a conversation for this chat. Please retry.");
        } catch (IResourceStore.ResourceStoreException e) {
            endQuietly(conversationId);
            throw OpenAiApiException.serverError(null,
                    "Could not persist the conversation mapping: " + e.getMessage());
        }
        return conversationId;
    }

    private String startConversation(AgentModelResolver.ResolvedModel model, String userId, String intent) {
        try {
            var result = conversationService.startConversation(model.environment(), model.agentId(), userId,
                    Map.of(CONTEXT_CHANNEL_INTENT, new Context(Context.ContextType.string, intent)));
            conversationsCreated.increment();
            return result.conversationId();
        } catch (IConversationService.AgentNotReadyException e) {
            throw OpenAiApiException.unavailable(OpenAiErrorResponse.CODE_AGENT_NOT_READY,
                    "Agent '" + model.displayName() + "' is not ready: " + e.getMessage());
        } catch (Exception e) {
            LOGGER.errorf("Could not start an OpenAI-adapter conversation for agent %s: %s",
                    sanitize(model.agentId()), e.getMessage());
            throw OpenAiApiException.serverError(null, "Could not start a conversation: " + e.getMessage());
        }
    }

    // ─── turn execution ───

    /**
     * Run one non-streaming turn and render its outcome.
     * <p>
     * A conversation that ended between mapping and send is retried <b>once</b>
     * against a fresh conversation — a bounded self-heal, so a permanently broken
     * agent cannot spin.
     */
    public TurnOutcome say(PreparedTurn turn, AgentModelResolver.ResolvedModel model,
                           String userId, Map<String, String> headers, ChatCompletionRequest request) {
        long start = System.nanoTime();
        try {
            return render(sendAndWait(turn.conversationId(), turn.inputData()));
        } catch (IConversationService.ConversationEndedException e) {
            if (turn.stateless()) {
                throw OpenAiApiException.serverError(null, "The conversation ended before it could be used.");
            }
            String intent = buildIntent(model.agentId(), resolveChatKey(headers, request));
            deleteMapping(intent, userId);
            String fresh = getOrCreateConversation(model, userId, intent);
            try {
                return render(sendAndWait(fresh, turn.inputData()));
            } catch (Exception retryFailure) {
                throw asApiException(retryFailure);
            }
        } catch (Exception e) {
            throw asApiException(e);
        } finally {
            if (turn.stateless()) {
                endQuietly(turn.conversationId());
            }
            turnTimer.record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
        }
    }

    /**
     * Send input and block for the turn, mapping a dropped turn onto a sentinel.
     * <p>
     * {@code onSkipped} means the input was <em>not consumed</em>: the turn was
     * dropped because the conversation was paused, busy or ended when it reached
     * the front of the queue. Delivering the accompanying snapshot as if it were
     * this turn's answer would replay the previous turn's reply.
     */
    SimpleConversationMemorySnapshot sendAndWait(String conversationId, InputData inputData) throws Exception {
        var future = new CompletableFuture<SimpleConversationMemorySnapshot>();

        conversationService.say(conversationId, false, true, Collections.emptyList(), inputData, false,
                new IConversationService.ConversationResponseHandler() {
                    @Override
                    public void onComplete(SimpleConversationMemorySnapshot snapshot) {
                        if (snapshot != null) {
                            future.complete(snapshot);
                        } else {
                            future.completeExceptionally(new IllegalStateException("Agent returned no snapshot"));
                        }
                    }

                    @Override
                    public void onSkipped(SimpleConversationMemorySnapshot snapshot) {
                        boolean stillAwaiting = snapshot != null
                                && snapshot.getConversationState() == ConversationState.AWAITING_HUMAN;
                        future.complete(stillAwaiting ? SKIPPED_STILL_AWAITING : SKIPPED_NOT_ACTIVE);
                    }
                });

        try {
            return future.get(config.getRequestTimeoutSeconds(), TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            throw OpenAiApiException.timeout("The agent did not respond within "
                    + config.getRequestTimeoutSeconds() + " seconds.");
        }
    }

    /** Render a snapshot (or sentinel) as assistant text. */
    TurnOutcome render(SimpleConversationMemorySnapshot snapshot) {
        if (snapshot == SKIPPED_STILL_AWAITING) {
            return new TurnOutcome(STILL_AWAITING_NOTICE, true);
        }
        if (snapshot == SKIPPED_NOT_ACTIVE) {
            throw OpenAiApiException.busy(
                    "The conversation is busy with another turn or is no longer active. Please retry.");
        }

        String text = ConversationOutputExtractor.extractResponse(snapshot);
        boolean paused = snapshot != null
                && snapshot.getConversationState() == ConversationState.AWAITING_HUMAN;

        if (paused) {
            String notice = PAUSE_NOTICE_PREFIX + " Conversation: " + snapshot.getConversationId();
            return new TurnOutcome(text == null || text.isBlank() ? notice : text + "\n\n" + notice, true);
        }
        return new TurnOutcome(text == null || text.isBlank() ? NO_OUTPUT_NOTICE : text, false);
    }

    /**
     * Run one streaming turn, writing OpenAI chunks through {@code writer}.
     * <p>
     * <b>Token reconciliation.</b> Not every agent streams tokens: {@code LlmTask}
     * does, but a purely rule-based agent produces its text only at
     * {@code onComplete} via the output task. So the final snapshot's text is
     * emitted <em>only</em> when no token arrived — otherwise the whole reply would
     * be sent twice, once as tokens and once as a block.
     * <p>
     * The stream always terminates. Once headers are flushed the status is fixed at
     * 200, so failures surface as a marked content delta rather than an HTTP error;
     * a stream that simply stops looks to the client like a hang.
     */
    public void stream(PreparedTurn turn, OpenAiSseWriter writer) {
        long start = System.nanoTime();
        boolean[] anyToken = {false};
        try {
            conversationService.sayStreaming(turn.conversationId(), false, true, Collections.emptyList(),
                    turn.inputData(), new IConversationService.StreamingResponseHandler() {
                        @Override
                        public void onTaskStart(TaskId taskId, String taskType, int index) {
                            // Pipeline progress is not part of the OpenAI protocol.
                        }

                        @Override
                        public void onTaskComplete(TaskId taskId, String taskType,
                                                   long durationMs, Map<String, Object> summary) {
                            // Suppressed — see onTaskStart.
                        }

                        @Override
                        public void onToken(String token) {
                            anyToken[0] = true;
                            writer.content(token);
                        }

                        @Override
                        public void onComplete(SimpleConversationMemorySnapshot snapshot) {
                            if (!anyToken[0]) {
                                String text = ConversationOutputExtractor.extractResponse(snapshot);
                                writer.content(text == null || text.isBlank() ? NO_OUTPUT_NOTICE : text);
                            }
                            if (snapshot != null
                                    && snapshot.getConversationState() == ConversationState.AWAITING_HUMAN) {
                                writer.content("\n\n" + PAUSE_NOTICE_PREFIX
                                        + " Conversation: " + snapshot.getConversationId());
                            }
                            writer.finish(Choice.FINISH_STOP);
                        }

                        @Override
                        public void onSkipped(SimpleConversationMemorySnapshot snapshot) {
                            boolean stillAwaiting = snapshot != null
                                    && snapshot.getConversationState() == ConversationState.AWAITING_HUMAN;
                            writer.content(stillAwaiting
                                    ? STILL_AWAITING_NOTICE
                                    : "⚠️ The conversation is busy with another turn or is no longer active. "
                                            + "Please retry.");
                            writer.finish(Choice.FINISH_STOP);
                        }

                        @Override
                        public void onError(Throwable error) {
                            LOGGER.errorf("OpenAI adapter stream failed for conversation %s: %s",
                                    sanitize(turn.conversationId()), error.getMessage());
                            writer.content("\n\n⚠️ " + error.getMessage());
                            writer.finish(Choice.FINISH_STOP);
                        }
                    });
        } catch (Exception e) {
            OpenAiApiException apiException = asApiException(e);
            writer.content("⚠️ " + apiException.getMessage());
        } finally {
            // Belt and braces: the handler terminates on every documented path,
            // but an undocumented one must not leave the client waiting forever.
            writer.finish(Choice.FINISH_STOP);
            if (turn.stateless()) {
                endQuietly(turn.conversationId());
            }
            turnTimer.record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
        }
    }

    /** Translate a turn failure into the OpenAI error envelope. */
    OpenAiApiException asApiException(Exception e) {
        if (e instanceof OpenAiApiException apiException) {
            return apiException;
        }
        if (e instanceof IConversationService.ConversationAwaitingApprovalException) {
            // Reachable when the pause is already persisted at submit time.
            return OpenAiApiException.busy(STILL_AWAITING_NOTICE);
        }
        if (e instanceof IConversationService.ConversationEndedException) {
            return OpenAiApiException.busy("The conversation has ended. Start a new chat.");
        }
        if (e instanceof IResourceStore.ResourceNotFoundException) {
            return OpenAiApiException.notFound(null, "The conversation no longer exists.");
        }
        if (e.getCause() instanceof OpenAiApiException causeException) {
            return causeException;
        }
        LOGGER.errorf("OpenAI adapter turn failed: %s", e.getMessage());
        return OpenAiApiException.serverError(null, "The agent could not process the message: " + e.getMessage());
    }

    // ─── store helpers ───

    private boolean isUsable(String conversationId) {
        try {
            ConversationState state = conversationService.getConversationState(conversationId);
            return state != null && state != ConversationState.ENDED;
        } catch (Exception e) {
            LOGGER.debugf("Mapped conversation %s is unreadable, treating as stale: %s",
                    sanitize(conversationId), e.getMessage());
            return false;
        }
    }

    private UserConversation readMapping(String intent, String userId) {
        try {
            return userConversationStore.readUserConversation(intent, userId);
        } catch (IResourceStore.ResourceStoreException e) {
            LOGGER.warnf("Could not read the conversation mapping for %s: %s", sanitize(intent), e.getMessage());
            return null;
        }
    }

    private void deleteMapping(String intent, String userId) {
        try {
            userConversationStore.deleteUserConversation(intent, userId);
        } catch (IResourceStore.ResourceStoreException e) {
            LOGGER.warnf("Could not delete the stale conversation mapping for %s: %s",
                    sanitize(intent), e.getMessage());
        }
    }

    /**
     * End a conversation without letting the failure mask the real outcome — this
     * runs on cleanup paths where an exception would replace a good answer with a
     * spurious error.
     */
    void endQuietly(String conversationId) {
        try {
            conversationService.endConversation(conversationId);
        } catch (Exception e) {
            LOGGER.debugf("Could not end conversation %s: %s", sanitize(conversationId), e.getMessage());
        }
    }

    private static String headerValue(Map<String, String> headers, String name) {
        if (headers == null) {
            return null;
        }
        String value = headers.get(name);
        if (value == null) {
            // Header names are case-insensitive on the wire.
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(name)) {
                    value = entry.getValue();
                    break;
                }
            }
        }
        return value == null || value.isBlank() ? null : value.trim();
    }
}
