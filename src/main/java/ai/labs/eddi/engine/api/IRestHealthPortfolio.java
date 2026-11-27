/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.api;

import ai.labs.eddi.engine.health.report.HealthReports.Portfolio;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.List;

/**
 * The Agent Health portfolio: every agent the caller may view, ordered by which
 * needs attention first, with the platform incidents of the period.
 */
@Path("/health")
@Tag(name = "Agent Health", description = "Reliability, efficiency, adoption and quality of an agent over time, per version")
@RolesAllowed({"eddi-admin", "eddi-editor", "eddi-viewer"})
public interface IRestHealthPortfolio {

    @GET
    @Path("/agents")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Health of every agent you may view", description = "One row per agent with sessions, their change and up to three "
            + "key measures, sorted by attention (significant worsening, then band, then traffic), name or traffic; and the days on which "
            + "platform failures hit several agents at once.")
    @APIResponse(responseCode = "200", description = "One page of the portfolio.")
    @APIResponse(responseCode = "400", description = "A query parameter is invalid; the message names it.")
    Portfolio listAgentHealth(@Parameter(description = "7d, 30d or 90d (default 7d)")
    @QueryParam("period") String period,
                              @Parameter(description = "attention, name or traffic (default attention)")
                              @QueryParam("sort") String sort,
                              @Parameter(description = "Only agents with this status (repeatable); no agent has a status before H4")
                              @QueryParam("status") List<String> statuses,
                              @Parameter(description = "Page size, default 100, at most 500")
                              @QueryParam("limit") Integer limit,
                              @Parameter(description = "The nextCursor of the previous page")
                              @QueryParam("cursor") String cursor);
}
