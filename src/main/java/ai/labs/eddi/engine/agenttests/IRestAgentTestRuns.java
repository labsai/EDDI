/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.agenttests;

import ai.labs.eddi.engine.agenttests.model.AgentTestRun;
import ai.labs.eddi.engine.agenttests.model.StartRunRequest;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.resteasy.reactive.NoCache;

import java.util.List;

/**
 * Runs agent test suites and reads their results.
 * <p>
 * Same roles as deploying an agent ({@code IRestAgentAdministration}), because
 * starting a run deploys the agent version under test to the {@code test}
 * environment — and the deploy's own EDIT check, space guard and quota apply to
 * the caller.
 *
 * @since 6.5.0
 */
@Path("/agenttests")
@Tag(name = "Agents / Testing", description = "Run golden-conversation test suites against an agent version")
@RolesAllowed({"eddi-admin", "eddi-editor"})
public interface IRestAgentTestRuns {

    @POST
    @Path("/runs")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Start a test run", description = "Deploys the agent version to the test environment if needed and runs the suite "
            + "in the background. Returns 202 with the RUNNING run record; poll GET /agenttests/runs/{runId} until status is "
            + "PASSED, FAILED, ERROR, ABORTED or CANCELLED.")
    @APIResponse(responseCode = "202", description = "Run accepted; the body is the RUNNING record with its id.")
    @APIResponse(responseCode = "400", description = "Missing ids, or the suite has no cases.")
    @APIResponse(responseCode = "404", description = "Suite or agent not found.")
    @APIResponse(responseCode = "409", description = "The version could not be deployed to test, or a newer version is also deployed there.")
    @APIResponse(responseCode = "429", description = "Too many runs executing at once (eddi.agent-tests.max-concurrent-runs).")
    Response startRun(StartRunRequest request);

    @POST
    @Path("/runs/{runId}/cancel")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Cancel a test run", description = "Stops a RUNNING run cooperatively: no further turn starts, a turn in "
            + "progress is abandoned, and every test conversation is ended and deleted (unless the run keeps them). Returns 202 with "
            + "the record as it is now; it reads CANCELLED moments later. Takes the same rights as starting a run.")
    @APIResponse(responseCode = "202", description = "Cancellation requested; poll GET /agenttests/runs/{runId}.")
    @APIResponse(responseCode = "404", description = "Run not found.")
    @APIResponse(responseCode = "409", description = "The run has already finished, or it is executing on another node.")
    Response cancelRun(@PathParam("runId") String runId);

    @GET
    @NoCache
    @Path("/runs/{runId}")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Read a test run", description = "Per-case, per-turn results with each failed expectation and its actual value.")
    @APIResponse(responseCode = "404", description = "Run not found.")
    AgentTestRun readRun(@PathParam("runId") String runId);

    @GET
    @NoCache
    @Path("/runs")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "List an agent's test runs", description = "Newest first. Only a bounded number per agent is kept "
            + "(eddi.agent-tests.max-runs-per-agent).")
    List<AgentTestRun> listRuns(@QueryParam("agentId") String agentId,
                                @QueryParam("index")
                                @DefaultValue("0") Integer index,
                                @QueryParam("limit")
                                @DefaultValue("20") Integer limit);
}
