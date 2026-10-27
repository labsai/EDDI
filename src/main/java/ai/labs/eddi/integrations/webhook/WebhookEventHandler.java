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
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

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
     * Occupancy of the dedup cache at which the fairness backstop starts: from
     * here, only integrations above their share of it ({@code dedupRefuseAt /
     * activeIntegrations}) are refused, so a new delivery is never reserved by an
     * integration that is crowding the others out. The cache is sized at twice this
     * (see {@code CacheFactory.RATE_SIZED_EVICTION_HEADROOM}), so it never has to
     * evict a live key to admit a new one: an evicted key is a replay that would
     * run again. The first line of defence is each integration's own
     * {@code maxDeliveriesPerWindow}; this one only matters when many integrations
     * are each within theirs.
     */
    static final long DEDUP_REFUSE_AT = CacheFactory.maximumSizeFor(DEDUP_CACHE_NAME, DEDUP_TTL) / 2;

    /** Metric outcome of a delivery refused for replay-protection capacity. */
    static final String OUTCOME_CAPACITY = "refused_capacity";

    /**
     * Length of the per-integration delivery window, in one-minute buckets: one
     * more than the 10-minute key TTL, because a bucket is whole minutes - a
     * delivery counted in minute m must still count at any instant its keys can
     * still exist (until m + 10 minutes + the rest of m), or the counter forgets
     * keys the cache still holds.
     */
    private static final int WINDOW_MINUTES = 11;

    /**
     * Integrations tracked at most (entries also expire 11 minutes after last use).
     */
    private static final int MAX_TRACKED_INTEGRATIONS = 100_000;

    /**
     * Deliveries in the window below which an integration does not count as
     * "active" for the fair share, so creating many integrations that each send a
     * single delivery cannot shrink everyone else's share.
     */
    static final long ACTIVE_FLOOR = 50;

    /** How long the active-integration count is reused. */
    private static final long ACTIVE_RECOUNT_MS = 5_000L;

    /**
     * Share of the cache's capacity from which every new, non-repeat delivery is
     * refused whatever its integration holds - the last resort that keeps the cache
     * clear of eviction even when the fair-share arithmetic is gamed.
     */
    private static final double HARD_STOP_FRACTION = 0.9;

    private static final long CAPACITY_WARN_INTERVAL_MS = 60_000L;

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
    private final long hardStopAt;
    private long activeFloor = ACTIVE_FLOOR;
    private volatile long activeCount = 1;
    private volatile long activeCountedAt = -1;
    private final Cache<String, DeliveryWindow> deliveries = Caffeine.newBuilder()
            .expireAfterAccess(Duration.ofMinutes(WINDOW_MINUTES))
            .maximumSize(MAX_TRACKED_INTEGRATIONS)
            .build();
    private final Map<String, Long> lastCapacityWarn = new ConcurrentHashMap<>();
    private LongSupplier clock = System::currentTimeMillis;
    private final AtomicInteger capacityWarnings = new AtomicInteger();

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
        this.hardStopAt = (long) (dedupRefuseAt * 2 * HARD_STOP_FRACTION);
        this.router = router;
        this.conversationService = conversationService;
        this.groupConversationService = groupConversationService;
        this.userConversationStore = userConversationStore;
        this.templatingEngine = templatingEngine;
        this.dedup = cacheFactory.getCache(DEDUP_CACHE_NAME, DEDUP_TTL);
        this.meterRegistry = meterRegistry;
    }

    /** Test seam: capacity warnings logged so far. */
    int capacityWarningsLogged() {
        return capacityWarnings.get();
    }

    /** Test seam: the delivery count from which an integration is "active". */
    void setActiveFloor(long floor) {
        this.activeFloor = floor;
    }

    /** Test seam: the clock the per-integration windows run on. */
    void setClock(LongSupplier clock) {
        this.clock = clock;
    }

    /**
     * Deliveries one integration reserved keys for in the last
     * {@value #WINDOW_MINUTES} minutes, node-local, in one-minute buckets.
     */
    private static final class DeliveryWindow {
        private final long[] counts = new long[WINDOW_MINUTES];
        private final long[] minutes = new long[WINDOW_MINUTES];

        synchronized void add(long minute) {
            int slot = (int) (minute % WINDOW_MINUTES);
            if (minutes[slot] != minute) {
                minutes[slot] = minute;
                counts[slot] = 0;
            }
            counts[slot]++;
        }

        synchronized void remove(long minute) {
            int slot = (int) (minute % WINDOW_MINUTES);
            if (minutes[slot] == minute && counts[slot] > 0) {
                counts[slot]--;
            }
        }

        synchronized long total(long minute) {
            long sum = 0;
            for (int i = 0; i < WINDOW_MINUTES; i++) {
                if (minutes[i] > minute - WINDOW_MINUTES && minutes[i] <= minute) {
                    sum += counts[i];
                }
            }
            return sum;
        }
    }

    /**
     * Why a delivery that is not yet remembered may NOT reserve keys, or null when
     * it may: over the integration's own budget, past the hard stop, or - when the
     * shared cache is past the backstop - above its fair share. Does not log; the
     * caller does once the refusal stands.
     */
    private String refusalReason(String integrationId, WebhookChannelSettings settings) {
        long minute = clock.getAsLong() / 60_000L;
        DeliveryWindow own = deliveries.getIfPresent(integrationId);
        long used = own == null ? 0 : own.total(minute);
        if (used >= settings.maxDeliveriesPerWindow()) {
            return "it reached its own budget of " + settings.maxDeliveriesPerWindow()
                    + " deliveries per 10 minutes (platformConfig.maxDeliveriesPerWindow)";
        }
        if (dedup.size() >= dedupRefuseAt) {
            // The cheap estimate says the backstop may be reached; sweep before refusing.
            int exact = dedup.sizeExact();
            if (exact >= hardStopAt) {
                return "the shared replay-protection cache is nearly full (" + exact + " keys)";
            }
            if (exact >= dedupRefuseAt) {
                long share = dedupRefuseAt / 2 / activeIntegrations(minute);
                if (used >= share) {
                    return "the shared replay-protection cache is past half full and it holds " + used
                            + " of its deliveries, above its fair share of " + share;
                }
            }
        }
        return null;
    }

    /**
     * Integrations with at least {@code activeFloor} deliveries in the window,
     * recounted at most every few seconds (it walks every tracked integration).
     */
    private long activeIntegrations(long minute) {
        long now = clock.getAsLong();
        if (activeCountedAt < 0 || now - activeCountedAt >= ACTIVE_RECOUNT_MS) {
            activeCount = Math.max(1, deliveries.asMap().values().stream().filter(w -> w.total(minute) >= activeFloor).count());
            activeCountedAt = now;
        }
        return activeCount;
    }

    /** Counts a delivery against the integration's budget; returns its bucket. */
    private long recordDelivery(String integrationId) {
        long minute = clock.getAsLong() / 60_000L;
        deliveries.get(integrationId, id -> new DeliveryWindow()).add(minute);
        return minute;
    }

    /**
     * A delivery that was not accepted does not hold keys: give its budget back.
     */
    private void unrecordDelivery(String integrationId, long minute) {
        DeliveryWindow window = deliveries.getIfPresent(integrationId);
        if (window != null) {
            window.remove(minute);
        }
    }

    /**
     * Warns at most once a minute per integration, so a refusal storm is one line.
     */
    private void warnCapacity(String integrationId, String why) {
        long now = clock.getAsLong();
        Long last = lastCapacityWarn.get(integrationId);
        if (last == null || now - last >= CAPACITY_WARN_INTERVAL_MS) {
            lastCapacityWarn.put(integrationId, now);
            if (lastCapacityWarn.size() > MAX_TRACKED_INTEGRATIONS) {
                lastCapacityWarn.values().removeIf(t -> now - t >= CAPACITY_WARN_INTERVAL_MS);
            }
            capacityWarnings.incrementAndGet();
            LOGGER.warnf("Webhook %s: deliveries are being refused (503) because %s", sanitize(integrationId), why);
        }
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
        // The common case (admitted) must cost no extra cache read - in a cluster each
        // one is a NATS round trip - so the repeat check only runs for a refusal.
        String refusal = refusalReason(integrationId, settings);
        if (refusal != null && keys.stream().noneMatch(key -> dedup.get(key) != null)) {
            warnCapacity(integrationId, refusal);
            // Reserving another key for an integration that is over its budget (or its
            // share of a nearly full cache) would end in evicting someone's key, and an
            // evicted key is a signed request that can be replayed. A repeat of
            // something already remembered still gets its answer; anything new from
            // THIS integration waits, and every other integration is unaffected.
            count(OUTCOME_CAPACITY);
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

        long countedIn = recordDelivery(integrationId);
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
        if (outcome.status() != 202) {
            unrecordDelivery(integrationId, countedIn);
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
            } catch (Exception refused) {
                // The turn was refused (429 on the API-call quota, 503, ...) after the
                // conversation was created, and the sender will retry with a new one:
                // end this one rather than leave one orphan per retry. (A correlated
                // conversation below is kept - the retry reuses it.)
                endQuietly(conversationId, "system:webhook-turn-refused");
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
                endQuietly(conversationId, "system:lost-create-race");
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

    private void endQuietly(String conversationId, String reason) {
        try {
            conversationService.endConversation(conversationId, reason);
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
