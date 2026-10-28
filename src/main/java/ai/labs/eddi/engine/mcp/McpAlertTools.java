/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mcp;

import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.api.AlertEventQueryParams;
import ai.labs.eddi.engine.api.IRestAlertEvents;
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
 * MCP tools for the alert inbox: list events and acknowledge one. Rules are
 * written in the Manager or over REST.
 * <p>
 * The same pattern as {@link McpRatingTools}: the tools call the in-process
 * {@code IRestAlertEvents} bean, whose access check reads the request's
 * {@link SecurityIdentity} — the MCP caller. An administrator sees every
 * agent's events; anyone else names an agent they hold {@code EDIT} on
 * ({@code AGENT_ID_REQUIRED} otherwise). The acknowledger is the caller's
 * principal, never an argument. Tier {@link McpRoles#AUTHOR}, equal to the REST
 * {@code @RolesAllowed}.
 *
 * @since 6.6.0
 */
@ApplicationScoped
@McpErrorResults
public class McpAlertTools {

    private static final Logger LOGGER = Logger.getLogger(McpAlertTools.class);

    private final IRestAlertEvents events;
    private final IJsonSerialization jsonSerialization;
    private final SecurityIdentity identity;
    private final boolean authEnabled;

    @Inject
    public McpAlertTools(IRestAlertEvents events, IJsonSerialization jsonSerialization, SecurityIdentity identity,
            @ConfigProperty(name = "authorization.enabled", defaultValue = "false") boolean authEnabled) {
        this.events = events;
        this.jsonSerialization = jsonSerialization;
        this.identity = identity;
        this.authEnabled = authEnabled;
    }

    @Tool(name = "list_alert_events", description = "List fired alerts, newest first: low ratings or comments, turns that failed, "
            + "were interrupted, ran past their deadline or were stopped by a cost limit, and turns whose result matched an OUTPUT_MATCH "
            + "condition (a property, a field of JSON output, or an action) — whatever the deployment's alert rules watch. "
            + "Each event names the rule, the agent and version, the conversation id, what matched, its delivery status per target "
            + "(Slack, webhook) and whether it was acknowledged. No user ids. Conversation content appears only as a rating comment "
            + "cut to 280 characters, or an OUTPUT_MATCH value cut to 280 characters — and that value only when the rule sets "
            + "includeValue and the agent version has conversation review on. acknowledged=false lists the open inbox.")
    public String listAlertEvents(@ToolArg(description = "Agent id. Required unless you are an administrator (an editor gets AGENT_ID_REQUIRED "
            + "without it, and needs EDIT permission on the agent)", required = false) String agentId,
                                  @ToolArg(description = "Only the events of this rule", required = false) String ruleId,
                                  @ToolArg(description = "RATING, CONVERSATION_ERROR or OUTPUT_MATCH", required = false) String source,
                                  @ToolArg(description = "false: only unacknowledged (the inbox); true: only acknowledged",
                                           required = false) Boolean acknowledged,
                                  @ToolArg(description = "PENDING, DELIVERED, FAILED, INBOX_ONLY, DIGEST_PENDING or DIGESTED",
                                           required = false) String state,
                                  @ToolArg(description = "Only the events of this conversation", required = false) String conversationId,
                                  @ToolArg(description = "From this ISO-8601 instant or date, inclusive", required = false) String from,
                                  @ToolArg(description = "To this ISO-8601 instant, exclusive, or date, inclusive", required = false) String to,
                                  @ToolArg(description = "Page start, default 0", required = false) Integer index,
                                  @ToolArg(description = "Page size, default 20, max 100", required = false) Integer limit) {
        requireAnyRole(identity, authEnabled, McpRoles.AUTHOR);
        try {
            AlertEventQueryParams query = new AlertEventQueryParams();
            query.agentId = agentId;
            query.ruleId = ruleId;
            query.source = source;
            query.acknowledged = acknowledged;
            query.state = state;
            query.conversationId = conversationId;
            query.from = from;
            query.to = to;
            return jsonSerialization.serialize(events.listEvents(query, index, limit));
        } catch (Exception e) {
            return codedFailure(LOGGER, "list_alert_events", e);
        }
    }

    @Tool(name = "acknowledge_alert_event", description = "Acknowledge one alert event: it leaves the open inbox, recording you and the "
            + "time. Needs EDIT permission on the event's agent; a digest over several agents is an administrator's. Acknowledging twice "
            + "changes nothing. Acknowledge only what the person you act for has looked at.")
    public String acknowledgeAlertEvent(@ToolArg(description = "Event id, from list_alert_events (required)") String eventId) {
        requireAnyRole(identity, authEnabled, McpRoles.AUTHOR);
        if (eventId == null || eventId.isBlank()) {
            return errorJson("eventId is required", "BAD_REQUEST", null);
        }
        try {
            return jsonSerialization.serialize(events.acknowledgeEvent(eventId.trim()));
        } catch (Exception e) {
            return codedFailure(LOGGER, "acknowledge_alert_event", e);
        }
    }
}
