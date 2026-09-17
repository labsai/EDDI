/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.rag;

import jakarta.annotation.security.RolesAllowed;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * JAX-RS interface for RAG document ingestion.
 */
@Path("/ragstore/rags")
@Tag(name = "Knowledge / RAG Ingestion", description = "RAG document ingestion and indexing")
@RolesAllowed({"eddi-admin", "eddi-editor"})
public interface IRestRagIngestion {

    @POST
    @Path("/{id}/ingest")
    @Consumes(MediaType.TEXT_PLAIN)
    @Produces(MediaType.APPLICATION_JSON)
    @APIResponse(responseCode = "202", description = "Ingestion started — returns ingestion ID for status polling.")
    @Operation(summary = "Ingest document", description = "Ingest a text document into a knowledge base. Runs async on a virtual thread.")
    Response ingestDocument(@PathParam("id") String ragConfigId,
                            @Parameter(name = "version", required = true, example = "1")
                            @QueryParam("version") Integer version,
                            @Parameter(name = "kbId", description = "Knowledge base ID (defaults to RAG config name)")
                            @QueryParam("kbId") String kbId,
                            @Parameter(name = "documentName",
                                       description = "Display name for the document")
                            @QueryParam("documentName")
                            @DefaultValue("unnamed") String documentName,
                            String documentContent);

    @GET
    @Path("/{id}/ingestion/{ingestionId}/status")
    @Produces(MediaType.APPLICATION_JSON)
    @APIResponse(responseCode = "200", description = "Ingestion status: pending, processing, completed, or failed.")
    @Operation(summary = "Get ingestion status", description = "Poll the status of an async ingestion operation.")
    Response getIngestionStatus(@PathParam("id") String ragConfigId, @PathParam("ingestionId") String ingestionId);

    // --- Ingestion sources ---

    @POST
    @Path("/{id}/sources/{sourceId}/run")
    @Produces(MediaType.APPLICATION_JSON)
    @APIResponse(responseCode = "202", description = "Run started; poll the runs endpoint for progress.")
    @APIResponse(responseCode = "409", description = "A run is already in flight for this source.")
    @Operation(summary = "Run an ingestion source",
               description = "Crawls the source and updates the knowledge base. Runs async on a virtual thread.")
    Response runSource(@PathParam("id") String ragConfigId,
                       @PathParam("sourceId") String sourceId,
                       @Parameter(name = "version", required = true, example = "1")
                       @QueryParam("version") Integer version);

    @POST
    @Path("/{id}/sources/{sourceId}/preview")
    @Produces(MediaType.APPLICATION_JSON)
    @APIResponse(responseCode = "200", description = "What a run would change, having embedded nothing.")
    @Operation(summary = "Preview an ingestion source",
               description = "Crawls the source and reports what would change without embedding or recording "
                       + "anything. Blocks for the length of the crawl, so keep the source's limits small.")
    Response previewSource(@PathParam("id") String ragConfigId,
                           @PathParam("sourceId") String sourceId,
                           @Parameter(name = "version", required = true, example = "1")
                           @QueryParam("version") Integer version);

    @GET
    @Path("/{id}/sources/{sourceId}/runs")
    @Produces(MediaType.APPLICATION_JSON)
    @APIResponse(responseCode = "200", description = "Run history, newest first.")
    @Operation(summary = "Ingestion run history",
               description = "Past runs of this source with their counters, cost and any error.")
    Response readSourceRuns(@PathParam("id") String ragConfigId,
                            @PathParam("sourceId") String sourceId,
                            @Parameter(name = "version", required = true, example = "1")
                            @QueryParam("version") Integer version,
                            @QueryParam("limit")
                            @DefaultValue("20") Integer limit);

    @DELETE
    @Path("/{id}/sources/{sourceId}/documents")
    @Produces(MediaType.APPLICATION_JSON)
    @APIResponse(responseCode = "200", description = "Ingestion state for this source was forgotten.")
    @Operation(summary = "Purge a source's ingestion state",
               description = "Forgets what this source has ingested, so the next run re-ingests everything. "
                       + "Does not by itself remove vectors already stored.")
    Response purgeSource(@PathParam("id") String ragConfigId,
                         @PathParam("sourceId") String sourceId,
                         @Parameter(name = "version", required = true, example = "1")
                         @QueryParam("version") Integer version);
}
