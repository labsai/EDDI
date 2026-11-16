/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health;

import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.security.ConversationReviewPolicy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Who may list an agent's single sessions (plan §6.4, contract §6) — the
 * conversation-review rule ({@link ConversationReviewPolicy}), applied to the
 * drill-down although its rows carry no content and no user id: they name
 * conversations, and reading conversations of other people is what review
 * governs.
 * <ul>
 * <li>the caller must <b>maintain</b> the agent ({@code EDIT} — the review
 * policy's reader rule) — else {@code 403 {"reason": "FORBIDDEN"}};</li>
 * <li>conversation review must be enabled on the version asked for
 * ({@code version=}), or — without one — on the agent's current version; else
 * {@code 403 {"reason": "REVIEW_DISABLED"}}. Without {@code version} the page
 * holds only sessions of versions that have review enabled (of the
 * {@link #MAX_VERSIONS} newest), so a session that ran while review was off is
 * never listed.</li>
 * </ul>
 * The caller's {@code VIEW} on the agent ({@code HealthAccess}) is checked
 * before; the outcome-mapping suggestions need only that.
 */
@ApplicationScoped
public class HealthDrillDownAccess {

    public static final String REASON_FORBIDDEN = "FORBIDDEN";
    public static final String REASON_REVIEW_DISABLED = "REVIEW_DISABLED";
    /** The newest versions looked at for their review setting. */
    static final int MAX_VERSIONS = 100;

    private final ConversationReviewPolicy reviewPolicy;
    private final IAgentStore agentStore;
    private final HealthConfigPolicy configPolicy;

    @Inject
    public HealthDrillDownAccess(ConversationReviewPolicy reviewPolicy, IAgentStore agentStore, HealthConfigPolicy configPolicy) {
        this.reviewPolicy = reviewPolicy;
        this.agentStore = agentStore;
        this.configPolicy = configPolicy;
    }

    /**
     * The versions whose sessions the caller may list; throws the contract's 403
     * otherwise.
     */
    public Set<Integer> reviewableVersions(String agentId, Integer version) {
        if (!reviewPolicy.maintains(agentId)) {
            throw forbidden(REASON_FORBIDDEN, "Listing sessions needs EDIT on the agent (the people who maintain it)");
        }
        if (version != null) {
            if (reviewPolicy.reviewOf(agentId, version).isEmpty()) {
                throw forbidden(REASON_REVIEW_DISABLED, "Conversation review is not enabled on version " + version + " of this agent");
            }
            return Set.of(version);
        }
        int current = currentVersion(agentId);
        if (reviewPolicy.reviewOf(agentId, current).isEmpty()) {
            throw forbidden(REASON_REVIEW_DISABLED, "Conversation review is not enabled on the agent's current version (" + current + ")");
        }
        Set<Integer> versions = new TreeSet<>();
        for (int v = current; v >= 1 && v > current - MAX_VERSIONS; v--) {
            if (reviewPolicy.reviewOf(agentId, v).isPresent()) {
                versions.add(v);
            }
        }
        return versions;
    }

    /** The actions the agent's current version maps to outcomes. */
    public Set<String> mappedActions(String agentId) {
        return configPolicy.of(agentId, currentVersion(agentId)).outcomes().keySet();
    }

    int currentVersion(String agentId) {
        try {
            IResourceStore.IResourceId id = agentStore.getCurrentResourceId(agentId);
            if (id == null || id.getVersion() == null) {
                throw new NotFoundException("Agent not found: " + agentId);
            }
            return id.getVersion();
        } catch (IResourceStore.ResourceNotFoundException e) {
            throw new NotFoundException("Agent not found: " + agentId);
        } catch (RuntimeException e) {
            if (e instanceof NotFoundException) {
                throw e;
            }
            throw new InternalServerErrorException("Could not read the agent", e);
        }
    }

    static ForbiddenException forbidden(String reason, String message) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("error", "forbidden");
        body.put("reason", reason);
        body.put("message", message);
        return new ForbiddenException(message, Response.status(Response.Status.FORBIDDEN).type(MediaType.APPLICATION_JSON_TYPE).entity(body).build());
    }
}
