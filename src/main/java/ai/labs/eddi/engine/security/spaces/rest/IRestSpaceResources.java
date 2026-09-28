/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.security.spaces.rest;

import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
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

import java.util.List;

/**
 * Secrets and variables that belong to a space — a person's own, or a team's —
 * managed by the space's members without an administrator.
 *
 * <h3>Why this exists</h3> The vault and the global variables were
 * deployment-wide: an editor could not store their own LLM key (the vault is
 * {@code eddi-admin} only), and any editor could overwrite any variable another
 * team relied on. Each space now has its own tenant in both stores. Its members
 * read and write it here; agents reach it with an explicit reference —
 * {@code ${vault:<tenant>/key}} or {@code ${vars:<tenant>/key}}, where the
 * tenant is what every response names.
 *
 * <h3>Who may use what</h3> Writing requires membership of the space. Deploying
 * an agent that references a space's tenant requires the deployer to be a
 * member too — so a colleague who was lent edit access to your agent cannot
 * point it at your team's key and deploy it.
 */
@Path("/spacestore")
@Tag(name = "Operations / Sharing", description = "Share configuration resources with people and teams")
@RolesAllowed({"eddi-admin", "eddi-editor"})
public interface IRestSpaceResources {

    /**
     * A secret as its space's members see it — never the value.
     *
     * @param keyName
     *            the name within the space
     * @param reference
     *            what to paste into a configuration
     * @param description
     *            the owner's note
     * @param allowedAgents
     *            agent ids allowed to use it; empty or {@code *} means any agent
     *            that may reference the space at all
     * @param createdAt
     *            ISO-8601, or null
     */
    record SpaceSecret(String keyName, String reference, String description, List<String> allowedAgents, String createdAt) {
    }

    /**
     * What to store. {@code value} is required.
     *
     * @param value
     *            the secret; write-only, never returned
     */
    record SecretWrite(String value, String description, List<String> allowedAgents) {
    }

    /**
     * A variable in a space.
     *
     * @param reference
     *            what to paste into a configuration
     */
    record SpaceVariable(String key, String value, String description, String reference) {
    }

    /** What to store. */
    record VariableWrite(String value, String description) {
    }

    /**
     * The tenant a space's secrets and variables live in.
     *
     * @param space
     *            the space id
     * @param tenant
     *            the id to use in {@code ${vault:<tenant>/…}} and
     *            {@code ${vars:<tenant>/…}}
     */
    record SpaceTenant(String space, String tenant) {
    }

    @GET
    @Path("/tenant")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "The tenant id of one of my spaces", description = "What references to the space's secrets and variables use.")
    @APIResponse(responseCode = "200", description = "The tenant.")
    @APIResponse(responseCode = "403", description = "The caller is not a member of the space.")
    SpaceTenant readTenant(@QueryParam("space") String space);

    @GET
    @Path("/secrets")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "List a space's secrets", description = "Names, descriptions and grants — never values.")
    @APIResponse(responseCode = "200", description = "The space's secrets.")
    @APIResponse(responseCode = "403", description = "The caller is not a member of the space.")
    @APIResponse(responseCode = "503", description = "The vault is not configured.")
    List<SpaceSecret> listSecrets(@QueryParam("space") String space);

    @PUT
    @Path("/secrets/{keyName}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Store a secret in a space", description = "Creates or replaces it. The value is encrypted and never returned.")
    @APIResponse(responseCode = "200", description = "Stored.")
    @APIResponse(responseCode = "403", description = "The caller is not a member of the space.")
    Response storeSecret(@QueryParam("space") String space, @PathParam("keyName") String keyName, SecretWrite body);

    @DELETE
    @Path("/secrets/{keyName}")
    @Operation(summary = "Delete a secret from a space")
    @APIResponse(responseCode = "204", description = "Deleted.")
    @APIResponse(responseCode = "403", description = "The caller is not a member of the space.")
    @APIResponse(responseCode = "404", description = "No such secret.")
    Response deleteSecret(@QueryParam("space") String space, @PathParam("keyName") String keyName);

    @GET
    @Path("/variables")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "List a space's variables")
    @APIResponse(responseCode = "200", description = "The space's variables.")
    @APIResponse(responseCode = "403", description = "The caller is not a member of the space.")
    List<SpaceVariable> listVariables(@QueryParam("space") String space);

    @PUT
    @Path("/variables/{key}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Set a variable in a space")
    @APIResponse(responseCode = "200", description = "Stored.")
    @APIResponse(responseCode = "403", description = "The caller is not a member of the space.")
    Response storeVariable(@QueryParam("space") String space, @PathParam("key") String key, VariableWrite body);

    @DELETE
    @Path("/variables/{key}")
    @Operation(summary = "Delete a variable from a space")
    @APIResponse(responseCode = "204", description = "Deleted.")
    @APIResponse(responseCode = "403", description = "The caller is not a member of the space.")
    Response deleteVariable(@QueryParam("space") String space, @PathParam("key") String key);
}
