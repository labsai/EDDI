/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.security.spaces.rest;

import ai.labs.eddi.engine.security.spaces.rest.model.WorkspaceInfo;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * What workspaces mean for the calling user.
 *
 * <h3>Why this endpoint exists</h3> A client cannot tell a deployment with
 * workspaces switched off from one where every resource predates ownership —
 * both return descriptors with no owner, no space and no visibility. Guessing
 * wrong means offering a Share action that silently does nothing. So the server
 * says which it is.
 * <p>
 * It also serves the caller's spaces rather than leaving a client to derive
 * them from the token. The derivation is not hard, but getting it subtly wrong
 * fails silently: a space id encoded differently selects nothing, which looks
 * like an empty workspace rather than a bug.
 *
 * @author ginccc
 */
@Path("/workspaces")
@Tag(name = "Operations / Sharing", description = "Share configuration resources with people and teams")
@RolesAllowed({"eddi-admin", "eddi-editor"})
public interface IRestWorkspaces {

    String resourceURI = "eddi://ai.labs.workspaces/workspaces/";

    /**
     * Whether workspaces are enforced, and which spaces this caller can reach.
     * <p>
     * Always answers for the caller who asked — never takes a principal as a
     * parameter, so it cannot be used to enumerate somebody else's group
     * membership.
     */
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Read workspace settings for the calling user",
               description = "Whether workspace enforcement is active, the caller's principal and default space, "
                       + "and every space the caller can reach.")
    @APIResponse(responseCode = "200", description = "The caller's workspace context.")
    WorkspaceInfo readWorkspaceInfo();
}
