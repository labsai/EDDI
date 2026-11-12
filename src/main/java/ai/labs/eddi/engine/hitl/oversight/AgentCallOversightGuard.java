/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl.oversight;

import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.vertx.http.runtime.CurrentVertxRequest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.Map;
import java.util.regex.Pattern;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * Refuses an agent-store write that changes an agent's
 * {@code hitlConfig.oversight} when the inbound request is marked as an agent's
 * own tool call ({@link AgentCallMarker#HEADER}).
 * <p>
 * {@code SelfUngatingGuard} applies {@link OversightWriteGuard} to an approved
 * tool call before it is sent. A call that needed no approval — an httpcall to
 * EDDI's own {@code PUT /agentstore/agents/{id}} — never passes through it, so
 * an agent could lower its own sample rate or loosen its own autonomy policy.
 * The marker survives to the receiving request, and this guard checks it there,
 * with the same comparison: the submitted oversight block must equal the stored
 * one (removing it counts as a change).
 * <p>
 * Enforced in the store's write method rather than a request filter, so an
 * in-process caller acting for a marked request (an MCP tool) is covered by the
 * same check.
 *
 * @since 6.8.0
 */
@ApplicationScoped
public class AgentCallOversightGuard {

    private static final Logger LOGGER = Logger.getLogger(AgentCallOversightGuard.class);

    /** Matches the synthetic target handed to {@link OversightWriteGuard}. */
    private static final Pattern AGENT_TARGET = Pattern.compile("^/agentstore/agents/(target)$");

    private static final String SYNTHETIC_TARGET = "/agentstore/agents/target";

    private final CurrentVertxRequest currentVertxRequest;

    @Inject
    public AgentCallOversightGuard(CurrentVertxRequest currentVertxRequest) {
        this.currentVertxRequest = currentVertxRequest;
    }

    /** Reads the stored version a write replaces; null when it has none. */
    @FunctionalInterface
    public interface StoredAgent {
        AgentConfiguration read() throws Exception;
    }

    /** Whether the active HTTP request carries the agent-call marker. */
    public boolean isAgentOriginated() {
        try {
            var current = currentVertxRequest != null ? currentVertxRequest.getCurrent() : null;
            return current != null && current.request().getHeader(AgentCallMarker.HEADER) != null;
        } catch (RuntimeException e) {
            LOGGER.debugf("No active request to read %s from: %s", AgentCallMarker.HEADER, sanitize(e.getMessage()));
            return false;
        }
    }

    /**
     * Throws 403 when the active request is an agent's own call and
     * {@code submitted} changes the stored {@code hitlConfig.oversight}.
     */
    public void requireNoOversightChange(String agentId, AgentConfiguration submitted, StoredAgent stored) {
        if (!isAgentOriginated()) {
            return;
        }
        String refusal = refusal(agentId, submitted, stored);
        if (refusal != null) {
            LOGGER.warnf("Refused an agent-originated write to agent '%s': %s", sanitize(agentId), refusal);
            throw new ForbiddenException(refusal,
                    Response.status(Response.Status.FORBIDDEN).entity(Map.of("error", refusal)).build());
        }
    }

    /**
     * The refusal for an agent-originated write of {@code submitted} over the
     * stored agent, or null. {@link OversightWriteGuard}'s own comparison, fed the
     * two documents serialised the same way.
     */
    static String refusal(String agentId, AgentConfiguration submitted, StoredAgent stored) {
        if (agentId == null || submitted == null) {
            return null;
        }
        String body;
        try {
            body = OversightJson.mapper().writeValueAsString(submitted);
        } catch (Exception e) {
            // Unreadable here means unverifiable: refused, never admitted.
            return OversightWriteGuard.OVERSIGHT_CONFIG_REASON;
        }
        // A fixed synthetic target: the id is the caller's path segment, and the stored
        // document is already the one for it.
        return OversightWriteGuard.refusal(SYNTHETIC_TARGET, body, AGENT_TARGET, id -> storedHitlConfig(stored));
    }

    private static JsonNode storedHitlConfig(StoredAgent stored) throws Exception {
        AgentConfiguration current = stored != null ? stored.read() : null;
        if (current == null || current.getHitlConfig() == null) {
            return null;
        }
        return OversightJson.mapper().valueToTree(current.getHitlConfig());
    }
}
