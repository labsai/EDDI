/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health;

import ai.labs.eddi.engine.health.HealthConfigPolicy.Resolved;
import ai.labs.eddi.engine.health.model.HealthEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Takes in the business events of a {@code health-outcomes} webhook target
 * (plan §5.2, H5). The webhook endpoint has already verified the signature,
 * deduplicated the delivery and routed it here; this validates each event,
 * binds it to the target's agents and stores it {@link HealthEvent#PENDING} for
 * {@link HealthEventMatcher}.
 * <p>
 * <b>Body</b> (the event's {@code data} — plain JSON, or a CloudEvent's data):
 * one event object, an array of them, or {@code {"events": [...]}}; at most
 * {@code eddi.health.events.max-per-request} (100). Each event:
 *
 * <pre>
 * { "agentId": "…", "ref": { "orderId": "A-123" } | "conversationId": "…",
 *   "event": "order.placed", "value": 49.90, "at": "2026-11-10T09:12:00Z", "id": "evt-1" }
 * </pre>
 *
 * {@code agentId} may be left out when the target binds exactly one agent;
 * {@code event} defaults to the CloudEvent / webhook event type; {@code at} to
 * the receipt; {@code id} (the sender's id) makes a redelivery idempotent —
 * without it, an event identical in every field is stored once.
 * <p>
 * <b>Refused per event</b> (the others are stored): an agent the target does
 * not bind ({@code agent_not_bound} — a leaked secret can only feed the agents
 * it was set up for), neither or both of {@code ref} and
 * {@code conversationId}, a reference name the agent's current version does not
 * declare in {@code healthConfig.externalRefs} ({@code ref_not_declared}), an
 * event type that is not one, {@code at} more than five minutes in the future
 * or older than the turn-row retention, an agent whose unmatched backlog is
 * full ({@code agent_backlog_full}). A reference the agent hashes is hashed
 * here and never stored in plain; when no hash can be made the event is refused
 * ({@code hashing_unavailable}).
 */
@ApplicationScoped
public class HealthEventIngest {

    public static final String REASON_AGENT_NOT_BOUND = "agent_not_bound";
    public static final String REASON_AGENT_MISSING = "agent_missing";
    public static final String REASON_EVENT_MISSING = "event_missing";
    public static final String REASON_EVENT_INVALID = "event_invalid";
    public static final String REASON_TARGET_MISSING = "ref_or_conversation_required";
    public static final String REASON_REF_INVALID = "ref_invalid";
    public static final String REASON_REF_NOT_DECLARED = "ref_not_declared";
    public static final String REASON_CONVERSATION_INVALID = "conversation_id_invalid";
    public static final String REASON_VALUE_INVALID = "value_invalid";
    public static final String REASON_AT_INVALID = "at_invalid";
    public static final String REASON_ID_INVALID = "id_invalid";
    public static final String REASON_NOT_AN_OBJECT = "not_an_object";
    public static final String REASON_BACKLOG_FULL = "agent_backlog_full";
    public static final String REASON_HASHING_UNAVAILABLE = "hashing_unavailable";

    /** How far in the future an event's {@code at} may lie (clock skew). */
    static final Duration FUTURE_SKEW = Duration.ofMinutes(5);
    static final Pattern CONVERSATION_ID = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");
    static final int MAX_ID_LENGTH = 128;

    /**
     * What an ingest answered.
     *
     * @param status
     *            202 when at least one event was stored (or was a duplicate), 429
     *            when every refusal was a full backlog, 413 for too many events,
     *            503 when Agent Health is off, else 422
     */
    public record Result(int status, int accepted, int duplicates, List<Map<String, Object>> refused, String error) {
        public Map<String, Object> body() {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("accepted", accepted);
            body.put("duplicates", duplicates);
            body.put("refused", refused);
            return body;
        }
    }

    private final IHealthEventStore store;
    private final HealthConfigPolicy configPolicy;
    private final HealthRefHasher hasher;
    private final HealthSettings settings;
    private final HealthMetrics metrics;
    private final Clock clock;

    @Inject
    public HealthEventIngest(IHealthEventStore store, HealthConfigPolicy configPolicy, HealthRefHasher hasher, HealthSettings settings,
            HealthMetrics metrics) {
        this(store, configPolicy, hasher, settings, metrics, Clock.systemUTC());
    }

    HealthEventIngest(IHealthEventStore store, HealthConfigPolicy configPolicy, HealthRefHasher hasher, HealthSettings settings,
            HealthMetrics metrics,
            Clock clock) {
        this.store = store;
        this.configPolicy = configPolicy;
        this.hasher = hasher;
        this.settings = settings;
        this.metrics = metrics;
        this.clock = clock;
    }

    /**
     * @param integrationId
     *            the webhook integration the request arrived through
     * @param agentIds
     *            the agents the target binds
     * @param eventType
     *            the webhook / CloudEvent type — the default {@code event}
     * @param data
     *            the event's data: an object, an array, or {@code {"events": […]}}
     */
    public Result ingest(String integrationId, Collection<String> agentIds, String eventType, Object data) {
        if (!settings.enabled()) {
            return new Result(503, 0, 0, List.of(), "Agent Health is disabled on this deployment.");
        }
        List<Object> items = items(data);
        if (items == null) {
            return new Result(422, 0, 0, List.of(), "The body must be an event object, an array of them, or {\"events\": [...]}.");
        }
        if (items.isEmpty()) {
            return new Result(422, 0, 0, List.of(), "The body holds no event.");
        }
        if (items.size() > settings.maxEventsPerRequest()) {
            return new Result(413, 0, 0, List.of(), "At most " + settings.maxEventsPerRequest() + " events per request.");
        }
        List<String> bound = agentIds == null ? List.of() : agentIds.stream().filter(id -> id != null && !id.isBlank()).toList();
        Instant now = clock.instant();
        List<Map<String, Object>> refused = new ArrayList<>();
        List<HealthEvent> accepted = new ArrayList<>();
        Map<String, Resolved> configs = new HashMap<>();
        Map<String, Long> backlog = new HashMap<>();
        boolean onlyBacklog = true;
        for (int i = 0; i < items.size(); i++) {
            String reason;
            Object item = items.get(i);
            try {
                HealthEvent event = parse(integrationId, bound, eventType, item, now, configs);
                long pending = backlog.computeIfAbsent(event.agentId(), store::countPending);
                if (pending >= settings.maxPendingEventsPerAgent()) {
                    throw new Refusal(REASON_BACKLOG_FULL, "The agent has " + pending + " unmatched events waiting (the most is "
                            + settings.maxPendingEventsPerAgent() + ")");
                }
                backlog.put(event.agentId(), pending + 1);
                accepted.add(event);
                continue;
            } catch (Refusal r) {
                reason = r.reason;
                onlyBacklog &= REASON_BACKLOG_FULL.equals(r.reason);
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("index", i);
                entry.put("reason", reason);
                entry.put("message", r.getMessage());
                refused.add(entry);
            }
        }
        metrics.events(HealthMetrics.EVENT_REFUSED, refused.size());
        int inserted = accepted.isEmpty() ? 0 : store.insert(accepted);
        int duplicates = accepted.size() - inserted;
        metrics.events(HealthMetrics.EVENT_ACCEPTED, inserted);
        metrics.events(HealthMetrics.EVENT_DUPLICATE, duplicates);
        int status = !accepted.isEmpty() ? 202 : onlyBacklog ? 429 : 422;
        return new Result(status, inserted, duplicates, refused, accepted.isEmpty() ? "No event was accepted." : null);
    }

    /** The events of a body, or {@code null} when it has none of the shapes. */
    static List<Object> items(Object data) {
        if (data instanceof List<?> list) {
            return new ArrayList<>(list);
        }
        if (data instanceof Map<?, ?> map) {
            if (map.containsKey("events") && map.get("events") instanceof List<?> list && !map.containsKey("event")) {
                return new ArrayList<>(list);
            }
            return List.of(map);
        }
        return null;
    }

    private HealthEvent parse(String integrationId, List<String> bound, String eventType, Object item, Instant now, Map<String, Resolved> configs) {
        if (!(item instanceof Map<?, ?> map)) {
            throw new Refusal(REASON_NOT_AN_OBJECT, "An event must be a JSON object");
        }
        String agentId = string(map.get("agentId"));
        if (agentId == null) {
            if (bound.size() != 1) {
                throw new Refusal(REASON_AGENT_MISSING, "agentId is required: the target binds " + bound.size() + " agents");
            }
            agentId = bound.get(0);
        }
        if (!bound.contains(agentId)) {
            throw new Refusal(REASON_AGENT_NOT_BOUND, "The target does not bind this agent (its agentIds)");
        }
        String type = string(map.get("event"));
        if (type == null) {
            type = eventType == null || "webhook".equals(eventType) ? null : eventType;
        }
        if (type == null) {
            throw new Refusal(REASON_EVENT_MISSING, "event is required (the business event type, e.g. order.placed)");
        }
        if (!HealthConfigValidator.EVENT_TYPE.matcher(type).matches()) {
            throw new Refusal(REASON_EVENT_INVALID, "event is not an event type (letters, digits, _ . : / -, at most 128 characters)");
        }
        Object refNode = map.get("ref");
        String conversationId = string(map.get("conversationId"));
        if ((refNode == null) == (conversationId == null)) {
            throw new Refusal(REASON_TARGET_MISSING, "Exactly one of ref and conversationId is required");
        }
        String ref = null;
        String refValue = null;
        if (refNode != null) {
            if (!(refNode instanceof Map<?, ?> refMap) || refMap.size() != 1) {
                throw new Refusal(REASON_REF_INVALID, "ref must be an object with exactly one reference, e.g. {\"orderId\": \"A-123\"}");
            }
            var entry = refMap.entrySet().iterator().next();
            String name = entry.getKey() == null ? null : String.valueOf(entry.getKey());
            Object raw = entry.getValue();
            String value = raw instanceof String s ? s : raw instanceof Number n ? numberText(n) : null;
            if (name == null || value == null || !HealthRefs.recordable(value)) {
                throw new Refusal(REASON_REF_INVALID, "The reference value must be a string or number of at most " + HealthRefs.MAX_VALUE_LENGTH
                        + " characters");
            }
            final String agent = agentId;
            Resolved config = configs.computeIfAbsent(agent, configPolicy::current);
            if (!config.externalRefs().contains(name)) {
                throw new Refusal(REASON_REF_NOT_DECLARED, "The agent's current version does not declare '" + name
                        + "' in healthConfig.externalRefs");
            }
            if (config.hashRefs()) {
                String hex = hasher == null ? null : hasher.hash(name, value);
                if (hex == null) {
                    throw new Refusal(REASON_HASHING_UNAVAILABLE, "The reference cannot be hashed right now (no salt available)");
                }
                ref = HealthRefs.hashed(name, hex);
            } else {
                ref = HealthRefs.plain(name, value);
                refValue = value;
            }
        } else if (!CONVERSATION_ID.matcher(conversationId).matches()) {
            throw new Refusal(REASON_CONVERSATION_INVALID, "conversationId is not a conversation id");
        }
        Double value = null;
        Object rawValue = map.get("value");
        if (rawValue != null) {
            if (!(rawValue instanceof Number n) || !Double.isFinite(n.doubleValue())) {
                throw new Refusal(REASON_VALUE_INVALID, "value must be a finite number");
            }
            value = n.doubleValue();
        }
        Instant at = now;
        Object rawAt = map.get("at");
        if (rawAt != null) {
            try {
                at = Instant.parse(String.valueOf(rawAt));
            } catch (DateTimeParseException e) {
                throw new Refusal(REASON_AT_INVALID, "at must be an ISO-8601 instant, e.g. 2026-11-10T09:12:00Z");
            }
            if (at.isAfter(now.plus(FUTURE_SKEW))) {
                throw new Refusal(REASON_AT_INVALID, "at lies in the future");
            }
            if (at.isBefore(now.minus(settings.factsRetention()))) {
                throw new Refusal(REASON_AT_INVALID, "at is older than the turn-row retention (" + settings.factsRetentionDays() + " days)");
            }
        }
        String senderId = string(map.get("id"));
        if (senderId != null && senderId.length() > MAX_ID_LENGTH) {
            throw new Refusal(REASON_ID_INVALID, "id must be at most " + MAX_ID_LENGTH + " characters");
        }
        String identity = senderId != null
                ? "id\n" + integrationId + "\n" + agentId + "\n" + senderId
                : "item\n" + integrationId + "\n" + agentId + "\n" + type + "\n" + (ref != null ? ref : "conversation:" + conversationId) + "\n"
                        + (value == null ? "" : value) + "\n" + (rawAt == null ? "" : String.valueOf(rawAt));
        return new HealthEvent(sha256(identity), integrationId, agentId, type, ref, refValue, conversationId, value, at, now,
                LocalDate.ofInstant(at, ZoneOffset.UTC), HealthEvent.PENDING, null, null, null, null, null, null);
    }

    private static String numberText(Number n) {
        double d = n.doubleValue();
        if (!Double.isFinite(d)) {
            return null;
        }
        return d == Math.rint(d) && Math.abs(d) < 1e15 ? String.valueOf((long) d) : String.valueOf(d);
    }

    private static String string(Object value) {
        if (value instanceof String s && !s.isBlank()) {
            return s.trim();
        }
        return null;
    }

    static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** One event refused, with its machine-readable reason. */
    static final class Refusal extends RuntimeException {
        private final String reason;

        Refusal(String reason, String message) {
            super(message, null, false, false);
            this.reason = reason;
        }
    }
}
