/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl.oversight.rest;

import ai.labs.eddi.engine.hitl.oversight.analytics.ApprovalDecision;
import ai.labs.eddi.engine.hitl.oversight.analytics.ApprovalSummary;
import ai.labs.eddi.engine.hitl.oversight.analytics.OutcomeAnalyticsService;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.BeanParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.List;

/**
 * Approval analytics: how a deployment's HITL decisions turn out, per category,
 * agent, group, day, channel or agent version.
 *
 * @since 6.7.0
 */
@Path("/hitl/analytics")
@Tag(name = "HITL Oversight", description = "Approval analytics, sampled audit and earned autonomy")
@RolesAllowed({"eddi-admin", "eddi-editor", "eddi-approver"})
public interface IRestApprovalAnalytics {

    /** The filter shared by the analytics reads. */
    class Query {
        @QueryParam("agentId")
        public String agentId;
        @QueryParam("groupId")
        public String groupId;
        @QueryParam("category")
        public String category;
        /** ISO-8601 instant or date; default {@code to} minus 30 days. */
        @QueryParam("from")
        public String from;
        /**
         * ISO-8601 instant or date (exclusive); default now. At most 365 days after
         * {@code from}.
         */
        @QueryParam("to")
        public String to;
        /** {@code regular} or {@code group}. */
        @QueryParam("surface")
        public String surface;
        /** {@code RULE}, {@code TOOL_CALL}, {@code PHASE}, {@code TASK}. */
        @QueryParam("pauseType")
        public String pauseType;
        /**
         * {@code REST}, {@code MANAGER}, {@code MCP}, {@code SLACK}, {@code TIMEOUT},
         * {@code SYSTEM}.
         */
        @QueryParam("channel")
        public String channel;
        @QueryParam("agentVersion")
        public Integer agentVersion;
    }

    /** A page of decision rows. */
    record DecisionPage(long total, int index, int limit, List<ApprovalDecision> rows) {
    }

    /**
     * Whether a rebuild started; it runs in the background and logs what it did.
     */
    record RebuildStatus(boolean started, boolean alreadyRunning, String from, String to) {
    }

    @GET
    @Path("/summary")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Summarize approval decisions",
               description = "Totals, outcome rates, top reason codes, latency p50/p90 (approximate), timeout share, channel mix and "
                       + "sampled-audit counts. groupBy: none, category, agent, group, day, week, channel, agentVersion.")
    ApprovalSummary.Result summarize(@BeanParam Query query, @QueryParam("groupBy") String groupBy);

    @GET
    @Path("/outcomes")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Approval decisions joined with the outcomes their work reported",
               description = "Per approval category: decided units, how many have a real-world outcome recorded against them (directly or "
                       + "through the approved task), and the outcomes by kind. Needs groupId; outcomes are team-scoped and exist only "
                       + "for groups whose configuration enables outcomeConfig.")
    OutcomeAnalyticsService.Result outcomes(@BeanParam Query query);

    @GET
    @Path("/decisions")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "List approval decision rows", description = "Newest first; userId only for administrators.")
    DecisionPage listDecisions(@BeanParam Query query, @QueryParam("index") Integer index, @QueryParam("limit") Integer limit);

    @POST
    @Path("/rebuild")
    @Produces(MediaType.APPLICATION_JSON)
    @RolesAllowed("eddi-admin")
    @Operation(summary = "Rebuild approval analytics from the audit ledger",
               description = "Idempotent; repairs gaps and imports pre-6.7 decisions as legacy rows. Runs in the background. "
                       + "Default window: the last 90 days.")
    RebuildStatus rebuild(@QueryParam("from") String from, @QueryParam("to") String to);
}
