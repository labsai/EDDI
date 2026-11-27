/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.api;

import ai.labs.eddi.engine.health.report.HealthReports.AgentHealth;
import ai.labs.eddi.engine.health.report.HealthReports.HealthCompare;
import ai.labs.eddi.engine.health.report.HealthReports.HealthSeries;
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
 * Agent Health of one agent — reliability, efficiency, adoption and quality
 * measures with their trend, change markers and a version compare
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
}
