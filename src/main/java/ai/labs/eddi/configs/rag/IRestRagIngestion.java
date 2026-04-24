/*
 * Copyright (c) 2016-2026 EDDI contributors
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
@Tag(name = "RAG Ingestion")
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
}
