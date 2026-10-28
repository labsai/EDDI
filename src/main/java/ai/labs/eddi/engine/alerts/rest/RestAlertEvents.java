/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.alerts.rest;

import ai.labs.eddi.engine.alerts.AlertDeliveryService;
import ai.labs.eddi.engine.alerts.AlertDispatcher;
import ai.labs.eddi.engine.alerts.IAlertEventStore;
import ai.labs.eddi.engine.alerts.IAlertRuleStore;
import ai.labs.eddi.engine.alerts.model.AlertEvent;
import ai.labs.eddi.engine.alerts.model.AlertEventFilter;
import ai.labs.eddi.engine.alerts.model.AlertSource;
import ai.labs.eddi.engine.api.AlertEventQueryParams;
import ai.labs.eddi.engine.api.IRestAlertEvents;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * @see IRestAlertEvents
 */
@ApplicationScoped
public class RestAlertEvents implements IRestAlertEvents {

    private static final Logger LOGGER = Logger.getLogger(RestAlertEvents.class);

    static final int MAX_PAGE_SIZE = 100;
    /** The most ids one bulk acknowledgement may name. */
    static final int MAX_BULK_IDS = 500;

    private final IAlertEventStore eventStore;
    private final IAlertRuleStore ruleStore;
    private final AlertDeliveryService deliveryService;
    private final AlertDispatcher dispatcher;
    private final AlertAccess access;
    private final Clock clock;

    @Inject
    public RestAlertEvents(IAlertEventStore eventStore, IAlertRuleStore ruleStore, AlertDeliveryService deliveryService,
            AlertDispatcher dispatcher, AlertAccess access) {
        this(eventStore, ruleStore, deliveryService, dispatcher, access, Clock.systemUTC());
    }

    RestAlertEvents(IAlertEventStore eventStore, IAlertRuleStore ruleStore, AlertDeliveryService deliveryService, AlertDispatcher dispatcher,
            AlertAccess access, Clock clock) {
        this.eventStore = eventStore;
        this.ruleStore = ruleStore;
        this.deliveryService = deliveryService;
        this.dispatcher = dispatcher;
        this.access = access;
        this.clock = clock;
    }

    @Override
    public EventPage listEvents(AlertEventQueryParams query, Integer index, Integer limit) {
        AlertEventFilter filter = filter(query);
        int start = index == null ? 0 : index;
        if (start < 0) {
            throw AlertErrors.badRequest(AlertErrors.INVALID_PARAMETER, "index must be 0 or greater");
        }
        int size = limit == null ? 20 : Math.max(1, Math.min(MAX_PAGE_SIZE, limit));
        return new EventPage(eventStore.count(filter), start, size, eventStore.list(filter, start, size));
    }

    @Override
    public UnacknowledgedCount unacknowledgedCount(String agentId) {
        return new UnacknowledgedCount(eventStore.count(AlertEventFilter.unacknowledged(access.requireScope(agentId))));
    }

    @Override
    public AlertEvent readEvent(String eventId) {
        AlertEvent event = existing(eventId);
        access.requireEvent(event);
        return event;
    }

    @Override
    public AlertEvent acknowledgeEvent(String eventId) {
        AlertEvent event = existing(eventId);
        access.requireEvent(event);
        eventStore.acknowledge(event.id(), access.caller(), clock.instant());
        return existing(eventId);
    }

    @Override
    public AcknowledgeResult acknowledgeEvents(AlertEventQueryParams query, AcknowledgeRequest request) {
        String caller = access.caller();
        Instant now = clock.instant();
        if (request != null && request.ids() != null) {
            // A present list is the selector, never a fallback to the filter: an empty
            // list (or a blank entry) would otherwise acknowledge everything matching.
            if (request.ids().isEmpty()) {
                throw AlertErrors.badRequest(AlertErrors.INVALID_PARAMETER,
                        "ids is empty; name at least one event, or leave ids out to acknowledge by filter");
            }
            if (request.ids().stream().anyMatch(id -> id == null || id.isBlank())) {
                throw AlertErrors.badRequest(AlertErrors.INVALID_PARAMETER, "ids must not contain empty entries");
            }
            LinkedHashSet<String> ids = new LinkedHashSet<>(request.ids());
            if (ids.size() > MAX_BULK_IDS) {
                throw AlertErrors.badRequest(AlertErrors.INVALID_PARAMETER, "At most " + MAX_BULK_IDS + " ids per request");
            }
            // Every id is checked before anything is acknowledged: all or nothing.
            List<String> permitted = new ArrayList<>();
            for (String id : ids) {
                AlertEvent event = existing(id);
                access.requireEvent(event);
                permitted.add(event.id());
            }
            return new AcknowledgeResult(eventStore.acknowledgeIds(permitted, caller, now));
        }
        AlertEventFilter filter = filter(query);
        long acknowledged = eventStore.acknowledgeMatching(filter, caller, now);
        LOGGER.infof("[ALERTS] '%s' acknowledged %d alert events (agentId=%s)", sanitize(caller), acknowledged,
                sanitize(filter.agentId() == null ? "*" : filter.agentId()));
        return new AcknowledgeResult(acknowledged);
    }

    @Override
    public AlertEvent retryEvent(String eventId) {
        AlertEvent event = existing(eventId);
        access.requireEvent(event);
        if (event.deliveries().stream().noneMatch(d -> d.status() == AlertEvent.DeliveryStatus.FAILED)) {
            throw AlertErrors.conflict(AlertErrors.NOTHING_TO_RETRY, "No delivery of this alert has failed");
        }
        AlertEvent reset = eventStore.updateDelivery(AlertDeliveryService.resetFailed(event, clock.instant()), event.version())
                .orElseThrow(() -> AlertErrors.conflict(AlertErrors.NOTHING_TO_RETRY, "The alert changed meanwhile; reload it"));
        // Deliver now on the alert worker; if the queue is full, the maintenance job
        // picks the event up (its next attempt is due now).
        dispatcher.submit(() -> deliveryService.deliver(reset, reset.ruleId() == null ? null : ruleStore.read(reset.ruleId()).orElse(null)));
        LOGGER.infof("[ALERTS] '%s' retried alert %s", sanitize(access.caller()), sanitize(eventId));
        return reset;
    }

    /** The filter of a request, after the scope check. */
    AlertEventFilter filter(AlertEventQueryParams query) {
        AlertEventQueryParams q = query == null ? new AlertEventQueryParams() : query;
        String agentId = access.requireScope(q.agentId);
        return new AlertEventFilter(agentId, AlertAccess.blankToNull(q.ruleId), parseEnum("source", q.source, AlertSource.class),
                q.acknowledged, parseEnum("state", q.state, AlertEvent.State.class), AlertAccess.blankToNull(q.conversationId),
                instant("from", q.from, false), instant("to", q.to, true));
    }

    private AlertEvent existing(String eventId) {
        return eventStore.read(eventId).orElseThrow(() -> AlertErrors.notFound(AlertErrors.EVENT_NOT_FOUND, "Alert event not found"));
    }

    static <E extends Enum<E>> E parseEnum(String name, String value, Class<E> type) {
        String text = AlertAccess.blankToNull(value);
        if (text == null) {
            return null;
        }
        try {
            return Enum.valueOf(type, text.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw AlertErrors.badRequest(AlertErrors.INVALID_PARAMETER, name + " has an unknown value");
        }
    }

    static Instant instant(String name, String value, boolean exclusiveEnd) {
        String text = AlertAccess.blankToNull(value);
        if (text == null) {
            return null;
        }
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException notAnInstant) {
            try {
                LocalDate date = LocalDate.parse(text);
                return (exclusiveEnd ? date.plusDays(1) : date).atStartOfDay(ZoneOffset.UTC).toInstant();
            } catch (DateTimeParseException notADate) {
                throw AlertErrors.badRequest(AlertErrors.INVALID_PARAMETER,
                        name + " must be an ISO-8601 instant (2026-10-01T00:00:00Z) or date (2026-10-01)");
            }
        }
    }
}
