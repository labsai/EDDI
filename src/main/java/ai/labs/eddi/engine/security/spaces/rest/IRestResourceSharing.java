/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.security.spaces.rest;

import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Sharing a configuration resource with another person or team.
 *
 * <h3>Why this is one endpoint family and not fifteen</h3> Sharing is a
 * property of the <em>descriptor</em>, which every configuration type has, so a
 * single path keyed by resource id covers agents, workflows, rule sets and the
 * rest — and the Manager learns one dialog rather than fifteen.
 *
 * <p>
 * {@code cascade} defaults to {@code true} for a reason: people share agents,
 * and an agent shared without its workflows, rule sets and output sets is a
 * name pointing at documents the recipient cannot open. Passing {@code false}
 * shares exactly the one document, which is occasionally what you want and
 * never what you want by default.
 *
 * @author ginccc
 */
@Path("/descriptorstore/descriptors/{id}/shares")
@Tag(name = "Operations / Sharing", description = "Share configuration resources with people and teams")
@RolesAllowed({"eddi-admin", "eddi-editor"})
public interface IRestResourceSharing {

    /**
     * How a resource is shared: its owner, space, visibility, explicit grants, and
     * what the calling user may do with it.
     */
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Read sharing state", description = "Owner, space, visibility, grants, and the caller's effective access level.")
    @APIResponse(responseCode = "200", description = "The sharing state.")
    @APIResponse(responseCode = "403", description = "The caller may not read this resource.")
    Response readShares(@PathParam("id") String id);

    /**
     * Grants a person or team access.
     *
     * @param subject
     *            {@code user:<principal>} or {@code team:<group>}
     * @param level
     *            {@code USE}, {@code VIEW}, {@code EDIT} or {@code OWN}
     * @param cascade
     *            also grant on everything the resource references
     */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Share with a person or team",
               description = "Grants access. Re-granting a subject replaces its level rather than adding a second grant.")
    @APIResponse(responseCode = "200", description = "Updated and skipped resource ids.")
    @APIResponse(responseCode = "403", description = "Only the owner (or an admin) may share.")
    Response share(@PathParam("id") String id,
                   @QueryParam("subject") String subject,
                   @QueryParam("level")
                   @DefaultValue("VIEW") String level,
                   @QueryParam("cascade")
                   @DefaultValue("true") Boolean cascade);

    /** Removes a subject's grant, mirroring {@link #share}. */
    @DELETE
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Stop sharing with a person or team")
    @APIResponse(responseCode = "200", description = "Updated and skipped resource ids.")
    Response revoke(@PathParam("id") String id,
                    @QueryParam("subject") String subject,
                    @QueryParam("cascade")
                    @DefaultValue("true") Boolean cascade);

    /**
     * Sets visibility: {@code private}, {@code space} or {@code published}.
     */
    @PUT
    @Path("/visibility")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Set visibility",
               description = "private = owner and explicit grants only; space = everyone in the resource's space; published = everyone.")
    @APIResponse(responseCode = "200", description = "Updated and skipped resource ids.")
    Response setVisibility(@PathParam("id") String id,
                           @QueryParam("visibility") String visibility,
                           @QueryParam("cascade")
                           @DefaultValue("true") Boolean cascade);

    /**
     * Reassigns ownership. Administrators only — this exists to recover resources
     * whose owner has left, which cannot depend on that owner acting.
     */
    @PUT
    @Path("/owner")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Transfer ownership", description = "Administrators only. Use when a resource's owner has left the organisation.")
    @APIResponse(responseCode = "200", description = "Updated and skipped resource ids.")
    @APIResponse(responseCode = "403", description = "The caller is not an administrator.")
    @RolesAllowed("eddi-admin")
    Response transferOwnership(@PathParam("id") String id,
                               @QueryParam("ownerId") String ownerId,
                               @QueryParam("spaceId") String spaceId,
                               @QueryParam("cascade")
                               @DefaultValue("true") Boolean cascade);
}
