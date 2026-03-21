package ai.labs.eddi.engine.audit.rest;

import ai.labs.eddi.engine.audit.model.AuditEntry;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;

import java.util.List;

/**
 * Read-only REST API for the immutable audit ledger.
 * <p>
 * Provides query access to audit entries by conversation or agent.
 * No create/update/delete endpoints — entries are created internally
 * by the lifecycle pipeline.
 *
 * @author ginccc
 * @since 6.0.0
 */
@Path("/auditstore")
@Produces(MediaType.APPLICATION_JSON)
public interface IRestAuditStore {

    /**
     * Get the audit trail for a specific conversation.
     *
     * @param conversationId the conversation to query
     * @param skip           number of entries to skip (default: 0)
     * @param limit          maximum entries to return (default: 100)
     * @return list of audit entries, newest first
     */
    @GET
    @Path("/{conversationId}")
    List<AuditEntry> getAuditTrail(
            @PathParam("conversationId") String conversationId,
            @QueryParam("skip") @DefaultValue("0") int skip,
            @QueryParam("limit") @DefaultValue("100") int limit);

    /**
     * Get the audit trail for a specific agent.
     *
     * @param agentId      the Agent identifier
     * @param agentVersion the Agent version (optional, null = all versions)
     * @param skip       number of entries to skip (default: 0)
     * @param limit      maximum entries to return (default: 100)
     * @return list of audit entries, newest first
     */
    @GET
    @Path("/bot/{agentId}")
    List<AuditEntry> getAuditTrailByBot(
            @PathParam("agentId") String agentId,
            @QueryParam("agentVersion") Integer agentVersion,
            @QueryParam("skip") @DefaultValue("0") int skip,
            @QueryParam("limit") @DefaultValue("100") int limit);

    /**
     * Get the number of audit entries for a conversation.
     *
     * @param conversationId the conversation to count
     * @return the entry count
     */
    @GET
    @Path("/{conversationId}/count")
    long getEntryCount(@PathParam("conversationId") String conversationId);
}
