/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mcp;

import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.api.IRestAgentHealth;
import ai.labs.eddi.engine.api.IRestHealthPortfolio;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.ClientErrorException;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static ai.labs.eddi.engine.mcp.McpToolUtils.codedFailure;
import static ai.labs.eddi.engine.mcp.McpToolUtils.errorJson;
import static ai.labs.eddi.engine.mcp.McpToolUtils.requireAnyRole;

/**
 * MCP tools for Agent Health — read-only: one agent's health report, the
 * portfolio of every agent the caller may view, a version-to-version compare,
 * the session drill-down and outcome-mapping suggestions.
 * <p>
 * <b>Same rule as REST, applied to the MCP caller.</b> The tools call the
 * in-process resource beans ({@code IRestAgentHealth},
 * {@code IRestHealthPortfolio}), whose {@code ResourceAccessGuard} reads the
 * request's {@link SecurityIdentity} — here the MCP caller: VIEW on the agent
 * for the report and the compare, only viewable agents in the portfolio, and
 * the quality section {@code restricted} for callers who may not read ratings,
 * and for the session list EDIT on the agent plus conversation review enabled
 * on the version.
 * <p>
 * Tier {@link McpRoles#HEALTH_READ} (admin, editor, viewer), equal to the REST
 * {@code @RolesAllowed}.
 *
 * @since 6.7.0
 */
@ApplicationScoped
@McpErrorResults
public class McpHealthTools {

    private static final Logger LOGGER = Logger.getLogger(McpHealthTools.class);

    private static final String ORIGINS_ARG = "Comma-separated origins to include (api, v1, mcp, email, slack, webhook, schedule, a2a, "
            + "group-member, delegated, agent-test). Default: the interactive ones (or all but agent-test for an agent driven only by "
            + "schedules, webhooks, A2A or groups). agent-test is included only when named";

    private final IRestAgentHealth health;
    private final IRestHealthPortfolio portfolio;
    private final IJsonSerialization jsonSerialization;
    private final SecurityIdentity identity;
    private final boolean authEnabled;

    @Inject
    public McpHealthTools(IRestAgentHealth health, IRestHealthPortfolio portfolio, IJsonSerialization jsonSerialization, SecurityIdentity identity,
            @ConfigProperty(name = "authorization.enabled", defaultValue = "false") boolean authEnabled) {
        this.health = health;
        this.portfolio = portfolio;
        this.jsonSerialization = jsonSerialization;
        this.identity = identity;
        this.authEnabled = authEnabled;
    }

    @Tool(name = "read_agent_health", description = "Read an agent's health over a period: reliability (agent-caused vs. provider/platform "
            + "failures, Apdex response time, p95 duration, fallback rate), efficiency (cost per session, tokens per turn), adoption "
            + "(sessions, engaged sessions, distinct and returning users), effectiveness (resolution split into confirmed, self-reported, "
            + "agent-closed and silence; escalation, abandonment, deflection, outcome coverage — rates are null while coverage is below "
            + "the minimum), quality (ratings — restricted unless you may read ratings) and the golden-suite check. Each measure has its "
            + "value, sample size n, interval, the previous period's value, the delta, whether the change is significant, and a band "
            + "(GREEN/AMBER/RED, GREY for too little data). Use it to answer 'is agent X healthy', 'does it resolve conversations' or "
            + "'why did X get worse'.")
    public String readAgentHealth(@ToolArg(description = "Agent id (required)") String agentId,
                                  @ToolArg(description = "7d, 30d or 90d (default 30d)", required = false) String period,
                                  @ToolArg(description = "First day YYYY-MM-DD (with to, instead of period)", required = false) String from,
                                  @ToolArg(description = "Last day YYYY-MM-DD (with from)", required = false) String to,
                                  @ToolArg(description = "Only this agent version", required = false) Integer version,
                                  @ToolArg(description = ORIGINS_ARG, required = false) String origins) {
        requireAnyRole(identity, authEnabled, McpRoles.HEALTH_READ);
        if (agentId == null || agentId.isBlank()) {
            return errorJson("agentId is required", "BAD_REQUEST", null);
        }
        try {
            return jsonSerialization.serialize(health.readHealth(agentId.trim(), period, from, to, version, list(origins)));
        } catch (Exception e) {
            return codedFailure(LOGGER, "read_agent_health", e);
        }
    }

    @Tool(name = "list_agent_health", description = "List the health of every agent you may view, the ones needing attention first "
            + "(significantly worse key measures, then red/amber bands, then traffic): sessions and their change, up to three key "
            + "measures (agent-caused failures, Apdex, cost per session), and platform incidents — days on which provider failures of one "
            + "class hit several agents at once, which points at the provider rather than the agents.")
    public String listAgentHealth(@ToolArg(description = "7d, 30d or 90d (default 7d)", required = false) String period,
                                  @ToolArg(description = "attention, name or traffic (default attention)", required = false) String sort,
                                  @ToolArg(description = "Page size, default 100, at most 500", required = false) Integer limit,
                                  @ToolArg(description = "nextCursor of the previous page", required = false) String cursor) {
        requireAnyRole(identity, authEnabled, McpRoles.HEALTH_READ);
        try {
            return jsonSerialization.serialize(portfolio.listAgentHealth(period, sort, null, limit, cursor));
        } catch (Exception e) {
            return codedFailure(LOGGER, "list_agent_health", e);
        }
    }

    @Tool(name = "compare_agent_versions_health", description = "Compare two versions of an agent: the newer version since its first "
            + "turn against the older one over its last days before the newer took over (default 14). Returns both sides' sections and "
            + "per-measure deltas with significance and whether the change is better. Use it after a deployment: 'is v8 better than v7?'.")
    public String compareAgentVersionsHealth(@ToolArg(description = "Agent id (required)") String agentId,
                                             @ToolArg(description = "One version (required)") Integer fromVersion,
                                             @ToolArg(description = "The other version (required)") Integer toVersion,
                                             @ToolArg(description = "Days of the older version, 1 to 90 (default 14)", required = false) Integer days,
                                             @ToolArg(description = ORIGINS_ARG, required = false) String origins) {
        requireAnyRole(identity, authEnabled, McpRoles.HEALTH_READ);
        if (agentId == null || agentId.isBlank()) {
            return errorJson("agentId is required", "BAD_REQUEST", null);
        }
        try {
            return jsonSerialization.serialize(health.compareVersions(agentId.trim(), fromVersion, toVersion, days, list(origins)));
        } catch (Exception e) {
            return codedFailure(LOGGER, "compare_agent_versions_health", e);
        }
    }

    @Tool(name = "list_agent_health_sessions", description = "List an agent's sessions with how they ended — the drill-down behind the "
            + "effectiveness measures: conversation id, session number, start and end, version, origin, engaged, outcome (RESOLVED, "
            + "ASSUMED_RESOLVED, ESCALATED, ABANDONED, FAILED, DEFLECTED, ENDED_QUIETLY, OPEN), evidence (agent_action, self_reported, "
            + "agent_closed, silence, structural, inferred), provisional, turns, cost, failures; newest first, no user ids, no content. Needs "
            + "EDIT on the agent and conversation review enabled on the version — otherwise the error says FORBIDDEN or REVIEW_DISABLED. "
            + "Use it to find the conversations behind a rate, then read_conversation to read one.")
    public String listAgentHealthSessions(@ToolArg(description = "Agent id (required)") String agentId,
                                          @ToolArg(description = "7d, 30d or 90d (default 30d)", required = false) String period,
                                          @ToolArg(description = "Only this agent version", required = false) Integer version,
                                          @ToolArg(description = ORIGINS_ARG, required = false) String origins,
                                          @ToolArg(description = "Comma-separated outcomes to include", required = false) String outcomes,
                                          @ToolArg(description = "Comma-separated evidence values to include", required = false) String evidence,
                                          @ToolArg(description = "Page size, 1 to 100 (default 50)", required = false) Integer limit,
                                          @ToolArg(description = "nextCursor of the previous page", required = false) String cursor) {
        requireAnyRole(identity, authEnabled, McpRoles.HEALTH_READ);
        if (agentId == null || agentId.isBlank()) {
            return errorJson("agentId is required", "BAD_REQUEST", null);
        }
        try {
            return jsonSerialization.serialize(health.listSessions(agentId.trim(), period, null, null, version, list(origins), list(outcomes),
                    list(evidence), limit, cursor));
        } catch (ClientErrorException e) {
            // The drill-down's 403 says why — FORBIDDEN or REVIEW_DISABLED — as its code.
            Object entity = e.getResponse() == null ? null : e.getResponse().getEntity();
            if (entity instanceof Map<?, ?> body && body.get("reason") instanceof String reason) {
                return errorJson(String.valueOf(body.get("message")), reason, null);
            }
            return codedFailure(LOGGER, "list_agent_health_sessions", e);
        } catch (Exception e) {
            return codedFailure(LOGGER, "list_agent_health_sessions", e);
        }
    }

    @Tool(name = "suggest_outcome_mappings", description = "Suggest which of an agent's actions to map to conversation outcomes "
            + "(healthConfig.outcomes): the actions that most often end engaged sessions and are not mapped yet, how many sessions they "
            + "end, and an outcome suggested from the action's name (RESOLVED, ESCALATED, DEFLECTED, or none). Nothing is applied — map an "
            + "action by updating the agent. Use it when outcome coverage is low or the resolution rate rests mostly on silence.")
    public String suggestOutcomeMappings(@ToolArg(description = "Agent id (required)") String agentId,
                                         @ToolArg(description = "7d, 30d or 90d (default 30d)", required = false) String period,
                                         @ToolArg(description = "Only this agent version", required = false) Integer version,
                                         @ToolArg(description = ORIGINS_ARG, required = false) String origins) {
        requireAnyRole(identity, authEnabled, McpRoles.HEALTH_READ);
        if (agentId == null || agentId.isBlank()) {
            return errorJson("agentId is required", "BAD_REQUEST", null);
        }
        try {
            return jsonSerialization.serialize(health.suggestOutcomes(agentId.trim(), period, null, null, version, list(origins)));
        } catch (Exception e) {
            return codedFailure(LOGGER, "suggest_outcome_mappings", e);
        }
    }

    private static List<String> list(String commaSeparated) {
        if (commaSeparated == null || commaSeparated.isBlank()) {
            return null;
        }
        return Arrays.stream(commaSeparated.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }
}
