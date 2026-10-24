/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.alerts.rest;

import ai.labs.eddi.configs.descriptors.model.AccessLevel;
import ai.labs.eddi.engine.alerts.model.AlertEvent;
import ai.labs.eddi.engine.alerts.model.AlertRule;
import ai.labs.eddi.engine.alerts.model.AlertScope;
import ai.labs.eddi.engine.alerts.model.DeliveryTarget;
import ai.labs.eddi.engine.security.ConversationAccessGuard;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.net.URI;
import java.util.List;

/**
 * Who may read and write which alert rules and events — one place, used by the
 * REST resources (and through them by the MCP tools), evaluated against the
 * request's identity.
 * <ul>
 * <li>An administrator ({@link ResourceAccessGuard#seesEverything()}) may do
 * anything.</li>
 * <li>Anyone else works on one agent at a time, holding {@code EDIT} on it: a
 * rule's scope must be exactly that agent and its targets Slack or the inbox;
 * an event must name that agent.</li>
 * </ul>
 */
@ApplicationScoped
public class AlertAccess {

    static final String ANONYMOUS = "anonymous";

    private final ResourceAccessGuard accessGuard;
    private final ConversationAccessGuard conversationAccessGuard;

    @Inject
    public AlertAccess(ResourceAccessGuard accessGuard, ConversationAccessGuard conversationAccessGuard) {
        this.accessGuard = accessGuard;
        this.conversationAccessGuard = conversationAccessGuard;
    }

    public boolean isAdmin() {
        return accessGuard.seesEverything();
    }

    /** The caller's name, for {@code createdBy} / {@code acknowledgedBy}. */
    public String caller() {
        return conversationAccessGuard.callerActor(ANONYMOUS);
    }

    /**
     * The agent a list or count is scoped to: {@code null} (everything) for an
     * administrator who names none; otherwise the named agent, which a
     * non-administrator must hold EDIT on.
     */
    public String requireScope(String agentId) {
        String id = blankToNull(agentId);
        if (id == null) {
            if (!isAdmin()) {
                throw AlertErrors.badRequest(AlertErrors.AGENT_ID_REQUIRED, "agentId is required");
            }
            return null;
        }
        requireAgent(id);
        return id;
    }

    /** EDIT on {@code agentId}, unless the caller is an administrator. */
    public void requireAgent(String agentId) {
        if (isAdmin()) {
            return;
        }
        if (agentId == null) {
            throw AlertErrors.forbidden(AlertErrors.NOT_PERMITTED, "Access denied");
        }
        // Throws io.quarkus.security.ForbiddenException (403) without EDIT.
        accessGuard.requireAccess(agentId, AccessLevel.EDIT, "agent");
    }

    /**
     * May the caller see this rule? (Administrators: any; others: single-agent
     * rules on an agent they hold EDIT on.)
     */
    public void requireReadable(AlertRule rule) {
        if (isAdmin()) {
            return;
        }
        String agentId = rule.scope() == null ? null : rule.scope().singleAgent();
        if (agentId == null) {
            throw AlertErrors.forbidden(AlertErrors.ADMIN_SCOPE_REQUIRED, "Rules over every agent or several agents are an administrator's");
        }
        requireAgent(agentId);
    }

    /**
     * May the caller store this rule (or change/delete the stored one)? Editors:
     * one agent they hold EDIT on, no webhook targets.
     */
    public void requireWritable(AlertRule rule) {
        if (isAdmin()) {
            return;
        }
        AlertScope scope = rule.scope();
        String agentId = scope == null ? null : scope.singleAgent();
        if (agentId == null) {
            throw AlertErrors.forbidden(AlertErrors.ADMIN_SCOPE_REQUIRED,
                    "Rules over every agent or several agents are an administrator's; scope the rule to one agent");
        }
        if (rule.delivery().stream().anyMatch(t -> t != null && t.type() == DeliveryTarget.DeliveryType.WEBHOOK)) {
            throw AlertErrors.forbidden(AlertErrors.WEBHOOK_ADMIN_ONLY, "Webhook targets are an administrator's; use Slack or the inbox");
        }
        requireAgent(agentId);
    }

    /** May the caller see or act on this event? */
    public void requireEvent(AlertEvent event) {
        requireAgent(event.agentId());
    }

    /**
     * The rule as this caller may see it: an administrator sees it whole; for
     * anyone else a webhook target shows only its scheme and host, and no secret
     * reference.
     */
    public AlertRule redact(AlertRule rule) {
        if (isAdmin() || rule.delivery().stream().noneMatch(t -> t != null && t.type() == DeliveryTarget.DeliveryType.WEBHOOK)) {
            return rule;
        }
        List<DeliveryTarget> targets = rule.delivery().stream()
                .map(t -> t != null && t.type() == DeliveryTarget.DeliveryType.WEBHOOK ? DeliveryTarget.webhook(origin(t.url()), null) : t)
                .toList();
        return new AlertRule(rule.id(), rule.name(), rule.enabled(), rule.scope(), rule.source(), rule.condition(), targets, rule.throttle(),
                rule.digest(), rule.createdBy(), rule.createdAt(), rule.updatedBy(), rule.updatedAt());
    }

    static String origin(String url) {
        try {
            URI uri = URI.create(url);
            return uri.getScheme() + "://" + uri.getHost() + "/…";
        } catch (RuntimeException e) {
            return "…";
        }
    }

    static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
