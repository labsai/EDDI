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
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.Map;

/**
 * REST surface for org/team preset templates (I10): list what's available, read
 * one, and instantiate it into a real group config by assigning agents to the
 * template's named roles.
 *
 * @author ginccc
 */
@Path("/groupstore/templates")
@Tag(name = "Agents / Groups", description = "Org/team preset templates")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed({"eddi-admin", "eddi-editor"})
public interface IRestGroupTemplates {

    /**
     * @param roleAssignments
     *            template role → agent id (for HUMAN roles: the principal id)
     */
    record InstantiateRequest(String name, Map<String, String> roleAssignments) {
    }

    @GET
    @Operation(description = "List the packaged group templates: id, title, description and required roles.")
    Response listTemplates();

    @GET
    @Path("/{templateId}")
    @Operation(description = "Read one template — its manifest and the full config it instantiates.")
    Response readTemplate(@PathParam("templateId") String templateId);

    @POST
    @Path("/{templateId}/instantiate")
    @Operation(description = "Create a group from a template by assigning agents to its roles. "
            + "Runs the normal store path — every save-time validation applies.")
    Response instantiate(@PathParam("templateId") String templateId, InstantiateRequest request);
}
