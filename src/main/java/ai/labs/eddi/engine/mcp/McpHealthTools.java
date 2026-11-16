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
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.Arrays;
import java.util.List;

import static ai.labs.eddi.engine.mcp.McpToolUtils.codedFailure;
import static ai.labs.eddi.engine.mcp.McpToolUtils.errorJson;
import static ai.labs.eddi.engine.mcp.McpToolUtils.requireAnyRole;

/**
 * MCP tools for Agent Health — read-only: one agent's health report, the
 * portfolio of every agent the caller may view, and a version-to-version
 * compare.
 * <p>
 * <b>Same rule as REST, applied to the MCP caller.</b> The tools call the
 * in-process resource beans ({@code IRestAgentHealth},
 * {@code IRestHealthPortfolio}), whose {@code ResourceAccessGuard} reads the
 * request's {@link SecurityIdentity} — here the MCP caller: VIEW on the agent
 * for the report and the compare, only viewable agents in the portfolio, and
 * the quality section {@code restricted} for callers who may not read ratings.
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
            + "(sessions, engaged sessions, distinct and returning users), quality (ratings — restricted unless you may read ratings) and "
            + "the golden-suite check. Each measure has its value, sample size n, interval, the previous period's value, the delta, "
            + "whether the change is significant, and a band (GREEN/AMBER/RED, GREY for too little data). Effectiveness measures are "
            + "null until conversation outcomes are recorded. Use it to answer 'is agent X healthy' or 'why did X get worse'.")
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

    private static List<String> list(String commaSeparated) {
        if (commaSeparated == null || commaSeparated.isBlank()) {
            return null;
        }
        return Arrays.stream(commaSeparated.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }
}
