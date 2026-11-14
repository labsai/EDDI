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
import ai.labs.eddi.engine.alerts.AlertSettings;
import ai.labs.eddi.engine.alerts.IAlertEventStore;
import ai.labs.eddi.engine.alerts.IAlertRuleStore;
import ai.labs.eddi.engine.alerts.model.AlertEvent;
import ai.labs.eddi.engine.alerts.model.AlertMatch;
import ai.labs.eddi.engine.alerts.model.AlertRule;
import ai.labs.eddi.engine.alerts.model.AlertScope;
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
    private final IAlertEventStore eventStore;
    private final AlertRuleCache ruleCache;
    private final AlertAccess access;
    private final AlertSenders senders;
    private final AlertMaintenanceSchedule maintenanceSchedule;
    private final AlertSettings settings;
    private final Clock clock;

    /**
     * Bounds test sends per caller. Field-injected so the constructors stay the
     * ones the tests use; null there, and then tests are not throttled.
     */
    @Inject
    AlertTestThrottle testThrottle;

    @Inject
    public RestAlertRules(IAlertRuleStore ruleStore, IAlertEventStore eventStore, AlertRuleCache ruleCache, AlertAccess access,
            AlertSenders senders, AlertMaintenanceSchedule maintenanceSchedule, AlertSettings settings) {
        this(ruleStore, eventStore, ruleCache, access, senders, maintenanceSchedule, settings, Clock.systemUTC());
    }

    RestAlertRules(IAlertRuleStore ruleStore, IAlertEventStore eventStore, AlertRuleCache ruleCache, AlertAccess access, AlertSenders senders,
            AlertMaintenanceSchedule maintenanceSchedule, AlertSettings settings, Clock clock) {
        this.ruleStore = ruleStore;
        this.eventStore = eventStore;
        this.ruleCache = ruleCache;
        this.access = access;
        this.senders = senders;
        this.maintenanceSchedule = maintenanceSchedule;
        this.settings = settings;
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
        access.requireDeliverable(rule);
        rule = access.withAdmission(rule);
        requireRuleCapacity(rule, null);
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
        rule = admitted(rule, current);
        requireRuleCapacity(rule, current);
        String caller = access.caller();
        AlertRule updated = rule.withId(current.id()).withAudit(current.createdBy(), current.createdAt(), caller, clock.instant());
        if (!ruleStore.update(updated)) {
            throw AlertErrors.notFound(AlertErrors.RULE_NOT_FOUND, "Alert rule not found");
        }
        ruleCache.invalidate();
        if (!updated.enabled() || updated.effectiveDigest() == AlertRule.DigestMode.NONE) {
            // The maintenance pass digests enabled digest rules only; what this rule
            // held back would otherwise stay DIGEST_PENDING until retention.
            eventStore.releaseDigestPending(updated.id());
        }
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
        eventStore.releaseDigestPending(current.id());
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
        access.requireDeliverable(rule);
        if (testThrottle != null && !testThrottle.tryAcquire(access.caller())) {
            throw AlertErrors.tooManyRequests(AlertErrors.TEST_THROTTLED,
                    "At most " + testThrottle.maxPerMinute() + " test alerts per minute; try again shortly");
        }
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
     * An update's rule, its Slack targets checked and marked
     * ({@link AlertAccess#withAdmission}). An enabled rule must be deliverable as a
     * whole. A disabled one only for the Slack channels it adds: switching a rule
     * off must stay possible after its channel has changed hands (the alternative
     * would be deleting it), and a channel it already had keeps the mark it was
     * saved with, so the rule's pending retries are judged as before.
     */
    private AlertRule admitted(AlertRule rule, AlertRule current) {
        if (rule.enabled()) {
            access.requireDeliverable(rule);
            return access.withAdmission(rule);
        }
        Map<String, DeliveryTarget> kept = new HashMap<>();
        for (DeliveryTarget target : current.delivery()) {
            if (target != null && target.type() == DeliveryTarget.DeliveryType.SLACK) {
                kept.putIfAbsent(target.channelId(), target);
            }
        }
        List<DeliveryTarget> added = rule.delivery().stream()
                .filter(t -> t == null || t.type() != DeliveryTarget.DeliveryType.SLACK || !kept.containsKey(t.channelId())).toList();
        access.requireDeliverable(withDelivery(rule, added));
        List<DeliveryTarget> marked = access.withAdmission(rule).delivery().stream()
                .map(t -> t != null && t.type() == DeliveryTarget.DeliveryType.SLACK && kept.containsKey(t.channelId())
                        ? t.withAdminAdmitted(kept.get(t.channelId()).adminAdmitted())
                        : t)
                .toList();
        return withDelivery(rule, marked);
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

    /**
     * Refuses a save that would give an agent more than
     * {@code eddi.alerts.max-rules-per-agent} rules listing it, or the rules over
     * every agent more than as many — each of them is evaluated on every rating and
     * failed turn of the agents it watches. Only what the save adds to
     * {@code current} is counted, so rules already over a lowered limit stay
     * editable. Runs after the access checks: a caller without access learns
     * nothing about another agent's rules.
     */
    private void requireRuleCapacity(AlertRule rule, AlertRule current) {
        int max = settings.maxRulesPerAgent();
        AlertScope before = current == null ? null : current.scope();
        AlertScope scope = rule.scope();
        if (scope.allAgents()) {
            if (before == null || !before.allAgents()) {
                long existing = ruleStore.countAllAgents();
                if (existing >= max) {
                    throw AlertErrors.conflict(AlertErrors.RULE_LIMIT_REACHED, "There are already " + existing
                            + " alert rules over every agent; at most " + max + " (eddi.alerts.max-rules-per-agent)");
                }
            }
            return;
        }
        for (String agentId : scope.agentIds()) {
            if (before != null && before.agentIds().contains(agentId)) {
                continue;
            }
            long existing = ruleStore.count(agentId, false);
            if (existing >= max) {
                throw AlertErrors.conflict(AlertErrors.RULE_LIMIT_REACHED, "Agent " + agentId + " already has " + existing
                        + " alert rules; at most " + max + " per agent (eddi.alerts.max-rules-per-agent)");
            }
        }
    }

    private static void validate(AlertRule rule) {
        List<String> problems = AlertRuleValidator.validate(rule);
        if (!problems.isEmpty()) {
            throw AlertErrors.badRequest(AlertErrors.INVALID_RULE, String.join("; ", problems));
        }
    }
}
