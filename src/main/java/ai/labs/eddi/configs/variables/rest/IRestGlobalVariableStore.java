/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.variables.rest;

import ai.labs.eddi.configs.variables.model.GlobalVariable;
import jakarta.annotation.security.RolesAllowed;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;

/**
 * REST API for global configuration variables, scoped per tenant.
 * <p>
 * Global variables are key-value pairs available in agent configurations via:
 * <ul>
 * <li>{@code ${vars:<key>}} — late-binding, default tenant (works
 * everywhere)</li>
 * <li>{@code ${vars:tenantId/<key>}} — late-binding, explicit tenant</li>
 * <li>{@code {{vars.<key>}}} — template layer (LlmTask system prompts)</li>
 * </ul>
 *
 * @author ginccc
 * @since 6.0.0
 */
@Path("/variablestore/variables")
@Tag(name = "Configuration / Global Variables")
@RolesAllowed({"eddi-admin", "eddi-editor"})
public interface IRestGlobalVariableStore {

    @GET
    @Path("/{tenantId}")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(operationId = "listGlobalVariables", description = "List all global variables for a tenant.")
    List<GlobalVariable> listVariables(@PathParam("tenantId") String tenantId);

    @GET
    @Path("/{tenantId}/{key}")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(operationId = "getGlobalVariable", description = "Get a single global variable by tenant and key.")
    GlobalVariable getVariable(@PathParam("tenantId") String tenantId, @PathParam("key") String key);

    @PUT
    @Path("/{tenantId}/{key}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(operationId = "upsertGlobalVariable", description = "Create or update a global variable.")
    Response upsertVariable(@PathParam("tenantId") String tenantId, @PathParam("key") String key,
                            GlobalVariable variable);

    @DELETE
    @Path("/{tenantId}/{key}")
    @Operation(operationId = "deleteGlobalVariable", description = "Delete a global variable.")
    Response deleteVariable(@PathParam("tenantId") String tenantId, @PathParam("key") String key);
}
