/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mcp;

import ai.labs.eddi.configs.groups.IRestTeamHandoffs;
import ai.labs.eddi.configs.groups.IRestTeamHandoffs.AcceptRequest;
import ai.labs.eddi.configs.groups.IRestTeamHandoffs.DeclineRequest;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import static ai.labs.eddi.engine.mcp.McpToolUtils.errorJson;
import static ai.labs.eddi.engine.mcp.McpToolUtils.requireAnyRole;
import static ai.labs.eddi.engine.mcp.McpToolUtils.responseJson;
import static ai.labs.eddi.engine.mcp.McpToolUtils.toolFailure;

/**
 * MCP tools for a standing team's cross-team handoff intake
 * ({@code docs/team-handoff.md}): read the inbox of handoffs other teams or
 * agents filed under a REVIEW accept rule, and accept or decline one.
 * <p>
 * <b>Same rule as REST, applied to the MCP caller.</b> The tools call the
 * in-process {@link IRestTeamHandoffs} bean, which checks that the group exists
 * first and then VIEW (read) or EDIT (decide) on the <em>group</em> against the
 * request's {@link SecurityIdentity} — here the MCP caller. The decider
 * recorded in the {@code team_handoff_intake} audit entry is the caller's
 * principal, never an argument. Tier {@link McpRoles#AUTHOR}, equal to the REST
 * {@code @RolesAllowed}.
 * <p>
 * There is deliberately no tool to <em>file</em> a handoff: that is an
 * agent-to-team path with its own consent and caps; a person with EDIT adds a
 * task with {@code add_team_task}. Deleting a backlog task stays on REST and in
 * the Manager.
 *
 * @since 6.7.0
 */
@ApplicationScoped
@McpErrorResults
public class McpTeamHandoffTools {

    private static final Logger LOGGER = Logger.getLogger(McpTeamHandoffTools.class);

    private final IRestTeamHandoffs handoffs;
    private final IJsonSerialization jsonSerialization;
    private final SecurityIdentity identity;
    private final boolean authEnabled;

    @Inject
    public McpTeamHandoffTools(IRestTeamHandoffs handoffs, IJsonSerialization jsonSerialization, SecurityIdentity identity,
            @ConfigProperty(name = "authorization.enabled", defaultValue = "false") boolean authEnabled) {
        this.handoffs = handoffs;
        this.jsonSerialization = jsonSerialization;
        this.identity = identity;
        this.authEnabled = authEnabled;
    }

    @Tool(name = "list_team_handoffs", description = "List a standing team's cross-team handoff intake: the inbox of handoffs other teams "
            + "or agents filed that wait for a human to accept or decline (REVIEW accept rules), and where every handed-off backlog task "
            + "came from (source team or agent, hop, filed when). Needs VIEW on the group. Use it to answer why a team did or did not "
            + "get a piece of work; the backlog itself is list_team_backlog.")
    public String listTeamHandoffs(@ToolArg(description = "The receiving group's id (required)") String groupId) {
        requireAnyRole(identity, authEnabled, McpRoles.AUTHOR);
        if (groupId == null || groupId.isBlank()) {
            return errorJson("groupId is required", "BAD_REQUEST", null);
        }
        try {
            return responseJson(jsonSerialization, handoffs.readHandoffs(groupId.trim()));
        } catch (Exception e) {
            return toolFailure(LOGGER, "list_team_handoffs", e);
        }
    }

    @Tool(name = "accept_team_handoff", description = "Accept one pending handoff onto the team's backlog, optionally editing its subject, "
            + "description or priority first (any edit records the decision as APPROVED_EDITED). This is a human decision recorded "
            + "under your name: accept only what the person you act for has reviewed. Needs EDIT on the group. CONFLICT when the "
            + "backlog is full (200 tasks) or already holds that subject; NOT_FOUND when the handoff was decided meanwhile.")
    public String acceptTeamHandoff(@ToolArg(description = "The receiving group's id (required)") String groupId,
                                    @ToolArg(description = "Handoff id, from list_team_handoffs (required)") String handoffId,
                                    @ToolArg(description = "Replacement subject", required = false) String subject,
                                    @ToolArg(description = "Replacement description", required = false) String description,
                                    @ToolArg(description = "Replacement priority, 0..10", required = false) Integer priority,
                                    @ToolArg(description = "Note kept with the decision", required = false) String note) {
        requireAnyRole(identity, authEnabled, McpRoles.AUTHOR);
        if (groupId == null || groupId.isBlank() || handoffId == null || handoffId.isBlank()) {
            return errorJson("groupId and handoffId are required", "BAD_REQUEST", null);
        }
        try {
            return responseJson(jsonSerialization,
                    handoffs.acceptHandoff(groupId.trim(), handoffId.trim(), new AcceptRequest(subject, description, priority, note)));
        } catch (Exception e) {
            return toolFailure(LOGGER, "accept_team_handoff", e);
        }
    }

    @Tool(name = "decline_team_handoff", description = "Decline one pending handoff. The same handoff filed again is refused for the decline "
            + "memory (eddi.groups.handoff.decline-memory, 30 days by default). A human decision recorded under your name, with the "
            + "reason code in the audit entry. Needs EDIT on the group.")
    public String declineTeamHandoff(@ToolArg(description = "The receiving group's id (required)") String groupId,
                                     @ToolArg(description = "Handoff id, from list_team_handoffs (required)") String handoffId,
                                     @ToolArg(description = "Short machine-readable reason, e.g. out_of_scope or duplicate",
                                              required = false) String reasonCode,
                                     @ToolArg(description = "Note kept with the decision", required = false) String note) {
        requireAnyRole(identity, authEnabled, McpRoles.AUTHOR);
        if (groupId == null || groupId.isBlank() || handoffId == null || handoffId.isBlank()) {
            return errorJson("groupId and handoffId are required", "BAD_REQUEST", null);
        }
        try {
            return responseJson(jsonSerialization, handoffs.declineHandoff(groupId.trim(), handoffId.trim(), new DeclineRequest(reasonCode, note)));
        } catch (Exception e) {
            return toolFailure(LOGGER, "decline_team_handoff", e);
        }
    }
}
