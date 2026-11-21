/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.groups;

import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * REST surface for the real-world outcomes of a team's work: the inbound
 * webhook an external system calls (reply received, PR merged, listing live, a
 * metric reading) and the read of what was recorded. Off until the group's
 * configuration carries an {@code outcomeConfig}.
 *
 * @since 6.8.0
 */
@Path("/groupstore/groups/{groupId}/outcomes")
@Tag(name = "Agents / Groups", description = "Standing team workspace — backlog, cadences, metrics, outcomes")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed({"eddi-admin", "eddi-editor"})
public interface IRestGroupOutcomes {

    /**
     * One outcome as an external system reports it.
     *
     * @param subjectType
     *            {@code TASK} (a backlog task id) or {@code DECISION} (an approval
     *            decision id from the audit ledger)
     * @param subjectId
     *            the task or decision id
     * @param kind
     *            what happened, e.g. {@code reply_received}, {@code pr_merged},
     *            {@code listing_live}, {@code metric_reading}
     * @param metric
     *            the metric of a reading, e.g. {@code signups}
     * @param value
     *            the reading, when there is one
     * @param unit
     *            unit of {@code value}
     * @param note
     *            a short remark (at most 1000 characters)
     * @param source
     *            the reporting system, e.g. {@code github}
     * @param occurredAt
     *            ISO-8601 instant it happened; default now
     * @param idempotencyKey
     *            the sender's retry key: the same key stores once
     */
    record OutcomeRequest(String subjectType, String subjectId, String kind, String metric, Double value, String unit, String note, String source,
            String occurredAt, String idempotencyKey) {
    }

    @POST
    @Operation(description = "Record an outcome against a backlog task or an approval decision. 201 when stored, 200 when the "
            + "idempotencyKey was already stored, 409 when outcomes are not enabled for the group (add outcomeConfig) or the "
            + "subject is full. Visible to the team's RETRO phase and to approval analytics.")
    Response record(@PathParam("groupId") String groupId, OutcomeRequest request);

    @GET
    @Operation(operationId = "listGroupOutcomes",
               description = "A team's outcomes, newest first: those of one subject when subjectId is given, otherwise the most recent.")
    Response list(@PathParam("groupId") String groupId, @QueryParam("subjectId") String subjectId, @QueryParam("limit") Integer limit);
}
