/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.rag;

import jakarta.annotation.security.RolesAllowed;
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.multipart.FileUpload;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;

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
                            @Parameter(name = "kbId", deprecated = true,
                                       description = "Deprecated, and never selects a store: documents always go to the knowledge "
                                               + "base in the path. Accepted only when it names that knowledge base (its id or its "
                                               + "name); any other value is refused with 400")
                            @QueryParam("kbId") String kbId,
                            @Parameter(name = "documentName",
                                       description = "Display name for the document")
                            @QueryParam("documentName")
                            @DefaultValue("unnamed") String documentName,
                            @Parameter(name = "replace",
                                       description = "Supersede what was previously ingested under this documentName: the new "
                                               + "chunks are stored, then the old ones are removed. Requires an explicit "
                                               + "documentName. Off by default, so ingesting a name twice keeps both copies")
                            @QueryParam("replace")
                            @DefaultValue("false") Boolean replace,
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

    // --- Uploaded files (sources of type "upload") ---

    @POST
    @Path("/{id}/sources/{sourceId}/files")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Produces(MediaType.APPLICATION_JSON)
    @APIResponse(responseCode = "200", description = "What was stored and what was refused, per file.")
    @APIResponse(responseCode = "400", description = "Nothing in the request could be stored.")
    @APIResponse(responseCode = "409", description = "This source does not take uploaded files.")
    @Operation(summary = "Upload files to an ingestion source",
               description = "Stores one or more files on a source of type 'upload'. A file replaces any file of "
                       + "the same name. Files are not embedded here — run the source afterwards. Each file is "
                       + "accepted or refused on its own, so one bad file does not lose the rest of the batch.")
    Response uploadSourceFiles(@PathParam("id") String ragConfigId,
                               @PathParam("sourceId") String sourceId,
                               @Parameter(name = "version", required = true, example = "1")
                               @QueryParam("version") Integer version,
                               @RestForm("files") List<FileUpload> files);

    @GET
    @Path("/{id}/sources/{sourceId}/files")
    @Produces(MediaType.APPLICATION_JSON)
    @APIResponse(responseCode = "200", description = "The files this source holds, oldest upload first.")
    @Operation(summary = "List a source's uploaded files",
               description = "Names, types, sizes and content hashes of the files stored on an upload source.")
    Response readSourceFiles(@PathParam("id") String ragConfigId,
                             @PathParam("sourceId") String sourceId,
                             @Parameter(name = "version", required = true, example = "1")
                             @QueryParam("version") Integer version);

    @DELETE
    @Path("/{id}/sources/{sourceId}/files/{fileId}")
    @Produces(MediaType.APPLICATION_JSON)
    @APIResponse(responseCode = "200", description = "The file and the chunks it produced were removed.")
    @APIResponse(responseCode = "404", description = "No such file on this source.")
    @Operation(summary = "Delete an uploaded file",
               description = "Removes the file and, at once rather than at the next run, the vectors it produced.")
    Response deleteSourceFile(@PathParam("id") String ragConfigId,
                              @PathParam("sourceId") String sourceId,
                              @PathParam("fileId") String fileId,
                              @Parameter(name = "version", required = true, example = "1")
                              @QueryParam("version") Integer version);

    // --- Schedules ---

    @GET
    @Path("/{id}/sources/{sourceId}/schedule")
    @Produces(MediaType.APPLICATION_JSON)
    @APIResponse(responseCode = "200", description = "The source's schedule and its recent fires.")
    @Operation(summary = "Read an ingestion source's schedule",
               description = "Whether the source's cron is armed, when it fires next, when it last fired, and the "
                       + "outcome of its recent fires. 'scheduled' is false when the source has no cron, is "
                       + "disabled, or its cron was refused.")
    Response readSourceSchedule(@PathParam("id") String ragConfigId,
                                @PathParam("sourceId") String sourceId,
                                @Parameter(name = "version", required = true, example = "1")
                                @QueryParam("version") Integer version);

    @GET
    @Path("/cron/preview")
    @Produces(MediaType.APPLICATION_JSON)
    @APIResponse(responseCode = "200", description = "Whether the cron would be accepted, and its next fire times.")
    @Operation(summary = "Preview an ingestion cron",
               description = "Applies the checks saving a knowledge base applies to a source's cron — parseable, "
                       + "able to match a date, and no more frequent than this deployment allows — and returns "
                       + "its next fire times in UTC, the zone ingestion schedules run in.")
    Response previewCron(@QueryParam("cron") String cron,
                         @QueryParam("count")
                         @DefaultValue("3") Integer count);

    // --- Inspecting what a knowledge base holds ---

    @GET
    @Path("/{id}/sources/{sourceId}/documents")
    @Produces(MediaType.APPLICATION_JSON)
    @APIResponse(responseCode = "200", description = "A page of the source's documents, most recently ingested first.")
    @Operation(summary = "List a source's ingested documents",
               description = "Every document this source has ingested, as its ingestion state records it: when it "
                       + "was first and last embedded, and whether it has since been removed from the source. "
                       + "'q' narrows to documents whose URL, file name or id contains it.")
    Response readSourceDocuments(@PathParam("id") String ragConfigId,
                                 @PathParam("sourceId") String sourceId,
                                 @Parameter(name = "version", required = true, example = "1")
                                 @QueryParam("version") Integer version,
                                 @QueryParam("q") String query,
                                 @QueryParam("offset")
                                 @DefaultValue("0") Integer offset,
                                 @QueryParam("limit")
                                 @DefaultValue("50") Integer limit);

    @GET
    @Path("/{id}/sources/{sourceId}/chunks")
    @Produces(MediaType.APPLICATION_JSON)
    @APIResponse(responseCode = "200", description = "The document's chunks, in document order.")
    @APIResponse(responseCode = "501", description = "The vector store cannot filter by metadata.")
    @Operation(summary = "Read the chunks of one ingested document",
               description = "The text chunks one document was split into and stored as, with their metadata. "
                       + "Costs one embedding call: the vector stores offer no listing, so the chunks are found "
                       + "by a search filtered to the document.")
    Response readDocumentChunks(@PathParam("id") String ragConfigId,
                                @PathParam("sourceId") String sourceId,
                                @Parameter(name = "version", required = true, example = "1")
                                @QueryParam("version") Integer version,
                                @Parameter(name = "documentId", required = true,
                                           description = "The document's id, as the documents listing returns it")
                                @QueryParam("documentId") String documentId,
                                @QueryParam("limit")
                                @DefaultValue("100") Integer limit);

    @POST
    @Path("/{id}/search")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @APIResponse(responseCode = "200", description = "The chunks the query retrieves, best match first.")
    @Operation(summary = "Test retrieval against a knowledge base",
               description = "Embeds the query and searches the knowledge base exactly as an agent's retrieval "
                       + "does, returning each chunk with its score and metadata. Costs one embedding call.")
    Response searchKnowledgeBase(@PathParam("id") String ragConfigId,
                                 @Parameter(name = "version", required = true, example = "1")
                                 @QueryParam("version") Integer version,
                                 KnowledgeBaseSearch search);

    /**
     * A test retrieval.
     *
     * @param maxResults
     *            defaults to the knowledge base's own {@code maxResults}
     * @param minScore
     *            defaults to the knowledge base's own {@code minScore}
     * @param sourceId
     *            narrows the search to one source's chunks; absent searches all of
     *            them
     */
    record KnowledgeBaseSearch(String query, Integer maxResults, Double minScore, String sourceId) {
    }

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
