/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.audit.rest;

import ai.labs.eddi.engine.audit.model.AuditEntry;
import ai.labs.eddi.engine.audit.model.AuditVerificationReport;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.List;

/**
 * Read-only REST API for the immutable audit ledger, including the integrity
 * sweep that turns the stored HMACs into something an auditor can actually
 * check.
 *
 * @since 6.0.0
 */
@Path("/auditstore")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Security / Audit Trail", description = "Immutable audit ledger queries")
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

    /**
     * Verify the integrity of a conversation's audit trail: recompute every entry's
     * HMAC and check the per-conversation sequence for gaps.
     * <p>
     * Note the path is {@code /verify/{conversationId}} rather than
     * {@code /{conversationId}/verify} so it cannot be shadowed by, or shadow, the
     * {@code /{conversationId}} trail lookup.
     *
     * @param conversationId
     *            the conversation to verify
     * @param skip
     *            number of entries to skip (pagination). A non-zero skip makes the
     *            chain check report the range's own continuity only
     * @param limit
     *            maximum entries to verify (default: 1000)
     * @return per-entry HMAC status plus the chain verdict
     */
    @GET
    @Path("/verify/{conversationId}")
    AuditVerificationReport verifyConversation(@PathParam("conversationId") String conversationId, @QueryParam("skip")
    @DefaultValue("0") int skip,
                                               @QueryParam("limit")
                                               @DefaultValue("1000") int limit);

    /**
     * Verify the integrity of an agent's audit entries. Spans many conversations,
     * so only the per-entry HMACs are checked — the chain verdict is
     * {@code NOT_APPLICABLE}.
     *
     * @param agentId
     *            the Agent identifier
     * @param agentVersion
     *            the Agent version (optional, null = all versions)
     * @param skip
     *            number of entries to skip
     * @param limit
     *            maximum entries to verify (default: 1000)
     * @return per-entry HMAC status
     */
    @GET
    @Path("/verify/agent/{agentId}")
    AuditVerificationReport verifyAgent(@PathParam("agentId") String agentId, @QueryParam("agentVersion") Integer agentVersion,
                                        @QueryParam("skip")
                                        @DefaultValue("0") int skip,
                                        @QueryParam("limit")
                                        @DefaultValue("1000") int limit);
}
