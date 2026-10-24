/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.webhook;

import ai.labs.eddi.configs.channels.model.ChannelIntegrationConfiguration;
import ai.labs.eddi.configs.channels.model.ChannelTarget;
import ai.labs.eddi.configs.channels.model.WebhookChannelSettings;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.api.IConversationService.AgentNotReadyException;
import ai.labs.eddi.engine.api.IConversationService.ConversationAwaitingApprovalException;
import ai.labs.eddi.engine.api.IConversationService.ConversationEndedException;
import ai.labs.eddi.engine.api.IGroupConversationService;
import ai.labs.eddi.engine.api.IGroupConversationService.GroupDiscussionException;
import ai.labs.eddi.engine.caching.CacheFactory;
import ai.labs.eddi.engine.caching.ICache;
import ai.labs.eddi.engine.caching.ICacheFactory;
import ai.labs.eddi.engine.gdpr.ProcessingRestrictedException;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot;
import ai.labs.eddi.engine.model.Context;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.model.Deployment;
import ai.labs.eddi.engine.model.InputData;
import ai.labs.eddi.engine.tenancy.QuotaExceededException;
import ai.labs.eddi.engine.triggermanagement.IUserConversationStore;
import ai.labs.eddi.engine.triggermanagement.model.UserConversation;
import ai.labs.eddi.integrations.channels.ChannelTargetRouter;
import ai.labs.eddi.integrations.channels.ChannelTargetRouter.ResolvedTarget;
import ai.labs.eddi.integrations.channels.HmacSignatures;
import ai.labs.eddi.integrations.webhook.WebhookEventParser.InboundEvent;
import ai.labs.eddi.modules.templating.ITemplatingEngine;
import com.fasterxml.jackson.databind.JsonNode;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * Runs a verified, parsed webhook event: deduplicates it, routes it by type to
 * an agent or group target, and starts the turn.
 * <p>
 * <b>What runs on the request thread, and why.</b> The endpoint answers
 * {@code 202} with the conversation id and {@code 429} when the tenant is over
 * quota, so the two quota-consuming steps must happen before it answers:
 * {@link IConversationService#startConversation} (conversation slot, and it
 * returns the id) and {@link IConversationService#say} (API-call slot). Neither
 * holds the request for the agent's work: {@code say} acquires its slot, queues
 * the turn on the conversation coordinator and returns — the turn itself runs
 * asynchronously, exactly as a REST turn does. A group target likewise creates
 * its record synchronously and discusses on the group service's own virtual
 * threads. A separate executor would only have moved the quota refusal to a
 * place the sender can no longer be told about. The one piece of agent work
 * that does run inline is a new conversation's {@code CONVERSATION_START} turn
 * — keep webhook agents' start rules light.
 * <p>
 * <b>Identity.</b> Turns run as {@code webhook:<integrationId>}: a principal no
 * OIDC user or other channel can produce, owned by nobody but the integration.
 * Connections scoped {@code PER_USER} have no human principal here and behave
 * as for scheduled turns.
 *
 * @since 6.6.0
 */
@ApplicationScoped
public class WebhookEventHandler {

    private static final Logger LOGGER = Logger.getLogger(WebhookEventHandler.class);

    /** Prefix of every user id a webhook turn runs as. */
    public static final String USER_PREFIX = "webhook:";

    /** Context key under which the event map reaches the conversation. */
    public static final String EVENT_CONTEXT_KEY = "event";

    /**
     * Deduplication window. Longer than the eddi-v1 freshness window, so a captured
     * request cannot be replayed after its id has been forgotten but while its
     * timestamp is still accepted.
     */
    static final Duration DEDUP_TTL = Duration.ofMinutes(10);

    /**
     * Name of the dedup cache; sized in {@link CacheFactory}, shared in a cluster.
     */
    public static final String DEDUP_CACHE_NAME = "webhook-event-dedup";

    /**
     * Occupancy of the dedup cache at which a delivery that is not already
     * remembered is refused (503, retryable) instead of reserved. The cache is
     * sized at twice this (see {@code CacheFactory.RATE_SIZED_EVICTION_HEADROOM}),
     * so it never has to evict a live key to admit a new one: an evicted key is a
     * replay that would run again.
     */
    static final long DEDUP_REFUSE_AT = CacheFactory.maximumSizeFor(DEDUP_CACHE_NAME, DEDUP_TTL) / 2;

    /** Machine-readable {@code reason} of a 409 whose first delivery is running. */
    public static final String REASON_IN_PROGRESS = "in_progress";

    /**
     * Machine-readable {@code reason} of a 409 for a conversation awaiting
     * approval.
     */
    public static final String REASON_AWAITING_APPROVAL = "awaiting_approval";

    /** Marker for an event whose first delivery is still being processed. */
    static final String IN_FLIGHT = "";

    /** Answer to a repeat delivery while the first one is still being processed. */
    static final String IN_PROGRESS = "Delivery in progress — retry shortly.";

    private final ChannelTargetRouter router;
    private final IConversationService conversationService;
    private final IGroupConversationService groupConversationService;
    private final IUserConversationStore userConversationStore;
    private final ITemplatingEngine templatingEngine;
    private final ICache<String, String> dedup;
    private final MeterRegistry meterRegistry;
    private final long dedupRefuseAt;

    @Inject
    public WebhookEventHandler(ChannelTargetRouter router,
            IConversationService conversationService,
            IGroupConversationService groupConversationService,
            IUserConversationStore userConversationStore,
            ITemplatingEngine templatingEngine,
            ICacheFactory cacheFactory,
            MeterRegistry meterRegistry) {
        this(router, conversationService, groupConversationService, userConversationStore, templatingEngine, cacheFactory, meterRegistry,
                DEDUP_REFUSE_AT);
    }

    WebhookEventHandler(ChannelTargetRouter router,
            IConversationService conversationService,
            IGroupConversationService groupConversationService,
            IUserConversationStore userConversationStore,
            ITemplatingEngine templatingEngine,
            ICacheFactory cacheFactory,
            MeterRegistry meterRegistry,
            long dedupRefuseAt) {
        this.dedupRefuseAt = dedupRefuseAt;
        this.router = router;
        this.conversationService = conversationService;
        this.groupConversationService = groupConversationService;
        this.userConversationStore = userConversationStore;
        this.templatingEngine = templatingEngine;
        this.dedup = cacheFactory.getCache(DEDUP_CACHE_NAME, DEDUP_TTL);
        this.meterRegistry = meterRegistry;
    }

    /**
     * What the endpoint answers.
     *
     * @param status
     *            HTTP status
     * @param conversationId
     *            the agent conversation the event went to, if any
     * @param groupConversationId
     *            the group discussion it started, if any
     * @param duplicate
     *            whether this was a repeat delivery
     * @param error
     *            a client-safe reason for a refusal
     * @param reason
     *            machine-readable discriminator for a status that has more than one
     *            cause (409: {@link #REASON_IN_PROGRESS} or
     *            {@link #REASON_AWAITING_APPROVAL}); null otherwise
     */
    public record Dispatch(int status, String conversationId, String groupConversationId, boolean duplicate, String error, String reason) {
        static Dispatch error(int status, String error) {
            return new Dispatch(status, null, null, false, error, null);
        }

        static Dispatch error(int status, String error, String reason) {
            return new Dispatch(status, null, null, false, error, reason);
        }
    }

    /**
     * @param integrationId
     *            the integration's resource id (from the URL)
     * @param integration
     *            the integration, secrets resolved
     * @param event
     *            the parsed event
     * @param replayKey
     *            a key unique to this exact signed request, used when the event
     *            carries no id (the signature header value, for every scheme); may
     *            be null
     */
    public Dispatch dispatch(String integrationId, ChannelIntegrationConfiguration integration,
                             WebhookChannelSettings settings, InboundEvent event, String replayKey) {
        // Two keys, either of which makes a delivery a repeat: the sender's event id
        // (a legitimate redelivery keeps it) and the canonical replay key of what
        // was signed (a captured request replayed under a NEW, unsigned id keeps
        // that). The price: two deliveries with byte-identical signed content
        // inside the window count as one — for eddi-v1 that needs the same
        // timestamp second as well.
        List<String> keys = new ArrayList<>(2);
        if (event.id() != null) {
            keys.add(integrationId + ":id:" + event.id());
        }
        if (replayKey != null) {
            keys.add(integrationId + ":sig:" + replayKey);
        }
        if (dedup.size() >= dedupRefuseAt && keys.stream().noneMatch(key -> dedup.get(key) != null)) {
            // The cache is full of live keys. Reserving another would evict one, and
            // an evicted key is a signed request that can be replayed. A repeat of
            // something already remembered still gets its answer (the check above);
            // anything new waits for entries to age out (10 minutes).
            LOGGER.warnf("Webhook %s: delivery refused, the replay-protection cache is at capacity (%d keys)", sanitize(integrationId),
                    dedupRefuseAt);
            count("refused_503");
            return Dispatch.error(503, "Temporarily unable to accept events — retry later.");
        }
        List<String> reserved = new ArrayList<>(keys.size());
        for (String key : keys) {
            String previous = dedup.putIfAbsent(key, IN_FLIGHT);
            if (previous == null) {
                reserved.add(key);
                continue;
            }
            // Give back what this call reserved: the delivery is not processed here.
            reserved.forEach(own -> dedup.remove(own, IN_FLIGHT));
            if (previous.isEmpty()) {
                // The first delivery has not finished. It may still be refused
                // (429, 503, ...), which releases the reservation — so a 200 here
                // would tell the sender an event was delivered that never was,
                // and it would not retry. Answer retryably instead; only a
                // recorded 202 makes a repeat a duplicate.
                count("in_progress");
                return new Dispatch(409, null, null, true, IN_PROGRESS, REASON_IN_PROGRESS);
            }
            count("duplicate");
            boolean group = previous.startsWith("group:");
            return new Dispatch(200, group ? null : previous, group ? previous.substring("group:".length()) : null, true, null, null);
        }

        Dispatch outcome;
        try {
            outcome = route(integrationId, integration, settings, event);
        } catch (RuntimeException e) {
            outcome = refusal(integrationId, e);
        }
        for (String key : reserved) {
            if (outcome.status() == 202) {
                dedup.put(key, outcome.groupConversationId() != null
                        ? "group:" + outcome.groupConversationId()
                        : outcome.conversationId());
            } else {
                // A refused event was not processed: its retry must be, so the
                // reservation is released rather than remembered.
                dedup.remove(key, IN_FLIGHT);
            }
        }
        count(outcome.status() == 202 ? "accepted" : "refused_" + outcome.status());
        return outcome;
    }

    private Dispatch route(String integrationId, ChannelIntegrationConfiguration integration,
                           WebhookChannelSettings settings, InboundEvent event) {
        ResolvedTarget resolved = router.resolveEventTarget(integration, event.type());
        if (resolved == null || resolved.target() == null || resolved.target().getTargetId() == null) {
            return Dispatch.error(422, "No target of this integration accepts event type '" + event.type() + "'.");
        }
        ChannelTarget target = resolved.target();
        String userId = USER_PREFIX + integrationId;
        String input = renderInput(settings, event);
        try {
            if (target.getType() == ChannelTarget.TargetType.GROUP) {
                // A group discussion takes a question, not a context map: the
                // rendered input is all its members see of the event.
                var groupConversation = groupConversationService.startAndDiscussAsync(target.getTargetId(), input, userId, null);
                LOGGER.infof("Webhook %s: event type %s started group discussion %s", sanitize(integrationId),
                        sanitize(event.type()), sanitize(groupConversation.getId()));
                return new Dispatch(202, null, groupConversation.getId(), false, null, null);
            }
            String conversationId = runAgentTurn(integrationId, target, settings, event, userId, input);
            LOGGER.infof("Webhook %s: event type %s accepted into conversation %s", sanitize(integrationId),
                    sanitize(event.type()), sanitize(conversationId));
            return new Dispatch(202, conversationId, null, false, null, null);
        } catch (AgentNotReadyException e) {
            return Dispatch.error(503, "The target agent is not deployed.");
        } catch (ConversationAwaitingApprovalException e) {
            return Dispatch.error(409, "The correlated conversation is awaiting human approval.", REASON_AWAITING_APPROVAL);
        } catch (IResourceStore.ResourceNotFoundException | GroupDiscussionException e) {
            LOGGER.warnf("Webhook %s: target %s cannot run: %s", sanitize(integrationId), sanitize(target.getName()),
                    sanitize(e.getMessage()));
            return Dispatch.error(422, "The routed target cannot run.");
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            LOGGER.errorf(e, "Webhook %s: could not start the turn", sanitize(integrationId));
            return Dispatch.error(500, "The event could not be processed.");
        }
    }

    private Dispatch refusal(String integrationId, RuntimeException e) {
        // Order matters: QuotaAccountingUnavailableException is a
        // RejectedExecutionException, not a QuotaExceededException.
        if (e instanceof QuotaExceededException) {
            return Dispatch.error(429, "Tenant quota exceeded.");
        }
        if (e instanceof RejectedExecutionException) {
            return Dispatch.error(503, "Temporarily unable to accept events — retry later.");
        }
        if (e instanceof ProcessingRestrictedException) {
            return Dispatch.error(403, "Processing is restricted for this integration's principal.");
        }
        if (e instanceof IConversationService.InputTooLargeException) {
            return Dispatch.error(413, "The rendered input is too large.");
        }
        LOGGER.errorf(e, "Webhook %s: unexpected failure", sanitize(integrationId));
        return Dispatch.error(500, "The event could not be processed.");
    }

    private String runAgentTurn(String integrationId, ChannelTarget target, WebhookChannelSettings settings,
                                InboundEvent event, String userId, String input)
            throws Exception {
        String agentId = target.getTargetId();
        String correlationKey = settings.conversationStrategy() == WebhookChannelSettings.Strategy.CORRELATE
                ? correlationKey(event, settings.correlationKeyPath())
                : null;
        if (correlationKey == null) {
            String conversationId = startConversation(agentId, userId, integrationId);
            try {
                say(conversationId, input, event, integrationId);
            } catch (Exception | Error refused) {
                // The turn was refused (429 on the API-call quota, 503, ...) after the
                // conversation was created, and the sender will retry with a new one:
                // end this one rather than leave one orphan per retry. (A correlated
                // conversation below is kept - the retry reuses it.)
                endQuietly(conversationId);
                throw refused;
            }
            return conversationId;
        }
        String intent = correlationIntent(integrationId, agentId, correlationKey);
        String conversationId = correlatedConversation(agentId, userId, integrationId, intent);
        try {
            say(conversationId, input, event, integrationId);
        } catch (ConversationEndedException | IConversationService.ConversationNotFoundException ended) {
            // Ended between the state check and the turn: replace it once.
            userConversationStore.deleteUserConversationIfMatches(intent, userId, conversationId);
            conversationId = correlatedConversation(agentId, userId, integrationId, intent);
            say(conversationId, input, event, integrationId);
        }
        return conversationId;
    }

    /**
     * The conversation of this correlation key: the mapped one while it can still
     * take turns, else a new one recorded in its place. Mirrors the Slack thread
     * mapping, including its create race: the stored mapping wins and the loser's
     * conversation is ended.
     */
    private String correlatedConversation(String agentId, String userId, String integrationId, String intent)
            throws Exception {
        UserConversation existing = userConversationStore.readUserConversation(intent, userId);
        if (existing != null && existing.getConversationId() != null) {
            if (isLive(existing.getConversationId())) {
                return existing.getConversationId();
            }
            userConversationStore.deleteUserConversationIfMatches(intent, userId, existing.getConversationId());
        }
        String conversationId = startConversation(agentId, userId, integrationId);
        try {
            userConversationStore.createUserConversation(
                    new UserConversation(intent, userId, Deployment.Environment.production, agentId, conversationId));
        } catch (IResourceStore.ResourceAlreadyExistsException race) {
            UserConversation winner = userConversationStore.readUserConversation(intent, userId);
            if (winner != null && winner.getConversationId() != null) {
                endQuietly(conversationId);
                return winner.getConversationId();
            }
        }
        return conversationId;
    }

    private boolean isLive(String conversationId) {
        try {
            return conversationService.getConversationState(conversationId) != ConversationState.ENDED;
        } catch (IConversationService.ConversationNotFoundException e) {
            return false;
        } catch (RuntimeException e) {
            // A store hiccup must not cost the key its conversation.
            return true;
        }
    }

    private void endQuietly(String conversationId) {
        try {
            conversationService.endConversation(conversationId, "system:lost-create-race");
        } catch (RuntimeException e) {
            LOGGER.warnf("Could not end orphaned webhook conversation %s", sanitize(conversationId));
        }
    }

    private String startConversation(String agentId, String userId, String integrationId) throws Exception {
        Map<String, Context> context = Map.of("webhookIntegration", new Context(Context.ContextType.string, integrationId));
        return conversationService.startConversation(Deployment.Environment.production, agentId, userId, context).conversationId();
    }

    private void say(String conversationId, String input, InboundEvent event, String integrationId) throws Exception {
        Map<String, Context> context = new LinkedHashMap<>();
        context.put(EVENT_CONTEXT_KEY, new Context(Context.ContextType.object, event.event()));
        context.put("webhookIntegration", new Context(Context.ContextType.string, integrationId));
        var inputData = new InputData(input, context);
        conversationService.say(conversationId, false, true, Collections.emptyList(), inputData, false,
                new IConversationService.ConversationResponseHandler() {
                    @Override
                    public void onComplete(SimpleConversationMemorySnapshot snapshot) {
                        LOGGER.debugf("Webhook turn completed in conversation %s", sanitize(conversationId));
                    }

                    @Override
                    public void onSkipped(SimpleConversationMemorySnapshot snapshot) {
                        LOGGER.warnf("Webhook turn in conversation %s was skipped (conversation not ready)", sanitize(conversationId));
                    }
                });
    }

    /**
     * The turn's input text: the author's template rendered with the event as its
     * only data, or {@code event:<type>}. The template is author configuration; the
     * event is never compiled — Qute substitutes values, it does not re-evaluate
     * them.
     */
    String renderInput(WebhookChannelSettings settings, InboundEvent event) {
        String fallback = "event:" + event.type();
        if (settings.inputTemplate() == null) {
            return fallback;
        }
        try {
            String rendered = templatingEngine.processTemplate(settings.inputTemplate(), Map.of(EVENT_CONTEXT_KEY, event.event()),
                    ITemplatingEngine.TemplateMode.TEXT);
            return rendered == null || rendered.isBlank() ? fallback : rendered;
        } catch (Exception e) {
            LOGGER.warnf("Webhook inputTemplate could not be rendered — using '%s': %s", sanitize(fallback), sanitize(e.getMessage()));
            return fallback;
        }
    }

    /** The value at {@code pointer} in the event, or null when absent/empty. */
    static String correlationKey(InboundEvent event, String pointer) {
        if (pointer == null || event.eventNode() == null) {
            return null;
        }
        JsonNode node = event.eventNode().at(pointer);
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        String value = node.isValueNode() ? node.asText() : node.toString();
        return value.isBlank() ? null : value;
    }

    /**
     * Mapping key for a correlated conversation. Scoped by integration and agent (a
     * conversation belongs to one agent), with the key hashed so arbitrary
     * sender-supplied text never becomes part of a store key.
     */
    static String correlationIntent(String integrationId, String agentId, String correlationKey) {
        String digest = HmacSignatures.toHex(sha256(correlationKey));
        return USER_PREFIX + integrationId + ":" + agentId + ":" + digest;
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Records a refusal the endpoint answered before dispatch (401, 404, 413, 415,
     * 400), so the outcome counter covers every response, not only the ones that
     * reached the handler.
     */
    public void countRefused(int status) {
        count("refused_" + status);
    }

    private void count(String outcome) {
        if (meterRegistry != null) {
            meterRegistry.counter("eddi.webhook.events", "outcome", outcome).increment();
        }
    }
}
