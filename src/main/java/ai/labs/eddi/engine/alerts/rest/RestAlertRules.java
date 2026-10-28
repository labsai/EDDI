/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.alerts.rest;

import ai.labs.eddi.engine.alerts.AlertMaintenanceSchedule;
import ai.labs.eddi.engine.alerts.AlertRuleCache;
import ai.labs.eddi.engine.alerts.AlertRuleValidator;
import ai.labs.eddi.engine.alerts.AlertSenders;
import ai.labs.eddi.engine.alerts.AlertSenders.AlertDeliveryException;
import ai.labs.eddi.engine.alerts.IAlertRuleStore;
import ai.labs.eddi.engine.alerts.model.AlertEvent;
import ai.labs.eddi.engine.alerts.model.AlertMatch;
import ai.labs.eddi.engine.alerts.model.AlertRule;
import ai.labs.eddi.engine.alerts.model.DeliveryTarget;
import ai.labs.eddi.engine.api.IRestAlertRules;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * @see IRestAlertRules
 */
@ApplicationScoped
public class RestAlertRules implements IRestAlertRules {

    private static final Logger LOGGER = Logger.getLogger(RestAlertRules.class);

    static final int MAX_PAGE_SIZE = 100;

    private final IAlertRuleStore ruleStore;
    private final AlertRuleCache ruleCache;
    private final AlertAccess access;
    private final AlertSenders senders;
    private final AlertMaintenanceSchedule maintenanceSchedule;
    private final Clock clock;

    @Inject
    public RestAlertRules(IAlertRuleStore ruleStore, AlertRuleCache ruleCache, AlertAccess access, AlertSenders senders,
            AlertMaintenanceSchedule maintenanceSchedule) {
        this(ruleStore, ruleCache, access, senders, maintenanceSchedule, Clock.systemUTC());
    }

    RestAlertRules(IAlertRuleStore ruleStore, AlertRuleCache ruleCache, AlertAccess access, AlertSenders senders,
            AlertMaintenanceSchedule maintenanceSchedule, Clock clock) {
        this.ruleStore = ruleStore;
        this.ruleCache = ruleCache;
        this.access = access;
        this.senders = senders;
        this.maintenanceSchedule = maintenanceSchedule;
        this.clock = clock;
    }

    @Override
    public RulePage listRules(String agentId, Integer index, Integer limit) {
        String scoped = access.requireScope(agentId);
        int start = index == null ? 0 : index;
        if (start < 0) {
            throw AlertErrors.badRequest(AlertErrors.INVALID_PARAMETER, "index must be 0 or greater");
        }
        int size = limit == null ? 20 : Math.max(1, Math.min(MAX_PAGE_SIZE, limit));
        // An editor naming an agent sees the rules of that agent alone; a rule that
        // also lists other agents is an administrator's.
        boolean onlyThatAgent = !access.isAdmin();
        List<AlertRule> rules = new ArrayList<>();
        for (AlertRule rule : ruleStore.list(scoped, onlyThatAgent, start, size)) {
            rules.add(access.redact(rule));
        }
        return new RulePage(ruleStore.count(scoped, onlyThatAgent), start, size, rules);
    }

    @Override
    public AlertRule readRule(String ruleId) {
        AlertRule rule = existing(ruleId);
        access.requireReadable(rule);
        return access.redact(rule);
    }

    @Override
    public Response createRule(AlertRule body) {
        AlertRule rule = assignTargetIds(forStorage(body), null);
        validate(rule);
        access.requireWritable(rule);
        Instant now = clock.instant();
        String caller = access.caller();
        AlertRule stored = ruleStore.create(rule.withId(null).withAudit(caller, now, caller, now));
        ruleCache.invalidate();
        maintenanceSchedule.ensure();
        LOGGER.infof("[ALERTS] '%s' created alert rule %s (%s)", sanitize(caller), stored.id(), stored.source());
        return Response.created(URI.create("/alerts/rules/" + stored.id())).entity(access.redact(stored)).build();
    }

    @Override
    public AlertRule updateRule(String ruleId, AlertRule body) {
        AlertRule current = existing(ruleId);
        access.requireWritable(current);
        AlertRule rule = assignTargetIds(restoreUrls(forStorage(body), current), current);
        validate(rule);
        access.requireWritable(rule);
        String caller = access.caller();
        AlertRule updated = rule.withId(current.id()).withAudit(current.createdBy(), current.createdAt(), caller, clock.instant());
        if (!ruleStore.update(updated)) {
            throw AlertErrors.notFound(AlertErrors.RULE_NOT_FOUND, "Alert rule not found");
        }
        ruleCache.invalidate();
        maintenanceSchedule.ensure();
        LOGGER.infof("[ALERTS] '%s' updated alert rule %s", sanitize(caller), sanitize(ruleId));
        return access.redact(updated);
    }

    @Override
    public Response deleteRule(String ruleId) {
        AlertRule current = existing(ruleId);
        access.requireWritable(current);
        ruleStore.delete(current.id());
        ruleCache.invalidate();
        LOGGER.infof("[ALERTS] '%s' deleted alert rule %s", sanitize(access.caller()), sanitize(ruleId));
        return Response.noContent().build();
    }

    @Override
    public TestResult testRule(AlertRule body) {
        AlertRule rule = forStorage(body);
        if (rule == null) {
            throw AlertErrors.badRequest(AlertErrors.INVALID_RULE, "A rule is required");
        }
        if (rule.id() != null) {
            // Testing a saved rule from the editor sends its URLs back masked: fill
            // them in from the stored rule, which the caller must be allowed to change.
            AlertRule current = ruleStore.read(rule.id()).orElse(null);
            if (current != null) {
                access.requireWritable(current);
                rule = restoreUrls(rule, current);
            }
        }
        validate(rule);
        access.requireWritable(rule);
        String agentId = rule.scope().singleAgent();
        Instant now = clock.instant();
        String summary = "Test alert from rule \"" + rule.name() + "\" — sent from the Manager; nothing happened.";
        AlertEvent test = new AlertEvent("test-" + UUID.randomUUID(), rule.id(), rule.name(), rule.source(), AlertEvent.Kind.ALERT, agentId,
                null, null, now, summary, new AlertMatch(List.of("test"), null, null, null, null, null, null, null, null), List.of(),
                AlertEvent.State.INBOX_ONLY, null, null, 0, null, null, null, null, 0);
        List<TestDelivery> results = new ArrayList<>();
        for (DeliveryTarget target : rule.delivery()) {
            try {
                senders.send(test, target);
                results.add(new TestDelivery(target.type(), target.label(), true, null));
            } catch (AlertDeliveryException e) {
                results.add(new TestDelivery(target.type(), target.label(), false, e.getMessage()));
            }
        }
        return new TestResult(results);
    }

    /**
     * The body without the read-only {@code urlConfigured} flags, which are never
     * stored.
     */
    static AlertRule forStorage(AlertRule rule) {
        if (rule == null) {
            return null;
        }
        List<DeliveryTarget> targets = rule.delivery().stream().map(t -> t == null ? null : t.forStorage()).toList();
        return withDelivery(rule, targets);
    }

    /**
     * Puts the stored URL back into every webhook target the client sent without
     * one or with the masked value a read returned — from the stored target with
     * the same {@code targetId}, and only from that one. A target without an id, or
     * with an id the stored rule does not have, gets nothing back: its masked or
     * empty URL then fails validation. A new value replaces the stored one.
     */
    static AlertRule restoreUrls(AlertRule rule, AlertRule current) {
        if (rule == null || current == null) {
            return rule;
        }
        Map<String, DeliveryTarget> stored = new HashMap<>();
        for (DeliveryTarget target : current.withTargetIds().delivery()) {
            if (target != null) {
                stored.putIfAbsent(target.targetId(), target);
            }
        }
        List<DeliveryTarget> targets = new ArrayList<>();
        for (DeliveryTarget t : rule.delivery()) {
            boolean needsStoredUrl = t != null && t.type() == DeliveryTarget.DeliveryType.WEBHOOK
                    && (t.url() == null || t.url().isBlank() || DeliveryTarget.isMaskedUrl(t.url()));
            DeliveryTarget same = needsStoredUrl && t.targetId() != null ? stored.get(t.targetId()) : null;
            boolean restorable = same != null && same.type() == DeliveryTarget.DeliveryType.WEBHOOK && same.url() != null
                    && !same.url().isBlank() && !DeliveryTarget.isMaskedUrl(same.url());
            targets.add(restorable ? t.withUrl(same.url()) : t);
        }
        return withDelivery(rule, targets);
    }

    /**
     * Ids are the server's: an id the stored rule does not have (or any id on a
     * create), or one used twice in the body, is dropped, and every target without
     * an id gets one. Runs after {@link #restoreUrls}, so a dropped id cannot pull
     * a stored URL.
     */
    static AlertRule assignTargetIds(AlertRule rule, AlertRule current) {
        if (rule == null) {
            return null;
        }
        Set<String> known = new HashSet<>();
        if (current != null) {
            for (DeliveryTarget target : current.withTargetIds().delivery()) {
                if (target != null) {
                    known.add(target.targetId());
                }
            }
        }
        Set<String> seen = new HashSet<>();
        List<DeliveryTarget> targets = new ArrayList<>();
        for (DeliveryTarget t : rule.delivery()) {
            if (t == null) {
                targets.add(null);
                continue;
            }
            boolean keep = t.targetId() != null && known.contains(t.targetId()) && seen.add(t.targetId());
            targets.add(keep ? t : t.withTargetId(null));
        }
        return withDelivery(rule, targets).withTargetIds();
    }

    private static AlertRule withDelivery(AlertRule rule, List<DeliveryTarget> targets) {
        return new AlertRule(rule.id(), rule.name(), rule.enabled(), rule.scope(), rule.source(), rule.condition(), targets,
                rule.throttle(), rule.digest(), rule.createdBy(), rule.createdAt(), rule.updatedBy(), rule.updatedAt());
    }

    private AlertRule existing(String ruleId) {
        return ruleStore.read(ruleId).orElseThrow(() -> AlertErrors.notFound(AlertErrors.RULE_NOT_FOUND, "Alert rule not found"));
    }

    private static void validate(AlertRule rule) {
        List<String> problems = AlertRuleValidator.validate(rule);
        if (!problems.isEmpty()) {
            throw AlertErrors.badRequest(AlertErrors.INVALID_RULE, String.join("; ", problems));
        }
    }
}
