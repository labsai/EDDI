/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.rag;

import ai.labs.eddi.configs.IRestVersionInfo;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.configs.rag.model.RagConfiguration;
import jakarta.annotation.security.RolesAllowed;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;

/**
 * JAX-RS interface for RAG (Knowledge Base) configuration store.
 */
@Path("/ragstore/rags")
@Tag(name = "RAG Knowledge Bases")
@RolesAllowed({"eddi-admin", "eddi-editor"})
public interface IRestRagStore extends IRestVersionInfo {
    String resourceBaseType = "eddi://ai.labs.rag";
    String resourceURI = resourceBaseType + "/ragstore/rags/";
    String versionQueryParam = "?version=";

    @GET
    @Path("/jsonSchema")
    @Produces(MediaType.APPLICATION_JSON)
    @APIResponse(responseCode = "200", description = "JSON Schema for RAG validation.")
    @Operation(operationId = "readRagJsonSchema", summary = "Get JSON Schema", description = "Read JSON Schema for RAG configuration.")
    Response readJsonSchema();

    @GET
    @Path("descriptors")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "List RAG descriptors", description = "Read list of RAG configuration descriptors.")
    List<DocumentDescriptor> readRagDescriptors(@QueryParam("filter")
    @DefaultValue("") String filter,
                                                @QueryParam("index")
                                                @DefaultValue("0") Integer index,
                                                @QueryParam("limit")
                                                @DefaultValue("20") Integer limit);

    @GET
    @Path("/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Read RAG config", description = "Read a specific RAG configuration.")
    RagConfiguration readRag(@PathParam("id") String id,
                             @Parameter(name = "version", required = true, example = "1")
                             @QueryParam("version") Integer version);

    @PUT
    @Path("/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(summary = "Update RAG config", description = "Update an existing RAG configuration.")
    Response updateRag(@PathParam("id") String id,
                       @Parameter(name = "version", required = true, example = "1")
                       @QueryParam("version") Integer version, RagConfiguration ragConfiguration);

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(summary = "Create RAG config", description = "Create a new RAG configuration.")
    Response createRag(RagConfiguration ragConfiguration);

    @POST
    @Path("/{id}")
    @Operation(summary = "Duplicate RAG config", description = "Duplicate an existing RAG configuration.")
    Response duplicateRag(@PathParam("id") String id, @QueryParam("version") Integer version);

    @DELETE
    @Path("/{id}")
    @Operation(summary = "Delete RAG config", description = "Delete a RAG configuration.")
    Response deleteRag(@PathParam("id") String id,
                       @Parameter(name = "version", required = true, example = "1")
                       @QueryParam("version") Integer version,
                       @QueryParam("permanent")
                       @DefaultValue("false") Boolean permanent);
}
