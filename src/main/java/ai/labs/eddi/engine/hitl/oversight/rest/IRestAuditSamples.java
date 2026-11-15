/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl.oversight.rest;

import ai.labs.eddi.engine.hitl.oversight.samples.AuditSample;
import ai.labs.eddi.engine.hitl.oversight.samples.AuditSampleService;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.List;
import java.util.Map;

/**
 * The sampled-audit review queue: tool calls that ran without approval, picked
 * for a human to look at afterwards. One verdict per sample, by hand — there is
 * no bulk verdict.
 *
 * @since 6.7.0
 */
@Path("/hitl/samples")
@Tag(name = "HITL Oversight", description = "Approval analytics, sampled audit and earned autonomy")
@RolesAllowed({"eddi-admin", "eddi-editor", "eddi-approver"})
public interface IRestAuditSamples {

    /** A page of samples. */
    record SamplePage(long total, int index, int limit, List<AuditSample> rows) {
    }

    /** One sample and the reason codes a reviewer may flag it with. */
    record SampleDetail(AuditSample sample, List<Map<String, String>> reasonCodes) {
    }

    /** How many samples wait for review. */
    record PendingCount(long pending) {
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "List audit samples",
               description = "PENDING samples by due date, anything else newest first. status: PENDING (default), OK, FLAGGED, EXPIRED, ALL.")
    SamplePage listSamples(@QueryParam("agentId") String agentId, @QueryParam("category") String category,
                           @QueryParam("status") String status, @QueryParam("from") String from, @QueryParam("to") String to,
                           @QueryParam("index") Integer index, @QueryParam("limit") Integer limit);

    @GET
    @Path("/pending-count")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Count audit samples waiting for review")
    PendingCount pendingCount(@QueryParam("agentId") String agentId);

    @GET
    @Path("/{sampleId}")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Read one audit sample")
    SampleDetail readSample(@PathParam("sampleId") String sampleId);

    @PATCH
    @Path("/{sampleId}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Review one audit sample",
               description = "{verdict: OK|FLAGGED, reasonCode (required for FLAGGED), note, expectedVersion}. The reviewer is the caller. "
                       + "409 SAMPLE_ALREADY_REVIEWED when it changed since you read it.")
    AuditSample reviewSample(@PathParam("sampleId") String sampleId, AuditSampleService.Verdict verdict);
}
