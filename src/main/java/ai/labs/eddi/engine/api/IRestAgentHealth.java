/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.api;

import ai.labs.eddi.engine.health.report.HealthReports.AgentHealth;
import ai.labs.eddi.engine.health.report.HealthReports.HealthCompare;
import ai.labs.eddi.engine.health.report.HealthReports.HealthSeries;
import ai.labs.eddi.engine.health.report.HealthReports.OutcomeSuggestions;
import ai.labs.eddi.engine.health.report.HealthReports.SessionPage;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.List;

/**
 * Agent Health of one agent — reliability, effectiveness, efficiency, adoption
 * and quality measures with their trend, change markers, a version compare, the
 * session drill-down and outcome-mapping suggestions
 * ({@code docs/agent-health.md}). Counts and rates only, never conversation
 * content. Requires {@code VIEW} on the agent, like {@code /usage}; the quality
 * section (ratings) additionally follows the ratings rule and comes back
 * {@code restricted} for callers who may not read ratings.
 */
@Path("/agents/{agentId}/health")
@Tag(name = "Agent Health", description = "Reliability, efficiency, adoption and quality of an agent over time, per version")
@RolesAllowed({"eddi-admin", "eddi-editor", "eddi-viewer"})
public interface IRestAgentHealth {

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "An agent's health over a period", description = "Every section with its measures, the change against the previous "
            + "period of equal length, bands from the default thresholds and the golden-suite check. Requires VIEW.")
    @APIResponse(responseCode = "200", description = "The health report.")
    @APIResponse(responseCode = "400", description = "A query parameter is invalid; the message names it.")
    @APIResponse(responseCode = "403", description = "The caller may not view this agent.")
    @APIResponse(responseCode = "404", description = "No such agent.")
    AgentHealth readHealth(@PathParam("agentId") String agentId,
                           @Parameter(description = "7d, 30d or 90d (default 30d), ending today (UTC)")
                           @QueryParam("period") String period,
                           @Parameter(description = "First day YYYY-MM-DD (with to; at most 90 days)")
                           @QueryParam("from") String from,
                           @Parameter(description = "Last day YYYY-MM-DD (with from)")
                           @QueryParam("to") String to,
                           @Parameter(description = "Only this agent version")
                           @QueryParam("version") Integer version,
                           @Parameter(description = "Origins to include (repeatable); default: the preset's set. agent-test only when named")
                           @QueryParam("origin") List<String> origins);

    @GET
    @Path("/series")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Daily series of health measures", description = "One point per day for each measure (up to 6), optionally per "
            + "version or origin, and the change markers of the period. A day without data is value null, n 0. Requires VIEW.")
    @APIResponse(responseCode = "200", description = "The series.")
    @APIResponse(responseCode = "400", description = "A query parameter is invalid; the message names it.")
    @APIResponse(responseCode = "403", description = "The caller may not view this agent.")
    @APIResponse(responseCode = "404", description = "No such agent.")
    HealthSeries readSeries(@PathParam("agentId") String agentId,
                            @Parameter(description = "Measure id (repeatable, 1 to 6)")
                            @QueryParam("measure") List<String> measures,
                            @Parameter(description = "7d, 30d or 90d (default 30d)")
                            @QueryParam("period") String period,
                            @Parameter(description = "First day YYYY-MM-DD (with to)")
                            @QueryParam("from") String from,
                            @Parameter(description = "Last day YYYY-MM-DD (with from)")
                            @QueryParam("to") String to,
                            @Parameter(description = "Only this agent version")
                            @QueryParam("version") Integer version,
                            @Parameter(description = "Origins to include (repeatable)")
                            @QueryParam("origin") List<String> origins,
                            @Parameter(description = "none, version or origin (default none)")
                            @QueryParam("groupBy") String groupBy);

    @GET
    @Path("/compare")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Compare two versions of an agent", description = "The newer version since its first turn against the older one's "
            + "last days before the newer took over, with per-measure deltas and their significance. Requires VIEW.")
    @APIResponse(responseCode = "200", description = "Both sides and the deltas.")
    @APIResponse(responseCode = "400", description = "A query parameter is invalid; the message names it.")
    @APIResponse(responseCode = "403", description = "The caller may not view this agent.")
    @APIResponse(responseCode = "404", description = "No such agent.")
    HealthCompare compareVersions(@PathParam("agentId") String agentId,
                                  @Parameter(description = "One version (required)")
                                  @QueryParam("from") Integer fromVersion,
                                  @Parameter(description = "The other version (required)")
                                  @QueryParam("to") Integer toVersion,
                                  @Parameter(description = "Days of the older version before the newer took over (default 14, at most 90)")
                                  @QueryParam("days") Integer days,
                                  @Parameter(description = "Origins to include (repeatable)")
                                  @QueryParam("origin") List<String> origins);

    @GET
    @Path("/sessions")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "List an agent's sessions with their outcomes", description = "The drill-down behind the effectiveness measures: "
            + "one row per session (conversation id, session number, times, version, origin, engagement, outcome, evidence, turns, cost, "
            + "failures), newest first — no user ids, no content. Needs EDIT on the agent and conversation review enabled on the version "
            + "(without version=, on the current version; only versions with review enabled are listed).")
    @APIResponse(responseCode = "200", description = "One page of sessions.")
    @APIResponse(responseCode = "400", description = "A query parameter is invalid; the message names it.")
    @APIResponse(responseCode = "403", description = "{\"reason\": \"FORBIDDEN\"} without EDIT on the agent, {\"reason\": "
            + "\"REVIEW_DISABLED\"} when conversation review is off on the version.")
    @APIResponse(responseCode = "404", description = "No such agent.")
    SessionPage listSessions(@PathParam("agentId") String agentId,
                             @Parameter(description = "7d, 30d or 90d (default 30d), ending today (UTC)")
                             @QueryParam("period") String period,
                             @Parameter(description = "First day YYYY-MM-DD (with to)")
                             @QueryParam("from") String from,
                             @Parameter(description = "Last day YYYY-MM-DD (with from)")
                             @QueryParam("to") String to,
                             @Parameter(description = "Only this agent version")
                             @QueryParam("version") Integer version,
                             @Parameter(description = "Origins to include (repeatable); default: the preset's set")
                             @QueryParam("origin") List<String> origins,
                             @Parameter(description = "Outcomes to include (repeatable): RESOLVED, ASSUMED_RESOLVED, ESCALATED, ABANDONED, FAILED, "
                                     + "DEFLECTED, ENDED_QUIETLY, OPEN")
                             @QueryParam("outcome") List<String> outcomes,
                             @Parameter(description = "Evidence to include (repeatable): external_event, conversation_id_event, agent_action, "
                                     + "agent_closed, silence, self_reported, structural, inferred, judged")
                             @QueryParam("evidence") List<String> evidence,
                             @Parameter(description = "Page size, 1 to 100 (default 50)")
                             @QueryParam("limit") Integer limit,
                             @Parameter(description = "nextCursor of the previous page")
                             @QueryParam("cursor") String cursor);

    @GET
    @Path("/outcome-suggestions")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Suggest actions to map to outcomes", description = "The actions that most often end engaged, closed sessions of the "
            + "period and are not mapped in the current version's healthConfig.outcomes, with an outcome suggested from the action's name. "
            + "Never applies anything. Requires VIEW.")
    @APIResponse(responseCode = "200", description = "The suggestions, most frequent first.")
    @APIResponse(responseCode = "400", description = "A query parameter is invalid; the message names it.")
    @APIResponse(responseCode = "403", description = "The caller may not view this agent.")
    @APIResponse(responseCode = "404", description = "No such agent.")
    OutcomeSuggestions suggestOutcomes(@PathParam("agentId") String agentId,
                                       @Parameter(description = "7d, 30d or 90d (default 30d)")
                                       @QueryParam("period") String period,
                                       @Parameter(description = "First day YYYY-MM-DD (with to)")
                                       @QueryParam("from") String from,
                                       @Parameter(description = "Last day YYYY-MM-DD (with from)")
                                       @QueryParam("to") String to,
                                       @Parameter(description = "Only this agent version")
                                       @QueryParam("version") Integer version,
                                       @Parameter(description = "Origins to include (repeatable)")
                                       @QueryParam("origin") List<String> origins);
}
