/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mcp;

import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.hitl.oversight.rest.IRestApprovalAnalytics;
import ai.labs.eddi.engine.hitl.oversight.rest.IRestAuditSamples;
import ai.labs.eddi.engine.hitl.oversight.rest.IRestAutonomy;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import static ai.labs.eddi.engine.mcp.McpToolUtils.codedFailure;
import static ai.labs.eddi.engine.mcp.McpToolUtils.errorJson;
import static ai.labs.eddi.engine.mcp.McpToolUtils.requireAnyRole;

/**
 * MCP read tools for HITL oversight ({@code docs/hitl-oversight.md}): how
 * approval decisions turn out, which approval categories are ready to stop
 * asking or are suspended, and how many sampled tool calls wait for review.
 * <p>
 * <b>Reads only, and there will be no write.</b> A sample verdict, applying or
 * dismissing a recommendation, suspending, lifting and the analytics rebuild
 * are human acts on the Manager's Oversight page (the server refuses them from
 * an agent's tool call, {@code AGENT_ORIGINATED}). The sample list and the
 * per-decision rows are not offered either: they carry users' redacted tool
 * arguments and per-row conversation ids and deciders.
 * <p>
 * The tools call the in-process {@code IRestApprovalAnalytics},
 * {@code IRestAutonomy} and {@code IRestAuditSamples} beans, whose
 * {@code OversightAccess} reads the request's {@link SecurityIdentity} — the
 * MCP caller: administrators and approvers see every agent, an editor names an
 * agent it holds EDIT on. Tier {@link McpRoles#OVERSIGHT}, equal to their
 * {@code @RolesAllowed}.
 *
 * @since 6.7.0
 */
@ApplicationScoped
@McpErrorResults
public class McpOversightTools {

    private static final Logger LOGGER = Logger.getLogger(McpOversightTools.class);

    private static final String AGENT_ID_ARG = "Agent id. Optional for administrators and approvers; an editor must name an agent "
            + "it holds EDIT on";

    private final IRestApprovalAnalytics analytics;
    private final IRestAutonomy autonomy;
    private final IRestAuditSamples samples;
    private final IJsonSerialization jsonSerialization;
    private final SecurityIdentity identity;
    private final boolean authEnabled;

    @Inject
    public McpOversightTools(IRestApprovalAnalytics analytics, IRestAutonomy autonomy, IRestAuditSamples samples,
            IJsonSerialization jsonSerialization, SecurityIdentity identity,
            @ConfigProperty(name = "authorization.enabled", defaultValue = "false") boolean authEnabled) {
        this.analytics = analytics;
        this.autonomy = autonomy;
        this.samples = samples;
        this.jsonSerialization = jsonSerialization;
        this.identity = identity;
        this.authEnabled = authEnabled;
    }

    @Tool(name = "summarize_approvals", description = "Summarize human-in-the-loop approval decisions: totals, outcome rates (APPROVED, "
            + "APPROVED_EDITED, REJECTED, AUTO_APPROVED, AUTO_REJECTED, ABORTED), top reason codes, latency p50/p90, timeout share, channel "
            + "mix and sampled-audit counts — overall or grouped by category, agent, group, day, week, channel or agentVersion. "
            + "To see which approval categories are close to earned autonomy, group by category for one agent and read the unedited "
            + "rate next to list_autonomy_recommendations. Default window: the last 30 days; at most 365.")
    public String summarizeApprovals(@ToolArg(description = AGENT_ID_ARG, required = false) String agentId,
                                     @ToolArg(description = "Only this group's decisions", required = false) String groupId,
                                     @ToolArg(description = "Only this approval category, e.g. tool:mcp:add_issue_comment",
                                              required = false) String category,
                                     @ToolArg(description = "From this ISO-8601 instant or date, inclusive", required = false) String from,
                                     @ToolArg(description = "To this ISO-8601 instant or date, exclusive", required = false) String to,
                                     @ToolArg(description = "regular or group", required = false) String surface,
                                     @ToolArg(description = "RULE, TOOL_CALL, PHASE or TASK", required = false) String pauseType,
                                     @ToolArg(description = "REST, MANAGER, MCP, SLACK, TIMEOUT or SYSTEM", required = false) String channel,
                                     @ToolArg(description = "Only decisions on this agent version", required = false) Integer agentVersion,
                                     @ToolArg(description = "none, category, agent, group, day, week, channel or agentVersion (default none)",
                                              required = false) String groupBy) {
        requireAnyRole(identity, authEnabled, McpRoles.OVERSIGHT);
        try {
            IRestApprovalAnalytics.Query query = new IRestApprovalAnalytics.Query();
            query.agentId = agentId;
            query.groupId = groupId;
            query.category = category;
            query.from = from;
            query.to = to;
            query.surface = surface;
            query.pauseType = pauseType;
            query.channel = channel;
            query.agentVersion = agentVersion;
            return jsonSerialization.serialize(analytics.summarize(query, groupBy));
        } catch (Exception e) {
            return codedFailure(LOGGER, "summarize_approvals", e);
        }
    }

    @Tool(name = "list_autonomy_recommendations", description = "List earned-autonomy states: each approval category with stored state, "
            + "its tier — GATED (still asks), READY (an open PROMOTE recommendation a human can apply), PROMOTED (applied: exempt from "
            + "approval and sampled instead) or SUSPENDED (re-gated after a flagged sample or by hand) — and the recommendation or "
            + "suspension behind it. Applying, dismissing, suspending and lifting are done by a person on the Manager's Oversight page.")
    public String listAutonomyRecommendations(@ToolArg(description = AGENT_ID_ARG, required = false) String agentId) {
        requireAnyRole(identity, authEnabled, McpRoles.OVERSIGHT);
        try {
            return jsonSerialization.serialize(autonomy.listStates(agentId));
        } catch (Exception e) {
            return codedFailure(LOGGER, "list_autonomy_recommendations", e);
        }
    }

    @Tool(name = "get_autonomy_state", description = "Read one approval category of an agent: its stored state and its evidence "
            + "evaluated now against the newest agent version (decisions in the window, unedited rate, demoting reason codes, flagged "
            + "samples, cooldown) with the reason it is or is not eligible for promotion. Only categories named in the agent's "
            + "hitlConfig.oversight.autonomyPolicy are evaluated.")
    public String getAutonomyState(@ToolArg(description = "Agent id (required)") String agentId,
                                   @ToolArg(description = "Approval category, e.g. tool:mcp:add_issue_comment (required)") String category) {
        requireAnyRole(identity, authEnabled, McpRoles.OVERSIGHT);
        if (agentId == null || agentId.isBlank() || category == null || category.isBlank()) {
            return errorJson("agentId and category are required", "BAD_REQUEST", null);
        }
        try {
            return jsonSerialization.serialize(autonomy.readCategory(agentId.trim(), category.trim()));
        } catch (Exception e) {
            return codedFailure(LOGGER, "get_autonomy_state", e);
        }
    }

    @Tool(name = "count_pending_audit_samples", description = "Count the sampled tool calls waiting for a human review — calls the approval "
            + "gate allowed (exempt or promoted) that sampledAudit picked for a look afterwards. Unreviewed samples expire after "
            + "reviewWithin. The samples themselves are reviewed on the Manager's Oversight page.")
    public String countPendingAuditSamples(@ToolArg(description = AGENT_ID_ARG, required = false) String agentId) {
        requireAnyRole(identity, authEnabled, McpRoles.OVERSIGHT);
        try {
            return jsonSerialization.serialize(samples.pendingCount(agentId));
        } catch (Exception e) {
            return codedFailure(LOGGER, "count_pending_audit_samples", e);
        }
    }
}
