/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mcp;

import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.groups.budget.rest.IRestGroupBudget;
import ai.labs.eddi.engine.groups.budget.rest.IRestGroupBudget.CapRequest;
import ai.labs.eddi.engine.groups.budget.rest.IRestTeamBudgets;
import ai.labs.eddi.engine.groups.budget.rest.IRestTeamBudgets.TransferRequest;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.LinkedHashMap;
import java.util.Map;

import static ai.labs.eddi.engine.mcp.McpToolUtils.errorJson;
import static ai.labs.eddi.engine.mcp.McpToolUtils.requireAnyRole;
import static ai.labs.eddi.engine.mcp.McpToolUtils.responseJson;
import static ai.labs.eddi.engine.mcp.McpToolUtils.toolFailure;

/**
 * MCP tools for per-team monthly budgets ({@code docs/team-budgets.md}): read a
 * team's budget and its history, list several teams, and — administrators only
 * — set this month's cap or move cap between teams.
 * <p>
 * The tools call the in-process {@link IRestGroupBudget} and
 * {@link IRestTeamBudgets} beans, whose VIEW check on the group reads the
 * request's {@link SecurityIdentity} — the MCP caller — and which record the
 * caller's principal as the actor of an adjustment, never an argument. Reads
 * are tier {@link McpRoles#BUDGET_READ} and writes {@link McpRoles#ADMIN_ONLY},
 * equal to the REST {@code @RolesAllowed}: a cap is a spending lever, exactly
 * as a tenant quota is. The role is checked here because an in-process call
 * does not pass the HTTP layer that enforces {@code @RolesAllowed}.
 *
 * @since 6.7.0
 */
@ApplicationScoped
@McpErrorResults
public class McpTeamBudgetTools {

    private static final Logger LOGGER = Logger.getLogger(McpTeamBudgetTools.class);

    /** The REST history read's own bound (the retention window). */
    private static final int MAX_HISTORY_MONTHS = 13;

    private final IRestGroupBudget groupBudget;
    private final IRestTeamBudgets teamBudgets;
    private final IJsonSerialization jsonSerialization;
    private final SecurityIdentity identity;
    private final boolean authEnabled;

    @Inject
    public McpTeamBudgetTools(IRestGroupBudget groupBudget, IRestTeamBudgets teamBudgets, IJsonSerialization jsonSerialization,
            SecurityIdentity identity, @ConfigProperty(name = "authorization.enabled", defaultValue = "false") boolean authEnabled) {
        this.groupBudget = groupBudget;
        this.teamBudgets = teamBudgets;
        this.jsonSerialization = jsonSerialization;
        this.identity = identity;
        this.authEnabled = authEnabled;
    }

    @Tool(name = "read_team_budget", description = "Read one team's monthly budget: the cap (configured base plus this month's adjustment), "
            + "spent, remaining, the month (UTC calendar month) and when it resets, and which limit binds first — TEAM or TENANT. "
            + "A group without a teamBudget returns its spend only. Give historyMonths to add past months with their adjustment "
            + "history (who changed the cap, and why). Needs VIEW on the group.")
    public String readTeamBudget(@ToolArg(description = "The group's id (required)") String groupId,
                                 @ToolArg(description = "Also return this many past months, 1..13 (default: none)",
                                          required = false) Integer historyMonths) {
        requireAnyRole(identity, authEnabled, McpRoles.BUDGET_READ);
        if (groupId == null || groupId.isBlank()) {
            return errorJson("groupId is required", "BAD_REQUEST", null);
        }
        if (historyMonths != null && (historyMonths < 1 || historyMonths > MAX_HISTORY_MONTHS)) {
            return errorJson("historyMonths must be between 1 and " + MAX_HISTORY_MONTHS, "BAD_REQUEST", null);
        }
        try {
            Response current = groupBudget.readBudget(groupId.trim());
            if (historyMonths == null || current == null || current.getStatus() != 200) {
                return responseJson(jsonSerialization, current);
            }
            Response history = groupBudget.readHistory(groupId.trim(), historyMonths);
            if (history == null || history.getStatus() != 200) {
                return responseJson(jsonSerialization, history);
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("current", current.getEntity());
            result.put("history", history.getEntity());
            return jsonSerialization.serialize(result);
        } catch (Exception e) {
            return toolFailure(LOGGER, "read_team_budget", e);
        }
    }

    @Tool(name = "list_team_budgets", description = "List the budgets of several teams at once (comma-separated group ids, at most 50), "
            + "plus the tenant's remaining monthly budget. Groups you may not view are left out, not refused.")
    public String listTeamBudgets(@ToolArg(description = "Comma-separated group ids (required)") String groupIds) {
        requireAnyRole(identity, authEnabled, McpRoles.BUDGET_READ);
        if (groupIds == null || groupIds.isBlank()) {
            return errorJson("groupIds is required", "BAD_REQUEST", null);
        }
        try {
            return responseJson(jsonSerialization, teamBudgets.listBudgets(groupIds));
        } catch (Exception e) {
            return toolFailure(LOGGER, "list_team_budgets", e);
        }
    }

    @Tool(name = "adjust_team_budget", description = "Administrators only. Set this month's cap of one team: the delta over its configured "
            + "base is adjusted, and next month starts at the base again. The cap may not be below what the team already spent; the "
            + "reason is required (at most 1000 characters) and kept in the audit ledger and the month's history. Raising a cap "
            + "raises what the team may spend: do it only when the person you act for asked for this amount.")
    public String adjustTeamBudget(@ToolArg(description = "The group's id (required)") String groupId,
                                   @ToolArg(description = "This month's cap in USD (required)") Double capUsd,
                                   @ToolArg(description = "Why (required)") String reason) {
        requireAnyRole(identity, authEnabled, McpRoles.ADMIN_ONLY);
        if (groupId == null || groupId.isBlank() || capUsd == null || reason == null || reason.isBlank()) {
            return errorJson("groupId, capUsd and reason are required", "BAD_REQUEST", null);
        }
        try {
            return responseJson(jsonSerialization, groupBudget.setCurrentMonthCap(groupId.trim(), new CapRequest(capUsd, reason)));
        } catch (Exception e) {
            return toolFailure(LOGGER, "adjust_team_budget", e);
        }
    }

    @Tool(name = "transfer_team_budget", description = "Administrators only. Move part of this month's cap from one team to another. The "
            + "source must keep a cap at or above what it already spent; both teams need a teamBudget; the reason is required. Give an "
            + "idempotencyKey to make a retry of the same transfer a no-op.")
    public String transferTeamBudget(@ToolArg(description = "Group id to take cap from (required)") String fromGroupId,
                                     @ToolArg(description = "Group id to give cap to (required)") String toGroupId,
                                     @ToolArg(description = "Amount in USD, more than 0 (required)") Double amountUsd,
                                     @ToolArg(description = "Why (required)") String reason,
                                     @ToolArg(description = "Idempotency key; a retry with the same key changes nothing",
                                              required = false) String idempotencyKey) {
        requireAnyRole(identity, authEnabled, McpRoles.ADMIN_ONLY);
        if (fromGroupId == null || fromGroupId.isBlank() || toGroupId == null || toGroupId.isBlank() || amountUsd == null || reason == null
                || reason.isBlank()) {
            return errorJson("fromGroupId, toGroupId, amountUsd and reason are required", "BAD_REQUEST", null);
        }
        try {
            String key = idempotencyKey == null || idempotencyKey.isBlank() ? null : idempotencyKey.trim();
            return responseJson(jsonSerialization,
                    teamBudgets.transfer(key, new TransferRequest(fromGroupId.trim(), toGroupId.trim(), amountUsd, reason)));
        } catch (Exception e) {
            return toolFailure(LOGGER, "transfer_team_budget", e);
        }
    }
}
