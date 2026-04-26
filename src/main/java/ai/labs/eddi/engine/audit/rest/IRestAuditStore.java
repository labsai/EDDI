/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.audit.rest;

import ai.labs.eddi.engine.audit.model.AuditEntry;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.List;

/**
 * Read-only REST API for the immutable audit ledger.
 *
 * @since 6.0.0
 */
@Path("/auditstore")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Audit Trail")
@RolesAllowed("eddi-admin")
public interface IRestAuditStore {

    /**
     * Get the audit trail for a specific conversation.
     *
     * @param conversationId
     *            the conversation to query
     * @param skip
     *            number of entries to skip (default: 0)
     * @param limit
     *            maximum entries to return (default: 100)
     * @return list of audit entries, newest first
     */
    @GET
    @Path("/{conversationId}")
    List<AuditEntry> getAuditTrail(@PathParam("conversationId") String conversationId, @QueryParam("skip")
    @DefaultValue("0") int skip,
                                   @QueryParam("limit")
                                   @DefaultValue("100") int limit);

    /**
     * Get the audit trail for a specific agent.
     *
     * @param agentId
     *            the Agent identifier
     * @param agentVersion
     *            the Agent version (optional, null = all versions)
     * @param skip
     *            number of entries to skip (default: 0)
     * @param limit
     *            maximum entries to return (default: 100)
     * @return list of audit entries, newest first
     */
    @GET
    @Path("/agent/{agentId}")
    List<AuditEntry> getAuditTrailByAgent(@PathParam("agentId") String agentId, @QueryParam("agentVersion") Integer agentVersion,
                                          @QueryParam("skip")
                                          @DefaultValue("0") int skip,
                                          @QueryParam("limit")
                                          @DefaultValue("100") int limit);

    /**
     * Get the number of audit entries for a conversation.
     *
     * @param conversationId
     *            the conversation to count
     * @return the entry count
     */
    @GET
    @Path("/{conversationId}/count")
    long getEntryCount(@PathParam("conversationId") String conversationId);
}
