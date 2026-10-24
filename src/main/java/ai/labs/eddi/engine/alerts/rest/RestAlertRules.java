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
import java.util.List;
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
    public Response createRule(AlertRule rule) {
        validate(rule);
        access.requireWritable(rule);
        Instant now = clock.instant();
        String caller = access.caller();
        AlertRule stored = ruleStore.create(rule.withId(null).withAudit(caller, now, caller, now));
        ruleCache.invalidate();
        maintenanceSchedule.ensure();
        LOGGER.infof("[ALERTS] '%s' created alert rule %s (%s)", sanitize(caller), stored.id(), stored.source());
        return Response.created(URI.create("/alerts/rules/" + stored.id())).entity(stored).build();
    }

    @Override
    public AlertRule updateRule(String ruleId, AlertRule rule) {
        AlertRule current = existing(ruleId);
        access.requireWritable(current);
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
        return updated;
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
    public TestResult testRule(AlertRule rule) {
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
