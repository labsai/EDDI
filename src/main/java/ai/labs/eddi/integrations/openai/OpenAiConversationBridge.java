/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.openai;

import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import ai.labs.eddi.configs.properties.IUserMemoryStore;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.lifecycle.TaskId;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.memory.MemoryKeys;
import ai.labs.eddi.engine.memory.model.ConversationListingSummary;
import ai.labs.eddi.engine.memory.model.ConversationOutput;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot;
import ai.labs.eddi.engine.model.Context;
import ai.labs.eddi.engine.model.InputData;
import ai.labs.eddi.engine.triggermanagement.IUserConversationStore;
import ai.labs.eddi.engine.triggermanagement.model.UserConversation;
import ai.labs.eddi.integrations.openai.model.ChatCompletionRequest;
import ai.labs.eddi.integrations.openai.model.ChatMessage;
import ai.labs.eddi.integrations.openai.model.Choice;
import ai.labs.eddi.integrations.openai.model.OpenAiErrorResponse;
import ai.labs.eddi.integrations.openai.model.TokenUsage;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;
import static ai.labs.eddi.utils.RuntimeUtilities.isNullOrEmpty;

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
 * <b>Clients that name no chat.</b> The OpenAI SDKs, LangChain and LiteLLM send
 * no chat id. Keying all of their requests to one {@code default} conversation
 * made every chat a user ever opened against an agent share one memory and one
 * ever-growing document. Such a request is now keyed by a hash of its opening
 * messages (first system and first user message) — which an OpenAI client
 * resends unchanged on every turn of a chat — so each client-side chat maps to
 * its own conversation (see {@link #resolveSessionKey}). A history-keyed
 * request that carries no history is the first turn of a chat, so it always
 * starts a fresh conversation, even when an earlier chat opened with the same
 * words. {@code eddi.openai-compat.chat-key-fallback=shared} restores the
 * single conversation.
 * <p>
 * An explicit key is never second-guessed: a new chat produces a new chat key,
 * which is a new intent, which is a new conversation. Nothing is ever ended to
 * make room — a superseded conversation is abandoned, not destroyed.
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

    /**
     * Request header carrying an explicit chat key for clients other than Open
     * WebUI — the generic counterpart of {@code X-OpenWebUI-Chat-Id}.
     */
    public static final String HEADER_EDDI_CHAT_ID = "X-EDDI-Chat-Id";

    /** Prefix of a chat key derived from the request's opening messages. */
    static final String HISTORY_KEY_PREFIX = "h:";

    /** Prefix of an explicit chat key too long to store verbatim. */
    static final String HASHED_KEY_PREFIX = "k:";

    /** Explicit keys longer than this are stored as their hash. */
    static final int MAX_EXPLICIT_KEY_LENGTH = 128;

    /** Where a request's chat key came from — the {@code source} metric tag. */
    static final String SOURCE_OPENWEBUI_HEADER = "openwebui_header";
    static final String SOURCE_EDDI_HEADER = "eddi_header";
    static final String SOURCE_METADATA = "metadata";
    static final String SOURCE_USER = "user";
    static final String SOURCE_HISTORY = "history";
    static final String SOURCE_SHARED = "shared";

    /** States in which a conversation is idle, so a step rollover may end it. */
    private static final Set<ConversationState> ROLLOVER_STATES = EnumSet.of(ConversationState.READY,
            ConversationState.ERROR, ConversationState.EXECUTION_INTERRUPTED);

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
    /**
     * The client-facing message for a failure whose reason must not leave the
     * server.
     */
    static final String AGENT_FAILED_MESSAGE = "The agent failed to process the message.";
    static final String BUSY_NOTICE = "The conversation is busy with another turn or is no longer active. Please retry.";

    private final IConversationService conversationService;
    private final IUserConversationStore userConversationStore;
    /**
     * Retained but intentionally NOT used to migrate memories out of the shared
     * bare-id namespace (Finding A): a leaked /v1 key lets the caller pick the raw
     * id, so any such move could relocate and erase another source's (e.g. an OIDC
     * principal's) memories. Kept so the no-migration invariant is enforceable in
     * tests and for a future workspace-safe migration.
     */
    @SuppressWarnings("unused")
    private final IUserMemoryStore userMemoryStore;
    private final OpenAiMessageMapper messageMapper;
    private final OpenAiCompatConfig config;
    private final MeterRegistry meterRegistry;
    private final IConversationMemoryStore conversationMemoryStore;

    private Counter conversationsCreated;
    private Counter conversationsRolledOver;
    private Timer turnTimer;

    private final ResourceAccessGuard resourceAccessGuard;

    @Inject
    public OpenAiConversationBridge(IConversationService conversationService,
            IUserConversationStore userConversationStore,
            IUserMemoryStore userMemoryStore,
            OpenAiMessageMapper messageMapper,
            OpenAiCompatConfig config,
            MeterRegistry meterRegistry,
            ResourceAccessGuard resourceAccessGuard,
            IConversationMemoryStore conversationMemoryStore) {
        this.resourceAccessGuard = resourceAccessGuard;
        this.conversationMemoryStore = conversationMemoryStore;
        this.conversationService = conversationService;
        this.userConversationStore = userConversationStore;
        this.userMemoryStore = userMemoryStore;
        this.messageMapper = messageMapper;
        this.config = config;
        this.meterRegistry = meterRegistry;
    }

    @PostConstruct
    void initMetrics() {
        conversationsCreated = meterRegistry.counter("eddi.openai.conversations.created");
        conversationsRolledOver = meterRegistry.counter("eddi.openai.conversations.rolled_over");
        turnTimer = meterRegistry.timer("eddi.openai.request.duration");
    }

    /**
     * Count one finished turn. {@code outcome} separates the states an operator
     * needs to tell apart — a wave of {@code paused} means reviewers are behind, a
     * wave of {@code busy} means clients are racing themselves, and neither is an
     * error.
     */
    private void countTurn(String mode, String outcome) {
        meterRegistry.counter("eddi.openai.requests", "mode", mode, "outcome", outcome).increment();
    }

    /** Count one request by where its chat key came from. */
    private void countChatKey(String source) {
        meterRegistry.counter("eddi.openai.chat_keys", "source", source).increment();
    }

    /**
     * One prepared turn: which conversation to talk to, and what to say.
     *
     * @param intent
     *            the conversation-mapping intent the turn was resolved under, so a
     *            retry re-resolves the same chat; {@code null} for a stateless turn
     */
    public record PreparedTurn(String conversationId, InputData inputData, boolean stateless, String intent) {
        public PreparedTurn(String conversationId, InputData inputData, boolean stateless) {
            this(conversationId, inputData, stateless, null);
        }
    }

    /**
     * A resolved chat key.
     *
     * @param key
     *            the key, or {@code null} for the shared per-user conversation
     * @param source
     *            where it came from — one of the {@code SOURCE_*} constants
     */
    record ChatKey(String key, String source) {
    }

    /**
     * The rendered outcome of a turn.
     *
     * @param paused
     *            whether the conversation is now (or still) awaiting a human
     *            decision — surfaced as chat text, never as an HTTP error, because
     *            a 4xx makes clients discard the user's message
     * @param usage
     *            the turn's token counts, or {@code null} when the agent called no
     *            model
     */
    public record TurnOutcome(String text, boolean paused, TokenUsage usage, String finishReason) {
        public TurnOutcome(String text, boolean paused, TokenUsage usage) {
            this(text, paused, usage, Choice.FINISH_STOP);
        }
    }

    // ─── session keys ───

    /**
     * The explicit chat key: which conversation the caller says this request
     * belongs to, or {@code null} when it names none.
     * <p>
     * In order: {@code X-OpenWebUI-Chat-Id}, {@code X-EDDI-Chat-Id},
     * {@code metadata.chat_id}, then the OpenAI {@code user} field for clients that
     * set it.
     */
    String resolveChatKey(Map<String, String> headers, ChatCompletionRequest request) {
        ChatKey explicit = explicitChatKey(headers, request);
        return explicit == null ? null : explicit.key();
    }

    private ChatKey explicitChatKey(Map<String, String> headers, ChatCompletionRequest request) {
        String openWebUi = headerValue(headers, OpenAiAuthFilter.HEADER_CHAT_ID);
        if (openWebUi != null) {
            return new ChatKey(boundExplicitKey(openWebUi), SOURCE_OPENWEBUI_HEADER);
        }
        String eddiHeader = headerValue(headers, HEADER_EDDI_CHAT_ID);
        if (eddiHeader != null) {
            return new ChatKey(boundExplicitKey(eddiHeader), SOURCE_EDDI_HEADER);
        }
        String metadata = request == null ? null : request.metadataChatId();
        if (metadata != null) {
            return new ChatKey(boundExplicitKey(metadata), SOURCE_METADATA);
        }
        String user = request == null ? null : request.userAsString();
        if (user != null && !user.isBlank()) {
            return new ChatKey(boundExplicitKey(user.trim()), SOURCE_USER);
        }
        return null;
    }

    /**
     * The chat key a request is mapped by, and where it came from.
     * <p>
     * An explicit key ({@link #resolveChatKey}) wins. Without one, and with
     * {@code chat-key-fallback=history} (the default), the key is a hash of the
     * request's first system message and first user message: an OpenAI client
     * resends its whole history every turn, so these two are the same on every turn
     * of one chat and differ between chats that open differently. With
     * {@code chat-key-fallback=shared} — or a request with no user message at all —
     * the key is {@code null}: one conversation per user and agent.
     */
    ChatKey resolveSessionKey(Map<String, String> headers, ChatCompletionRequest request) {
        ChatKey explicit = explicitChatKey(headers, request);
        if (explicit != null) {
            return explicit;
        }
        if (config.isDeriveChatKeyFromHistory() && request != null) {
            String derived = historyKey(request);
            if (derived != null) {
                return new ChatKey(derived, SOURCE_HISTORY);
            }
        }
        return new ChatKey(null, SOURCE_SHARED);
    }

    /**
     * {@code h:} plus a SHA-256 over the first system message and first user
     * message, in their JSON form so that multimodal content counts too.
     * {@code null} when the request has no user message.
     */
    static String historyKey(ChatCompletionRequest request) {
        ChatMessage firstUser = request.firstUserMessage();
        if (firstUser == null || firstUser.content() == null) {
            return null;
        }
        ChatMessage firstSystem = request.firstSystemMessage();
        String material = "system:" + (firstSystem == null || firstSystem.content() == null ? "" : firstSystem.content().toString())
                + "\nuser:" + firstUser.content().toString();
        return HISTORY_KEY_PREFIX + sha256(material).substring(0, 32);
    }

    /**
     * An explicit key as stored in the intent: verbatim up to
     * {@link #MAX_EXPLICIT_KEY_LENGTH} characters, its hash beyond that — the key
     * is caller-supplied, and the intent is a stored, indexed field.
     */
    static String boundExplicitKey(String key) {
        return key.length() <= MAX_EXPLICIT_KEY_LENGTH ? key : HASHED_KEY_PREFIX + sha256(key);
    }

    private static String sha256(String material) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(material.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            // Every Java platform is required to provide SHA-256.
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
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

        ChatKey chatKey = resolveSessionKey(headers, request);
        String intent = buildIntent(model.agentId(), chatKey.key());

        if (model.stateless()) {
            return new PreparedTurn(startConversation(model, userId, intent), inputData, true, null);
        }
        countChatKey(chatKey.source());
        // A history-keyed request without history is the opening turn of a chat. An
        // existing mapping under the same key belongs to an EARLIER chat that opened
        // with the same words, so it must not be continued.
        boolean newChat = SOURCE_HISTORY.equals(chatKey.source()) && !request.hasHistory();
        return new PreparedTurn(getOrCreateConversation(model, userId, intent, newChat), inputData, false, intent);
    }

    /**
     * Resolve the mapped conversation, creating one when absent or unusable.
     * Mirrors {@code SlackEventHandler.getOrCreateConversation}.
     */
    String getOrCreateConversation(AgentModelResolver.ResolvedModel model, String userId, String intent) {
        return getOrCreateConversation(model, userId, intent, false);
    }

    /**
     * @param newChat
     *            the request opens a new chat: an existing mapping is superseded
     *            (the old conversation is left as it is, not ended) rather than
     *            continued
     */
    String getOrCreateConversation(AgentModelResolver.ResolvedModel model, String userId, String intent, boolean newChat) {
        UserConversation existing = readMapping(intent, userId);
        if (existing != null && newChat) {
            deleteMapping(intent, userId);
        } else if (existing != null) {
            if (isUsable(existing.getConversationId()) && !rolledOver(existing.getConversationId())) {
                return existing.getConversationId();
            }
            // Ended or vanished — drop the stale mapping and start over rather
            // than failing every subsequent message in this chat.
            deleteMapping(intent, userId);
        } else {
            // No mapping under the namespaced id: an Open WebUI chat that predates
            // namespacing may still have one under the raw header id. Adopt it — the
            // conversation keeps its raw-id owner, so its long-term memories load
            // without any move. We deliberately do NOT move memories out of the bare
            // id here: that namespace is shared with OIDC principals and every other
            // source, and the raw id is caller-supplied (a leaked /v1 key lets the
            // header be set to any string), so a standalone move would let a caller
            // relocate and erase an OIDC user's memories (Finding A). Adoption is
            // scoped to a conversation MAPPING under this exact intent, which only this
            // bridge writes — but in OIDC mode it writes them under the bare principal,
            // so a raw mapping's origin is unknowable and adoption is opt-in (see
            // OpenAiCompatConfig#isAdoptLegacyHeaderMappings).
            String legacyConversationId = adoptLegacyMapping(model, userId, intent);
            if (legacyConversationId != null) {
                return legacyConversationId;
            }
        }

        String conversationId = startConversation(model, userId, intent);
        try {
            userConversationStore.createUserConversation(new UserConversation(
                    intent, userId, model.environment(), model.agentId(), conversationId));
        } catch (Exception e) {
            // Two requests for the same chat raced. This is routine rather than
            // exceptional: Open WebUI issues the completion and its title/tag
            // request concurrently, both with the same chat id, so both find no
            // mapping and both insert.
            //
            // Caught broadly on purpose. (intent, userId) is uniquely indexed,
            // but the stores do not agree on how they report the collision —
            // MongoDB surfaces a raw MongoWriteException (E11000) rather than
            // ResourceAlreadyExistsException, and the Postgres store differs
            // again. Catching a specific type here was dead code against the
            // real store, which is how this reached a live UI as a bare 500.
            // Re-reading the mapping is the datastore-agnostic test of what
            // actually happened.
            UserConversation winner = readMapping(intent, userId);
            endQuietly(conversationId);
            if (winner != null) {
                return winner.getConversationId();
            }
            LOGGER.errorf("Could not persist the conversation mapping for %s: %s",
                    sanitize(intent), e.getMessage());
            throw OpenAiApiException.serverError(null,
                    "Could not establish a conversation for this chat. Please retry.");
        }
        return conversationId;
    }

    /**
     * Ends the mapped conversation when it has reached
     * {@code max-conversation-steps} and is idle; returns whether it did. The chat
     * then continues in a fresh conversation. The ended one stays readable —
     * nothing is trimmed — but the agent no longer sees its history.
     * <p>
     * Idle only: ending a conversation that awaits a human decision would discard
     * the pending approval, and ending one mid-turn would refuse that turn.
     */
    private boolean rolledOver(String conversationId) {
        int maxSteps = config.getMaxConversationSteps();
        if (maxSteps <= 0) {
            return false;
        }
        ConversationListingSummary summary;
        try {
            summary = conversationMemoryStore.loadListingSummaries(List.of(conversationId)).get(conversationId);
        } catch (Exception e) {
            LOGGER.debugf("Could not read the size of conversation %s; keeping it: %s", sanitize(conversationId),
                    e.getMessage());
            return false;
        }
        if (summary == null || summary.conversationStepCount() < maxSteps
                || !ROLLOVER_STATES.contains(summary.conversationState())) {
            return false;
        }
        try {
            conversationService.endConversation(conversationId);
        } catch (Exception e) {
            LOGGER.warnf("Could not end conversation %s for its step rollover; keeping it: %s", sanitize(conversationId),
                    e.getMessage());
            return false;
        }
        conversationsRolledOver.increment();
        LOGGER.infof("OpenAI-adapter conversation %s reached %d steps (limit %d); the chat continues in a new conversation",
                sanitize(conversationId), summary.conversationStepCount(), maxSteps);
        return true;
    }

    private String startConversation(AgentModelResolver.ResolvedModel model, String userId, String intent) {
        try {
            // The same USE gate the REST and MCP surfaces apply. There is no verified
            // principal on /v1 — one shared API key, a user id taken from a trusted
            // header — so this admits only published agents once workspaces are enforced,
            // and everything as before when they are not. Deliberately NOT scoped to the
            // header-supplied userId: that id is self-asserted, and honouring it would let
            // one leaked key reach any user's private agents.
            resourceAccessGuard.requireAgentUseAccess(model.agentId());
            var result = conversationService.startConversation(model.environment(), model.agentId(), userId,
                    Map.of(CONTEXT_CHANNEL_INTENT, new Context(Context.ContextType.string, intent)));
            conversationsCreated.increment();
            return result.conversationId();
        } catch (IConversationService.AgentNotReadyException e) {
            throw OpenAiApiException.unavailable(OpenAiErrorResponse.CODE_AGENT_NOT_READY,
                    "Agent '" + model.displayName() + "' is not ready: " + e.getMessage());
        } catch (Exception e) {
            // The cause stays in the log: its message can name hosts or connection
            // strings, and this reaches a caller holding only the shared key.
            LOGGER.errorf(e, "Could not start an OpenAI-adapter conversation for agent %s", sanitize(model.agentId()));
            throw OpenAiApiException.serverError(null, "Could not start a conversation.");
        }
    }

    // ─── legacy-identity migration (raw Open WebUI id → openwebui:<id>) ───

    /**
     * Adopt a chat mapping stored under the raw header id, re-keying it to the
     * namespaced id so the chat keeps its conversation. The conversation is not
     * touched — it keeps the raw id as its owner, and with it the memories it has
     * always loaded. Only attempted for a namespaced Open WebUI caller, and only
     * when the operator enabled it: a raw mapping under this intent may equally
     * have been written for an OIDC principal while {@code /v1} ran with
     * {@code http-policy=authenticated}, and nothing in it records which — adopting
     * that would hand an OIDC user's conversation (and, through its owner, their
     * memories) to a shared-key caller who names the principal in the header.
     *
     * @return the adopted conversation id, or {@code null} when there is nothing to
     *         adopt (disabled, not a namespaced caller, no legacy mapping, or a
     *         stale one)
     */
    private String adoptLegacyMapping(AgentModelResolver.ResolvedModel model, String userId, String intent) {
        if (!config.isAdoptLegacyHeaderMappings()) {
            return null;
        }
        String rawId = OpenAiUserIdentity.rawId(userId);
        if (rawId == null) {
            return null;
        }
        UserConversation legacy;
        try {
            legacy = userConversationStore.readUserConversation(intent, rawId);
        } catch (IResourceStore.ResourceStoreException e) {
            // Inconclusive, not absent: starting a new conversation here would write a
            // namespaced mapping that shadows the legacy one on every later request, and
            // the chat would lose its conversation for good. Fail this request instead.
            LOGGER.warnf("Could not read the legacy conversation mapping for %s: %s", sanitize(intent),
                    e.getMessage());
            throw OpenAiApiException.serverError(null,
                    "Could not establish a conversation for this chat. Please retry.");
        }
        if (legacy == null) {
            return null;
        }
        if (!isUsable(legacy.getConversationId())) {
            deleteMapping(intent, rawId);
            return null;
        }
        try {
            userConversationStore.createUserConversation(new UserConversation(
                    intent, userId, legacy.getEnvironment() != null ? legacy.getEnvironment() : model.environment(),
                    legacy.getAgentId() != null ? legacy.getAgentId() : model.agentId(), legacy.getConversationId()));
        } catch (Exception e) {
            // Either a concurrent request re-keyed it first, or the store failed. Only
            // the re-read tells them apart (the stores report a duplicate differently —
            // see getOrCreateConversation). Drop the legacy mapping only once a
            // namespaced one for the same conversation is confirmed; otherwise keep it,
            // so the next request can still find this chat's conversation.
            UserConversation rekeyed = readMapping(intent, userId);
            if (rekeyed == null) {
                LOGGER.warnf("Could not re-key legacy Open WebUI conversation mapping %s; keeping it: %s",
                        sanitize(intent), e.getMessage());
                return legacy.getConversationId();
            }
            if (!legacy.getConversationId().equals(rekeyed.getConversationId())) {
                // Another request already bound this chat to a different conversation.
                // That mapping wins; the legacy one is left for the operator to inspect.
                return rekeyed.getConversationId();
            }
        }
        deleteMapping(intent, rawId);
        LOGGER.infof("Re-keyed legacy Open WebUI conversation mapping %s to the namespaced user id", sanitize(intent));
        return legacy.getConversationId();
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
        String outcomeTag = "error";
        try {
            TurnOutcome outcome = render(sendAndWait(turn.conversationId(), turn.inputData()));
            outcomeTag = outcome.paused() ? "paused" : "ok";
            return outcome;
        } catch (IConversationService.ConversationEndedException e) {
            if (turn.stateless()) {
                throw OpenAiApiException.serverError(null, "The conversation ended before it could be used.");
            }
            String intent = turn.intent() != null
                    ? turn.intent()
                    : buildIntent(model.agentId(), resolveSessionKey(headers, request).key());
            deleteMapping(intent, userId);
            String fresh = getOrCreateConversation(model, userId, intent);
            try {
                TurnOutcome outcome = render(sendAndWait(fresh, turn.inputData()));
                outcomeTag = outcome.paused() ? "paused" : "ok";
                return outcome;
            } catch (Exception retryFailure) {
                OpenAiApiException apiException = asApiException(retryFailure);
                outcomeTag = outcomeTag(apiException);
                throw apiException;
            }
        } catch (Exception e) {
            OpenAiApiException apiException = asApiException(e);
            outcomeTag = outcomeTag(apiException);
            throw apiException;
        } finally {
            if (turn.stateless()) {
                endQuietly(turn.conversationId());
            }
            turnTimer.record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
            countTurn("sync", outcomeTag);
        }
    }

    /** The {@code outcome} metric tag for a failed turn. */
    private static String outcomeTag(OpenAiApiException apiException) {
        return switch (apiException.getStatus()) {
            case 429 -> "busy";
            case 504 -> "timeout";
            default -> "error";
        };
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

        // returnDetailed=true: the filtered snapshot keeps only input, actions,
        // output and quick replies, which drops the audit:token_usage entry the
        // usage block is built from. Detailed costs one extra step's worth of
        // references in this process — the snapshot itself is never serialized to
        // the client, only read here.
        conversationService.say(conversationId, true, true, Collections.emptyList(), inputData, false,
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
        } catch (InterruptedException e) {
            // Restore the flag before unwinding: swallowing it leaves the worker
            // thread looking healthy to the pool while its shutdown signal is gone.
            Thread.currentThread().interrupt();
            throw OpenAiApiException.serverError(null, "The request was interrupted before the agent replied.");
        }
    }

    /** Render a snapshot (or sentinel) as assistant text. */
    TurnOutcome render(SimpleConversationMemorySnapshot snapshot) {
        if (snapshot == SKIPPED_STILL_AWAITING) {
            return new TurnOutcome(STILL_AWAITING_NOTICE, true, null);
        }
        if (snapshot == SKIPPED_NOT_ACTIVE) {
            throw OpenAiApiException.busy(BUSY_NOTICE);
        }
        if (snapshot != null && snapshot.getConversationState() == ConversationState.ERROR) {
            throw agentFailure(snapshot);
        }

        String text = OpenAiOutputRenderer.render(snapshot);
        TokenUsage usage = extractUsage(snapshot);
        boolean paused = snapshot != null
                && snapshot.getConversationState() == ConversationState.AWAITING_HUMAN;

        if (paused) {
            String notice = PAUSE_NOTICE_PREFIX + " Conversation: " + snapshot.getConversationId();
            return new TurnOutcome(text == null || text.isBlank() ? notice : text + "\n\n" + notice, true, usage,
                    Choice.FINISH_STOP);
        }
        return new TurnOutcome(text == null || text.isBlank() ? NO_OUTPUT_NOTICE : text, false, usage,
                extractFinishReason(snapshot));
    }

    /**
     * The OpenAI error for a turn the pipeline failed (conversation state
     * {@code ERROR}). Reported as an error — HTTP 500 on the plain path, an error
     * event on a stream — never as an answer with {@code finish_reason: "stop"},
     * which a client cannot tell apart from a real reply.
     * <p>
     * The reason is the failing task's digest from the turn's {@code taskErrors}
     * output: already sanitized (URLs, stack frames and credentials are stripped by
     * the lifecycle before it is stored), and the same text EDDI's own REST and
     * streaming endpoints report.
     */
    static OpenAiApiException agentFailure(SimpleConversationMemorySnapshot snapshot) {
        String reason = turnErrorText(snapshot);
        return OpenAiApiException.serverError(OpenAiErrorResponse.CODE_AGENT_ERROR,
                reason == null ? "The agent failed to process the message." : "The agent failed to process the message. " + reason);
    }

    /**
     * The last {@code taskErrors} digest text of the latest turn, or {@code null}.
     */
    static String turnErrorText(SimpleConversationMemorySnapshot snapshot) {
        List<ConversationOutput> outputs = snapshot == null ? null : snapshot.getConversationOutputs();
        if (outputs == null || outputs.isEmpty()) {
            return null;
        }
        ConversationOutput last = outputs.get(outputs.size() - 1);
        Object errors = last == null ? null : last.get(MemoryKeys.TASK_ERRORS);
        if (!(errors instanceof List<?> list) || list.isEmpty() || !(list.get(list.size() - 1) instanceof Map<?, ?> digest)) {
            return null;
        }
        Object text = digest.get("text");
        return text == null || text.toString().isBlank() ? null : text.toString();
    }

    /**
     * The turn's OpenAI {@code finish_reason}: {@code length} when the model's
     * answer was cut off at its token limit, {@code content_filter} when the
     * provider filtered it, {@code stop} otherwise. Read from the
     * {@link MemoryKeys#LLM_FINISH_REASON} datum {@code LlmTask} leaves on the
     * step.
     */
    static String extractFinishReason(SimpleConversationMemorySnapshot snapshot) {
        if (snapshot == null || isNullOrEmpty(snapshot.getConversationSteps())) {
            return Choice.FINISH_STOP;
        }
        var steps = snapshot.getConversationSteps();
        var lastStep = steps.get(steps.size() - 1);
        if (lastStep == null || isNullOrEmpty(lastStep.getConversationStep())) {
            return Choice.FINISH_STOP;
        }
        for (var data : lastStep.getConversationStep()) {
            if (data != null && MemoryKeys.LLM_FINISH_REASON.equals(data.getKey()) && data.getValue() != null) {
                String reason = data.getValue().toString();
                if (Choice.FINISH_LENGTH.equals(reason) || Choice.FINISH_CONTENT_FILTER.equals(reason)) {
                    return reason;
                }
            }
        }
        return Choice.FINISH_STOP;
    }

    /**
     * Pull the turn's token usage out of the snapshot's {@code audit:token_usage}
     * entry.
     * <p>
     * Present only because this adapter asks for <em>detailed</em> snapshots: the
     * filtered form keeps just input, actions, output and quick replies, and drops
     * every audit key. Absent for any agent that made no model call.
     */
    static TokenUsage extractUsage(SimpleConversationMemorySnapshot snapshot) {
        if (snapshot == null || isNullOrEmpty(snapshot.getConversationSteps())) {
            return null;
        }
        var steps = snapshot.getConversationSteps();
        var lastStep = steps.get(steps.size() - 1);
        if (lastStep == null || isNullOrEmpty(lastStep.getConversationStep())) {
            return null;
        }
        for (var data : lastStep.getConversationStep()) {
            if (data != null && MemoryKeys.AUDIT_TOKEN_USAGE.equals(data.getKey())
                    && data.getValue() instanceof Map<?, ?> map) {
                @SuppressWarnings("unchecked")
                var tokenUsage = (Map<String, Object>) map;
                return TokenUsage.from(tokenUsage);
            }
        }
        return null;
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
     * <b>Exactly one terminator.</b> The stream always ends with either a
     * {@code finish_reason} chunk or an OpenAI error event, followed by one
     * {@code [DONE]} — and nothing after it. Once headers are flushed the status is
     * fixed at 200, so a failure (an ERROR turn, a pipeline exception, a busy
     * conversation, the request timeout) is reported as an error event, which the
     * {@code openai} SDKs raise as {@code APIError}, instead of a warning written
     * as if the model had said it. The writer drops whatever a still-running
     * pipeline emits after the terminator (see {@link OpenAiSseWriter}), so a
     * timeout can no longer be followed by late tokens.
     */
    public void stream(PreparedTurn turn, OpenAiSseWriter writer) {
        long start = System.nanoTime();
        AtomicBoolean anyToken = new AtomicBoolean();
        AtomicReference<String> outcomeTag = new AtomicReference<>("ok");
        // sayStreaming hands the turn to the ConversationCoordinator and returns
        // immediately; every handler callback fires later, on another thread. So
        // the terminator cannot be written when sayStreaming returns — the caller
        // would close the response while tokens were still arriving, and the
        // client would see content after [DONE]. This latch is completed by
        // whichever terminal callback fires, and awaited before unwinding.
        var finished = new CompletableFuture<Void>();
        try {
            // returnDetailed=true for the same reason as the sync path — see
            // sendAndWait.
            conversationService.sayStreaming(turn.conversationId(), true, true, Collections.emptyList(),
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
                            anyToken.set(true);
                            writer.content(token);
                        }

                        @Override
                        public void onComplete(SimpleConversationMemorySnapshot snapshot) {
                            try {
                                if (snapshot != null && snapshot.getConversationState() == ConversationState.ERROR) {
                                    // A failed turn also reaches onError; whichever comes
                                    // first terminates the stream, the other is a no-op.
                                    outcomeTag.set("error");
                                    writer.error(agentFailure(snapshot).toErrorResponse());
                                    return;
                                }
                                if (anyToken.get()) {
                                    // The model already streamed the prose, so only the
                                    // non-text affordances are still missing — emitting the
                                    // full render here would repeat the whole reply.
                                    String extras = OpenAiOutputRenderer.renderExtras(snapshot);
                                    if (extras != null) {
                                        writer.content("\n\n" + extras);
                                    }
                                } else {
                                    String text = OpenAiOutputRenderer.render(snapshot);
                                    writer.content(text == null || text.isBlank() ? NO_OUTPUT_NOTICE : text);
                                }
                                boolean paused = snapshot != null
                                        && snapshot.getConversationState() == ConversationState.AWAITING_HUMAN;
                                if (paused) {
                                    outcomeTag.set("paused");
                                    writer.content("\n\n" + PAUSE_NOTICE_PREFIX
                                            + " Conversation: " + snapshot.getConversationId());
                                }
                                writer.usage(extractUsage(snapshot));
                                writer.finish(paused ? Choice.FINISH_STOP : extractFinishReason(snapshot));
                            } finally {
                                finished.complete(null);
                            }
                        }

                        @Override
                        public void onSkipped(SimpleConversationMemorySnapshot snapshot) {
                            try {
                                boolean stillAwaiting = snapshot != null
                                        && snapshot.getConversationState() == ConversationState.AWAITING_HUMAN;
                                if (stillAwaiting) {
                                    // HITL is surfaced as chat text, never as an error — see
                                    // TurnOutcome#paused.
                                    outcomeTag.set("paused");
                                    writer.content(STILL_AWAITING_NOTICE);
                                    writer.finish(Choice.FINISH_STOP);
                                } else {
                                    outcomeTag.set("busy");
                                    writer.error(OpenAiApiException.busy(BUSY_NOTICE).toErrorResponse());
                                }
                            } finally {
                                finished.complete(null);
                            }
                        }

                        @Override
                        public void onError(Throwable error) {
                            try {
                                // Logged, not sent: a raw exception message can carry hosts,
                                // URLs or paths, and this reaches a caller holding only the
                                // shared key. A failed turn's sanitized reason still reaches the
                                // client through the ERROR snapshot onComplete delivers first
                                // (agentFailure); this is the fallback for everything else.
                                LOGGER.errorf(error, "OpenAI adapter stream failed for conversation %s",
                                        sanitize(turn.conversationId()));
                                outcomeTag.compareAndSet("ok", "error");
                                writer.error(OpenAiApiException.serverError(OpenAiErrorResponse.CODE_AGENT_ERROR,
                                        AGENT_FAILED_MESSAGE).toErrorResponse());
                            } finally {
                                finished.complete(null);
                            }
                        }
                    });

            // Block until a terminal callback fires. Returning before that would
            // let the caller close the response mid-turn.
            finished.get(config.getRequestTimeoutSeconds(), TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            outcomeTag.set("timeout");
            writer.error(OpenAiApiException.timeout("The agent did not respond within "
                    + config.getRequestTimeoutSeconds() + " seconds.").toErrorResponse());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            outcomeTag.set("error");
            writer.error(OpenAiApiException.serverError(null, "The request was interrupted before the agent replied.")
                    .toErrorResponse());
        } catch (IConversationService.ConversationAwaitingApprovalException e) {
            // The pause was already persisted at submit time: chat text, as on onSkipped.
            outcomeTag.set("paused");
            writer.content(STILL_AWAITING_NOTICE);
            writer.finish(Choice.FINISH_STOP);
        } catch (Exception e) {
            OpenAiApiException apiException = asApiException(e);
            outcomeTag.set(apiException.getStatus() == 429 ? "busy" : "error");
            writer.error(apiException.toErrorResponse());
        } finally {
            // Belt and braces: the handler terminates on every documented path,
            // but an undocumented one must not leave the client waiting forever.
            if (!writer.isFinished()) {
                outcomeTag.set("error");
                writer.error(OpenAiApiException.serverError(null, "The agent ended the turn without a result.")
                        .toErrorResponse());
            }
            if (turn.stateless()) {
                endQuietly(turn.conversationId());
            }
            turnTimer.record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
            countTurn("stream", outcomeTag.get());
        }
    }

    /** Error code for input over {@code eddi.conversations.max-input-chars}. */
    static final String INPUT_TOO_LARGE_CODE = "input_too_large";

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
        // A caller error, not a server one: the input cap refused the turn before any
        // model call. 400 is what OpenAI itself answers for an over-long prompt.
        IConversationService.InputTooLargeException tooLarge = e instanceof IConversationService.InputTooLargeException direct
                ? direct
                : e.getCause() instanceof IConversationService.InputTooLargeException cause ? cause : null;
        if (tooLarge != null) {
            return OpenAiApiException.badRequest(INPUT_TOO_LARGE_CODE, tooLarge.getMessage());
        }
        if (e.getCause() instanceof OpenAiApiException causeException) {
            return causeException;
        }
        // Logged, not returned: an unclassified exception's message can carry hosts,
        // connection strings or paths, and this one reaches a caller holding only the
        // shared API key.
        LOGGER.errorf(e, "OpenAI adapter turn failed: %s", e.getMessage());
        return OpenAiApiException.serverError(null, "The agent could not process the message.");
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
