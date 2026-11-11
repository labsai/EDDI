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
import ai.labs.eddi.integrations.channels.ChannelTargetRouter;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;

/**
 * Who may read and write which alert rules and events — one place, used by the
 * REST resources (and through them by the MCP tools), evaluated against the
 * request's identity.
 * <ul>
 * <li>Anyone who sees every agent
 * ({@link ResourceAccessGuard#seesEverything()}: an administrator, or anyone
 * while workspaces are not enforced) may read and act on every agent's rules
 * and events.</li>
 * <li>What the API reserves for administrators — webhook targets, their signing
 * secret references, and rules over several or every agent — needs the
 * administrator <em>role</em> ({@link ResourceAccessGuard#isAdmin()}).
 * Unrestricted visibility is not that role: with workspaces off it is every
 * editor's.</li>
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
    private final ChannelTargetRouter channelTargetRouter;

    @Inject
    public AlertAccess(ResourceAccessGuard accessGuard, ConversationAccessGuard conversationAccessGuard,
            ChannelTargetRouter channelTargetRouter) {
        this.accessGuard = accessGuard;
        this.conversationAccessGuard = conversationAccessGuard;
        this.channelTargetRouter = channelTargetRouter;
    }

    /**
     * Without a channel router: no non-administrator can name a Slack target
     * ({@link #requireDeliverable}).
     */
    public AlertAccess(ResourceAccessGuard accessGuard, ConversationAccessGuard conversationAccessGuard) {
        this(accessGuard, conversationAccessGuard, null);
    }

    /** Unrestricted visibility: every agent's rules and events. */
    public boolean isAdmin() {
        return accessGuard.seesEverything();
    }

    /**
     * The administrator role, for what the alert API reserves to administrators.
     * Not {@link #isAdmin()}: that is also true for every editor while workspaces
     * are not enforced, which let an editor add webhook targets signed with any
     * vault key.
     */
    public boolean hasAdminRole() {
        return accessGuard.isAdmin();
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
        if (hasAdminRole()) {
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

    /**
     * May the caller send this rule's alerts where it says? A Slack target posts
     * with the bot token of whichever integration serves the channel, so an editor
     * of one agent could otherwise post into any channel of any integration —
     * another team's included. Editors may name only a channel whose serving
     * integration targets the rule's agent; administrators (the role) any.
     * <p>
     * The refusal reads the same whether the channel is served by another
     * integration or by none, so it does not tell a caller which channels exist.
     * Checked on what is being stored or tested, not on a stored rule being
     * deleted, so a rule whose channel changed hands can still be removed.
     */
    public void requireDeliverable(AlertRule rule) {
        if (hasAdminRole() || rule == null) {
            return;
        }
        String agentId = rule.scope() == null ? null : rule.scope().singleAgent();
        for (DeliveryTarget target : rule.delivery()) {
            if (target == null || target.type() != DeliveryTarget.DeliveryType.SLACK) {
                continue;
            }
            if (channelTargetRouter == null || agentId == null
                    || !channelTargetRouter.channelTargetsAgent("slack", target.channelId(), agentId)) {
                throw AlertErrors.forbidden(AlertErrors.SLACK_CHANNEL_NOT_PERMITTED,
                        "Slack channel " + target.channelId() + " is not served by a Slack integration that targets this agent");
            }
        }
    }

    /** May the caller see or act on this event? */
    public void requireEvent(AlertEvent event) {
        requireAgent(event.agentId());
    }

    /**
     * The rule as a read returns it, to anyone — administrators included: a literal
     * webhook URL masked to scheme and host (see {@link DeliveryTarget#masked()}),
     * because the URL is often itself a bearer credential. A non-administrator also
     * does not get the signing secret's vault reference.
     */
    public AlertRule redact(AlertRule rule) {
        boolean admin = hasAdminRole();
        // Every read shows each target's id; a rule stored before ids existed gets
        // the derived ones, the same on every read until its next save stores them.
        List<DeliveryTarget> targets = rule.withTargetIds().delivery().stream().map(t -> {
            if (t == null || t.type() != DeliveryTarget.DeliveryType.WEBHOOK) {
                return t;
            }
            DeliveryTarget masked = t.masked();
            return admin
                    ? masked
                    : new DeliveryTarget(masked.type(), masked.channelId(), masked.url(), null, masked.urlConfigured(), masked.targetId());
        }).toList();
        return new AlertRule(rule.id(), rule.name(), rule.enabled(), rule.scope(), rule.source(), rule.condition(), targets, rule.throttle(),
                rule.digest(), rule.createdBy(), rule.createdAt(), rule.updatedBy(), rule.updatedAt());
    }

    static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
