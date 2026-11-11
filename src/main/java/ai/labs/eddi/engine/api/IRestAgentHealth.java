/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.api;

import ai.labs.eddi.engine.health.model.HealthProfile;
import ai.labs.eddi.engine.health.report.HealthReports.AgentHealth;
import ai.labs.eddi.engine.health.report.HealthReports.HealthCompare;
import ai.labs.eddi.engine.health.report.HealthReports.HealthSeries;
import ai.labs.eddi.engine.health.report.HealthReports.OutcomeSuggestions;
import ai.labs.eddi.engine.health.report.HealthReports.ReportLinkInfo;
import ai.labs.eddi.engine.health.report.HealthReports.SessionPage;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
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
 * {@code restricted} for callers who may not read ratings. The health profile
 * (H4) is read at {@code VIEW} and written at {@code EDIT}; owner report links
 * (H4b) are managed at {@code EDIT} — their public side is
 * {@link IRestSharedHealthReport}.
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

    @GET
    @Path("/profile")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Read an agent's health profile", description = "The effective profile — preset, critical measures, thresholds, "
            + "budgets, SLO and overrides — with stored=false when nothing is stored and these are the preset's defaults. Unversioned and "
            + "not part of agent export or sync. Requires VIEW.")
    @APIResponse(responseCode = "200", description = "The effective profile.")
    @APIResponse(responseCode = "403", description = "The caller may not view this agent.")
    @APIResponse(responseCode = "404", description = "No such agent.")
    HealthProfile readProfile(@PathParam("agentId") String agentId);

    @PUT
    @Path("/profile")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @RolesAllowed({"eddi-admin", "eddi-editor"})
    @Operation(summary = "Store an agent's health profile", description = "Replaces the stored profile. Every field is optional and falls "
            + "back to the preset. Changes bands and the status at once and leaves a PROFILE_CHANGE marker; a changed slo.apdexTMs "
            + "recounts the retained days. Requires EDIT.")
    @APIResponse(responseCode = "200", description = "The effective profile after the save.")
    @APIResponse(responseCode = "400", description = "{\"violations\": [{\"path\": \"thresholds.resolutionRate.red\", \"message\": \"...\"}]}")
    @APIResponse(responseCode = "403", description = "The caller may not edit this agent.")
    @APIResponse(responseCode = "404", description = "No such agent.")
    Response updateProfile(@PathParam("agentId") String agentId, HealthProfile profile);

    @DELETE
    @Path("/profile")
    @RolesAllowed({"eddi-admin", "eddi-editor"})
    @Operation(summary = "Delete an agent's health profile", description = "Back to the preset picked from the agent's traffic. Requires "
            + "EDIT.")
    @APIResponse(responseCode = "204", description = "Deleted (or nothing was stored).")
    @APIResponse(responseCode = "403", description = "The caller may not edit this agent.")
    @APIResponse(responseCode = "404", description = "No such agent.")
    Response deleteProfile(@PathParam("agentId") String agentId);

    @POST
    @Path("/reports")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @RolesAllowed({"eddi-admin", "eddi-editor"})
    @Operation(summary = "Create an owner report link", description = "A read-only, branded link to this agent's health for someone "
            + "without Manager access: {\"expiresInDays\": 1-90 (default 30), \"sections\": [...] (default all but quality), \"period\": "
            + "\"7d|30d|90d\" (default 30d)}. The token is in url and path of this response only and cannot be read again. quality "
            + "needs permission to read the agent's ratings. Requires EDIT.")
    @APIResponse(responseCode = "201", description = "The link, with its token.")
    @APIResponse(responseCode = "400", description = "A field is invalid, or the agent has too many live links.")
    @APIResponse(responseCode = "403", description = "The caller may not edit this agent, or asked for quality without ratings access.")
    @APIResponse(responseCode = "404", description = "No such agent.")
    Response createReportLink(@PathParam("agentId") String agentId, ReportLinkRequest request, @Context UriInfo uriInfo);

    @GET
    @Path("/reports")
    @Produces(MediaType.APPLICATION_JSON)
    @RolesAllowed({"eddi-admin", "eddi-editor"})
    @Operation(summary = "List an agent's owner report links", description = "The live links — id, expiry, sections, period, creator — "
            + "never their tokens. Requires EDIT.")
    @APIResponse(responseCode = "200", description = "The links, newest first.")
    @APIResponse(responseCode = "403", description = "The caller may not edit this agent.")
    @APIResponse(responseCode = "404", description = "No such agent.")
    List<ReportLinkInfo> listReportLinks(@PathParam("agentId") String agentId);

    @DELETE
    @Path("/reports/{id}")
    @RolesAllowed({"eddi-admin", "eddi-editor"})
    @Operation(summary = "Revoke an owner report link", description = "The link answers 404 from now on. Requires EDIT.")
    @APIResponse(responseCode = "204", description = "Revoked.")
    @APIResponse(responseCode = "403", description = "The caller may not edit this agent.")
    @APIResponse(responseCode = "404", description = "No such agent or link.")
    Response revokeReportLink(@PathParam("agentId") String agentId, @PathParam("id") String id);

    /**
     * The body of {@code POST …/health/reports}.
     *
     * @param expiresInDays
     *            1 to 90, default 30
     * @param sections
     *            default every section but quality
     * @param period
     *            {@code 7d}, {@code 30d} (default) or {@code 90d}
     */
    record ReportLinkRequest(Integer expiresInDays, List<String> sections, String period) {
    }
}
