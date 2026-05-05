/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.ingestion;

import ai.labs.eddi.configs.IRestVersionInfo;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.configs.ingestion.model.RagIngestionSource;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.List;
import java.util.Map;

import static jakarta.ws.rs.core.MediaType.APPLICATION_JSON;

/**
 * JAX-RS REST API for RAG ingestion source management.
 * <p>
 * Provides CRUD operations, manual triggering, and status queries for web
 * ingestion sources.
 */
@Path("/ragstore/ingestion-sources")
@Tag(name = "RAG Ingestion Sources")
@RolesAllowed({"eddi-admin", "eddi-editor"})
public interface IRestRagIngestionSourceStore extends IRestVersionInfo {

    String resourceBaseType = "eddi://ai.labs.ingestion";
    String resourceURI = resourceBaseType + "/ingestionstore/ingestionsources/";
    String versionQueryParam = "?version=";

    @GET
    @Path("/jsonSchema")
    @Produces(APPLICATION_JSON)
    @APIResponse(responseCode = "200", description = "JSON Schema for RagIngestionSource")
    @Operation(summary = "Get JSON Schema", description = "Returns the JSON Schema for ingestion source validation")
    Response readJsonSchema();

    @GET
    @Path("/descriptors")
    @Produces(APPLICATION_JSON)
    @APIResponse(responseCode = "200", description = "List of ingestion source descriptors")
    @Operation(summary = "List descriptors", description = "Returns a list of ingestion source descriptors with filtering and pagination")
    List<DocumentDescriptor> readIngestionSourceDescriptors(
                                                            @QueryParam("filter")
                                                            @DefaultValue("") String filter,
                                                            @QueryParam("index")
                                                            @DefaultValue("0") Integer index,
                                                            @QueryParam("limit")
                                                            @DefaultValue("20") Integer limit);

    @GET
    @Path("/byRagConfig")
    @Produces(APPLICATION_JSON)
    @APIResponse(responseCode = "200", description = "List of ingestion sources for the given RAG config")
    @Operation(summary = "Find by RAG config",
               description = "Returns ingestion sources that reference a specific RAG configuration URI")
    List<Map<String, Object>> findIngestionSourcesByRagConfig(
                                                              @QueryParam("ragConfigUri") String ragConfigUri,
                                                              @QueryParam("index")
                                                              @DefaultValue("0") Integer index,
                                                              @QueryParam("limit")
                                                              @DefaultValue("20") Integer limit);

    @GET
    @Path("/{id}")
    @Produces(APPLICATION_JSON)
    @APIResponse(responseCode = "200", description = "Ingestion source configuration")
    @APIResponse(responseCode = "404", description = "Ingestion source not found")
    @Operation(summary = "Get ingestion source", description = "Returns a specific ingestion source configuration")
    RagIngestionSource readIngestionSource(
                                           @PathParam("id") String id,
                                           @Parameter(name = "version", required = true, example = "1")
                                           @QueryParam("version") Integer version);

    @PUT
    @Path("/{id}")
    @Consumes(APPLICATION_JSON)
    @APIResponse(responseCode = "200", description = "Ingestion source updated")
    @APIResponse(responseCode = "404", description = "Ingestion source not found")
    @Operation(summary = "Update ingestion source", description = "Updates an existing ingestion source configuration")
    Response updateIngestionSource(
                                   @PathParam("id") String id,
                                   @Parameter(name = "version", required = true, example = "1")
                                   @QueryParam("version") Integer version,
                                   RagIngestionSource source);

    @POST
    @Consumes(APPLICATION_JSON)
    @APIResponse(responseCode = "201", description = "Ingestion source created")
    @Operation(summary = "Create ingestion source", description = "Creates a new ingestion source configuration")
    Response createIngestionSource(RagIngestionSource source);

    @POST
    @Path("/{id}")
    @APIResponse(responseCode = "201", description = "Ingestion source duplicated")
    @Operation(summary = "Duplicate ingestion source", description = "Creates a copy of an existing ingestion source")
    Response duplicateIngestionSource(
                                      @PathParam("id") String id,
                                      @Parameter(name = "version", required = true, example = "1")
                                      @QueryParam("version") Integer version);

    @DELETE
    @Path("/{id}")
    @APIResponse(responseCode = "200", description = "Ingestion source deleted")
    @APIResponse(responseCode = "404", description = "Ingestion source not found")
    @Operation(summary = "Delete ingestion source", description = "Deletes an ingestion source configuration")
    Response deleteIngestionSource(
                                   @PathParam("id") String id,
                                   @Parameter(name = "version", required = true, example = "1")
                                   @QueryParam("version") Integer version,
                                   @QueryParam("permanent")
                                   @DefaultValue("false") Boolean permanent);

    @POST
    @Path("/{id}/trigger")
    @Produces(APPLICATION_JSON)
    @APIResponse(responseCode = "202", description = "Ingestion triggered (async)")
    @APIResponse(responseCode = "404", description = "Ingestion source not found")
    @Operation(summary = "Trigger ingestion", description = "Manually triggers a crawl and ingest for this source")
    Response triggerIngestion(
                              @PathParam("id") String id,
                              @Parameter(name = "version", required = true, example = "1")
                              @QueryParam("version") Integer version);
}
