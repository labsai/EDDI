/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.rag.rest;

import ai.labs.eddi.configs.descriptors.model.AccessLevel;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import ai.labs.eddi.configs.rag.IRestRagIngestion;
import ai.labs.eddi.configs.rag.IRestRagStore;
import ai.labs.eddi.configs.rag.model.IngestionSource;
import ai.labs.eddi.configs.rag.model.RagConfiguration;
import ai.labs.eddi.modules.ingestion.KnowledgeBaseInspector;
import ai.labs.eddi.modules.ingestion.PreviewBusyException;
import ai.labs.eddi.modules.ingestion.RagSourceIngestionService;
import ai.labs.eddi.modules.ingestion.files.IIngestedFileStore;
import ai.labs.eddi.modules.ingestion.files.IngestedFileService;
import ai.labs.eddi.modules.rag.RagIngestionService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.multipart.FileUpload;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * REST implementation for RAG document ingestion. Delegates to
 * {@link RagIngestionService} for async processing.
 */
@ApplicationScoped
public class RestRagIngestion implements IRestRagIngestion {

    private static final Logger LOGGER = Logger.getLogger(RestRagIngestion.class);

    private final IRestRagStore restRagStore;
    private final RagIngestionService ragIngestionService;
    private final RagSourceIngestionService sourceIngestionService;
    private final IngestedFileService ingestedFileService;
    private final KnowledgeBaseInspector inspector;

    private final ResourceAccessGuard resourceAccessGuard;

    @Inject
    public RestRagIngestion(IRestRagStore restRagStore, RagIngestionService ragIngestionService,
            RagSourceIngestionService sourceIngestionService, IngestedFileService ingestedFileService,
            KnowledgeBaseInspector inspector, ResourceAccessGuard resourceAccessGuard) {
        this.inspector = inspector;
        this.resourceAccessGuard = resourceAccessGuard;
        this.restRagStore = restRagStore;
        this.ragIngestionService = ragIngestionService;
        this.sourceIngestionService = sourceIngestionService;
        this.ingestedFileService = ingestedFileService;
    }

    /**
     * The knowledge base's configuration as the engine needs it. Every use here
     * builds an embedding model or store from it, so it must carry the real
     * credentials: {@link RestRagStore#readRag} redacts plaintext literals for a
     * non-administrator, and an editor ingesting into a knowledge base whose
     * {@code apiKey} is a literal would otherwise send the redaction marker to the
     * provider. {@link RestRagStore#readUnredacted} applies the same access check
     * without the redaction; the document never leaves the server. Anything other
     * than the real store (a test double) is read through the interface.
     */
    private RagConfiguration readEngineConfig(String ragConfigId, Integer version) {
        return restRagStore instanceof RestRagStore store
                ? store.readUnredacted(ragConfigId, version)
                : restRagStore.readRag(ragConfigId, version);
    }

    /**
     * The name {@code documentName} defaults to — see {@link IRestRagIngestion}.
     */
    static final String UNNAMED_DOCUMENT = "unnamed";

    private static final int DEFAULT_DOCUMENT_PAGE = 50;

    private static final int MAX_DOCUMENT_PAGE = 500;

    /** A test query, not a document: anything longer is a mistake or abuse. */
    static final int MAX_SEARCH_QUERY_LENGTH = 2000;

    @Override
    public Response ingestDocument(String ragConfigId, Integer version, String kbId, String documentName, Boolean replace,
                                   String documentContent) {
        if (documentContent == null || documentContent.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("error", "Document content is required")).build();
        }
        boolean replacing = Boolean.TRUE.equals(replace);
        if (replacing && (documentName == null || documentName.isBlank() || UNNAMED_DOCUMENT.equals(documentName))) {
            // Replacement is keyed on the name. Without one, every document ingested
            // without a name shares "unnamed", and replacing would delete all of them.
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "replace=true needs an explicit documentName: it replaces what was ingested under that name"))
                    .build();
        }

        // EDIT, not the VIEW that reading the config needs. Ingestion writes documents
        // into the knowledge base every agent using this config retrieves from, so
        // gating it on read access would let anyone poison a *published* RAG config's
        // knowledge base — published grants VIEW to everyone by design.
        //
        // Checked before readRag so the refusal is a 403: the catch below turns any
        // exception into a 404, which would otherwise mask the real answer.
        resourceAccessGuard.requireAccess(ragConfigId, AccessLevel.EDIT, "RAG configuration");

        RagConfiguration ragConfig;
        try {
            ragConfig = readEngineConfig(ragConfigId, version);
        } catch (Exception e) {
            LOGGER.warnf("Failed to load RAG config %s v%d: %s", sanitize(ragConfigId), version, e.getMessage());
            return Response.status(Response.Status.NOT_FOUND).entity(Map.of("error", "RAG configuration not found: " + ragConfigId + " v" + version))
                    .build();
        }

        // The knowledge base written to is the one in the path — the one EDIT was just
        // checked on — and nothing else. ?kbId= used to override it: a caller with
        // EDIT on their own knowledge base could name another's and write into its
        // store, or with replace=true delete from it. It is still accepted when it
        // names this knowledge base (its id, or the name it defaulted to), so
        // existing clients keep working, and refused otherwise rather than ignored:
        // a client that meant another knowledge base must not be told 202.
        if (kbId != null && !kbId.isBlank() && !kbId.equals(ragConfigId) && !kbId.equals(ragConfig.getName())) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "kbId names a different knowledge base. Documents are always ingested into the "
                            + "knowledge base in the path; omit kbId, or ingest through the other knowledge base's own endpoint."))
                    .build();
        }

        String ingestionId;
        try {
            ingestionId = ragIngestionService.ingest(ragConfigId, documentContent, documentName, ragConfig, replacing);
        } catch (IllegalArgumentException e) {
            // The configuration cannot be addressed (a 6.5.0-layout knowledge base
            // with no name, say).
            return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("error", e.getMessage())).build();
        }

        LOGGER.infof("Ingestion started: id=%s, kb=%s, doc=%s, chars=%d", ingestionId, sanitize(ragConfigId), sanitize(documentName),
                documentContent.length());

        return Response.accepted(Map.of("ingestionId", ingestionId, "kbId", ragConfigId, "status", "pending")).build();
    }

    @Override
    public Response getIngestionStatus(String ragConfigId, String ingestionId) {
        // The status of an ingestion is only meaningful to whoever could have started
        // it, and the caller names the RAG config in the path — so check it.
        resourceAccessGuard.requireAccess(ragConfigId, AccessLevel.VIEW, "RAG configuration");
        String status = ragIngestionService.getStatus(ingestionId);
        if (RagIngestionService.STATUS_UNKNOWN.equals(status)) {
            // Never started here, or its status has already expired. A 200 with
            // status "unknown" read as "still in flight" to every polling client.
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("ingestionId", ingestionId, "status", status, "error",
                            "No ingestion with this id is known (statuses are kept for one hour)"))
                    .build();
        }
        String warning = ragIngestionService.getWarning(ingestionId);
        if (warning != null) {
            return Response.ok(Map.of("ingestionId", ingestionId, "status", status, "warning", warning)).build();
        }
        return Response.ok(Map.of("ingestionId", ingestionId, "status", status)).build();
    }

    // --- Ingestion sources ---

    @Override
    public Response runSource(String ragConfigId, String sourceId, Integer version) {
        // EDIT for the same reason ingestDocument needs it: a run rewrites the
        // knowledge base every agent using this config retrieves from, and a
        // published config grants VIEW to everyone by design.
        resourceAccessGuard.requireAccess(ragConfigId, AccessLevel.EDIT, "RAG configuration");

        var resolved = resolveSource(ragConfigId, sourceId, version);
        if (resolved.error() != null) {
            return resolved.error();
        }

        if (!resolved.source().isEnabled()) {
            // Answering 202 and then writing a FAILED row into the history for a
            // source the operator deliberately turned off reads as a bug in the run
            // rather than as an answer to the request.
            return Response.status(Response.Status.CONFLICT)
                    .entity(Map.of("error", "This source is disabled. Enable it to run it.", "sourceId", sourceId))
                    .build();
        }

        return sourceIngestionService.runAsync(ragConfigId, resolved.knowledgeBase(), resolved.source())
                // The run id lets a caller follow this run in the history rather than
                // guessing which RUNNING row is theirs.
                .map(runId -> Response.accepted(Map.of("status", "started", "sourceId", sourceId, "runId", runId))
                        .build())
                .orElseGet(() -> Response.status(Response.Status.CONFLICT)
                        .entity(Map.of("error", "A run is already in flight for this source", "sourceId", sourceId))
                        .build());
    }

    @Override
    public Response previewSource(String ragConfigId, String sourceId, Integer version) {
        // A preview crawls the source, which is a visible amount of traffic to a
        // third party even though it writes nothing — so it is gated like a run.
        resourceAccessGuard.requireAccess(ragConfigId, AccessLevel.EDIT, "RAG configuration");

        var resolved = resolveSource(ragConfigId, sourceId, version);
        if (resolved.error() != null) {
            return resolved.error();
        }
        try {
            return Response
                    .ok(sourceIngestionService.preview(ragConfigId, resolved.knowledgeBase(), resolved.source()))
                    .build();
        } catch (PreviewBusyException e) {
            // 429 rather than 500: nothing is wrong with the source or the request,
            // there is simply no preview slot free right now.
            return Response.status(429)
                    .header("Retry-After", "30")
                    .entity(Map.of("error", e.getMessage(), "sourceId", sourceId))
                    .build();
        }
    }

    @Override
    public Response readSourceRuns(String ragConfigId, String sourceId, Integer version, Integer limit) {
        resourceAccessGuard.requireAccess(ragConfigId, AccessLevel.VIEW, "RAG configuration");

        var resolved = resolveSource(ragConfigId, sourceId, version);
        if (resolved.error() != null) {
            return resolved.error();
        }
        int effectiveLimit = limit == null || limit <= 0 ? 20 : Math.min(limit, 200);
        return Response.ok(sourceIngestionService.listRuns(ragConfigId, resolved.source(), effectiveLimit)).build();
    }

    @Override
    public Response purgeSource(String ragConfigId, String sourceId, Integer version) {
        resourceAccessGuard.requireAccess(ragConfigId, AccessLevel.EDIT, "RAG configuration");

        var resolved = resolveSource(ragConfigId, sourceId, version);
        if (resolved.error() != null) {
            return resolved.error();
        }
        // Refused while a run is in flight, and decided by the purge itself, under the
        // source's run claim: it deletes the run history, including the RUNNING row
        // that is the only thing stopping a second crawl into the same knowledge
        // base, and a worker still going would write its state rows back afterwards.
        // A check made here first let a run start between the check and the purge.
        if (!sourceIngestionService.purge(ragConfigId, resolved.source())) {
            return Response.status(Response.Status.CONFLICT)
                    .entity(Map.of("error", "A run is in flight for this source. Purge once it has finished.",
                            "sourceId", sourceId))
                    .build();
        }
        LOGGER.infof("Purged ingestion state for source %s of RAG config %s", sanitize(sourceId), sanitize(ragConfigId));
        return Response.ok(Map.of("status", "purged", "sourceId", sourceId)).build();
    }

    // --- Schedules ---

    @Override
    public Response readSourceSchedule(String ragConfigId, String sourceId, Integer version) {
        resourceAccessGuard.requireAccess(ragConfigId, AccessLevel.VIEW, "RAG configuration");

        var resolved = resolveSource(ragConfigId, sourceId, version);
        if (resolved.error() != null) {
            return resolved.error();
        }
        RagSourceIngestionService.SourceSchedule status;
        try {
            status = sourceIngestionService.scheduleStatus(ragConfigId, resolved.source());
        } catch (IResourceStore.ResourceStoreException e) {
            LOGGER.errorf(e, "Failed to read the schedule of source %s of RAG config %s", sanitize(sourceId),
                    sanitize(ragConfigId));
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity(Map.of("error", "The schedule store could not be read: " + e.getMessage())).build();
        }

        Map<String, Object> body = new LinkedHashMap<>();
        var schedule = status.schedule();
        body.put("scheduled", schedule != null);
        if (schedule != null) {
            body.put("scheduleId", schedule.getId());
            body.put("cron", schedule.getCronExpression());
            body.put("timeZone", schedule.getTimeZone());
            body.put("enabled", schedule.isEnabled());
            body.put("disabledReason", schedule.getDisabledReason());
            body.put("nextFire", isoOrNull(schedule.getNextFire()));
            body.put("lastFired", isoOrNull(schedule.getLastFired()));
            body.put("fireStatus", schedule.getFireStatus() == null ? null : schedule.getFireStatus().name());
            body.put("failCount", schedule.getFailCount());
            body.put("nextRetryAt", isoOrNull(schedule.getNextRetryAt()));
        }
        List<Map<String, Object>> fires = new ArrayList<>();
        for (var fire : status.recentFires()) {
            Map<String, Object> described = new LinkedHashMap<>();
            described.put("fireTime", isoOrNull(fire.fireTime()));
            described.put("completedAt", isoOrNull(fire.completedAt()));
            described.put("status", fire.status());
            described.put("errorMessage", fire.errorMessage());
            described.put("attemptNumber", fire.attemptNumber());
            fires.add(described);
        }
        body.put("recentFires", fires);
        return Response.ok(body).build();
    }

    @Override
    public Response previewCron(String cron, Integer count) {
        var preview = sourceIngestionService.previewCron(cron, count == null ? 3 : count);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("cron", preview.cron());
        body.put("valid", preview.error() == null);
        body.put("error", preview.error());
        body.put("timeZone", preview.zone());
        body.put("nextFires", preview.nextFires().stream().map(Object::toString).toList());
        return Response.ok(body).build();
    }

    private static String isoOrNull(Instant instant) {
        return instant == null ? null : instant.toString();
    }

    // --- Inspecting what a knowledge base holds ---

    @Override
    public Response readSourceDocuments(String ragConfigId, String sourceId, Integer version, String query,
                                        Integer offset, Integer limit) {
        // VIEW, like the run history and the file list: this is the ingestion state
        // -- ids, URLs, file names and timestamps -- not the text itself.
        resourceAccessGuard.requireAccess(ragConfigId, AccessLevel.VIEW, "RAG configuration");

        var resolved = resolveSource(ragConfigId, sourceId, version);
        if (resolved.error() != null) {
            return resolved.error();
        }
        int pageSize = limit == null ? DEFAULT_DOCUMENT_PAGE : Math.min(limit, MAX_DOCUMENT_PAGE);
        var page = inspector.listDocuments(ragConfigId, resolved.source(), query, offset == null ? 0 : offset,
                pageSize);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("total", page.total());
        body.put("truncated", page.truncated());
        body.put("documents", page.documents().stream().map(RestRagIngestion::describeDocument).toList());
        return Response.ok(body).build();
    }

    @Override
    public Response readDocumentChunks(String ragConfigId, String sourceId, Integer version, String documentId,
                                       Integer limit) {
        // EDIT: this returns the knowledge base's text, and every call costs an
        // embedding. A published configuration grants VIEW to everyone.
        resourceAccessGuard.requireAccess(ragConfigId, AccessLevel.EDIT, "RAG configuration");

        if (documentId == null || documentId.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Query parameter 'documentId' is required")).build();
        }
        var resolved = resolveSource(ragConfigId, sourceId, version);
        if (resolved.error() != null) {
            return resolved.error();
        }
        try {
            var chunks = inspector.documentChunks(ragConfigId, resolved.knowledgeBase(), resolved.source(), documentId,
                    limit == null ? 100 : limit);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("documentId", documentId);
            body.put("chunks", chunks.stream().map(RestRagIngestion::describeChunk).toList());
            return Response.ok(body).build();
        } catch (UnsupportedOperationException e) {
            return Response.status(Response.Status.NOT_IMPLEMENTED).entity(Map.of("error", e.getMessage())).build();
        } catch (RuntimeException e) {
            return inspectionFailed(ragConfigId, e);
        }
    }

    @Override
    public Response searchKnowledgeBase(String ragConfigId, Integer version, KnowledgeBaseSearch search) {
        resourceAccessGuard.requireAccess(ragConfigId, AccessLevel.EDIT, "RAG configuration");

        if (search == null || search.query() == null || search.query().isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "A non-empty 'query' is required")).build();
        }
        if (search.query().length() > MAX_SEARCH_QUERY_LENGTH) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "The query is longer than " + MAX_SEARCH_QUERY_LENGTH + " characters"))
                    .build();
        }
        var resolved = search.sourceId() == null || search.sourceId().isBlank()
                ? resolveKnowledgeBase(ragConfigId, version)
                : resolveSource(ragConfigId, search.sourceId(), version);
        if (resolved.error() != null) {
            return resolved.error();
        }
        RagConfiguration knowledgeBase = resolved.knowledgeBase();
        int maxResults = search.maxResults() != null
                ? search.maxResults()
                : knowledgeBase.getMaxResults() != null ? knowledgeBase.getMaxResults() : 5;
        double minScore = search.minScore() != null
                ? search.minScore()
                : knowledgeBase.getMinScore() != null ? knowledgeBase.getMinScore() : 0.0;
        try {
            var hits = inspector.search(ragConfigId, knowledgeBase, resolved.source(), search.query(), maxResults,
                    minScore);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("maxResults", maxResults);
            body.put("minScore", minScore);
            body.put("results", hits.stream().map(RestRagIngestion::describeChunk).toList());
            return Response.ok(body).build();
        } catch (UnsupportedOperationException e) {
            return Response.status(Response.Status.NOT_IMPLEMENTED).entity(Map.of("error", e.getMessage())).build();
        } catch (RuntimeException e) {
            return inspectionFailed(ragConfigId, e);
        }
    }

    /**
     * An embedding provider or vector store that failed. Said as a 502 with the
     * cause, because the cause -- a bad API key, an unreachable store -- is exactly
     * what the operator opened this view to find out.
     */
    private static Response inspectionFailed(String ragConfigId, RuntimeException e) {
        LOGGER.warnf(e, "Inspecting knowledge base %s failed", sanitize(ragConfigId));
        return Response.status(Response.Status.BAD_GATEWAY)
                .entity(Map.of("error", "The embedding model or vector store failed: " + e.getMessage()))
                .build();
    }

    private static Map<String, Object> describeDocument(KnowledgeBaseInspector.DocumentSummary document) {
        Map<String, Object> described = new LinkedHashMap<>();
        described.put("documentId", document.documentId());
        described.put("label", document.label());
        described.put("url", document.url());
        described.put("firstIngestedAt", document.firstIngestedAt() == null ? null : document.firstIngestedAt().toString());
        described.put("lastIngestedAt", document.lastIngestedAt() == null ? null : document.lastIngestedAt().toString());
        described.put("missedRuns", document.missedRuns());
        described.put("tombstoned", document.tombstoned());
        return described;
    }

    private static Map<String, Object> describeChunk(KnowledgeBaseInspector.Chunk chunk) {
        Map<String, Object> described = new LinkedHashMap<>();
        described.put("index", chunk.index());
        described.put("score", chunk.score());
        described.put("text", chunk.text());
        described.put("metadata", chunk.metadata());
        return described;
    }

    // --- Uploaded files ---

    @Override
    public Response uploadSourceFiles(String ragConfigId, String sourceId, Integer version, List<FileUpload> files) {
        // EDIT for the same reason a run needs it: what is uploaded here becomes
        // what every agent using this knowledge base answers from.
        resourceAccessGuard.requireAccess(ragConfigId, AccessLevel.EDIT, "RAG configuration");

        var resolved = resolveSource(ragConfigId, sourceId, version);
        if (resolved.error() != null) {
            return resolved.error();
        }
        Response wrongType = requireUploadSource(resolved.source(), sourceId);
        if (wrongType != null) {
            return wrongType;
        }
        if (files == null || files.isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "No files were sent. Attach them as multipart parts named 'files'."))
                    .build();
        }

        // Handed over as suppliers, not as bytes: the service reads one file at a
        // time, so a batch costs one file of memory rather than the whole request.
        // The runtime has already spooled every part to disk, and says how big each
        // one is — so a file over the source's limit is refused on that figure,
        // before its bytes are read into memory at all.
        List<IngestedFileService.IncomingFile> incoming = files.stream()
                .map(file -> new IngestedFileService.IncomingFile(file.fileName(), file.size(),
                        () -> Files.readAllBytes(file.uploadedFile())))
                .toList();

        var outcome = ingestedFileService.upload(ragConfigId, resolved.source(), incoming);
        List<Map<String, Object>> rejected = new ArrayList<>();
        outcome.rejected().forEach(rejection -> rejected
                .add(Map.of("fileName", rejection.fileName(), "reason", rejection.reason())));

        LOGGER.infof("Uploaded %d file(s) to source %s of knowledge base %s, %d refused",
                outcome.accepted().size(), sanitize(sourceId), sanitize(ragConfigId), rejected.size());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("stored", outcome.accepted().stream().map(RestRagIngestion::describeFile).toList());
        body.put("rejected", rejected);
        // 400 only when nothing at all was stored: a batch where one file failed is
        // a success with a caveat, and a client that treats 4xx as "nothing
        // happened" would leave the operator re-uploading files that are already
        // there.
        Response.Status status = outcome.accepted().isEmpty()
                ? Response.Status.BAD_REQUEST
                : Response.Status.OK;
        return Response.status(status).entity(body).build();
    }

    @Override
    public Response readSourceFiles(String ragConfigId, String sourceId, Integer version) {
        resourceAccessGuard.requireAccess(ragConfigId, AccessLevel.VIEW, "RAG configuration");

        var resolved = resolveSource(ragConfigId, sourceId, version);
        if (resolved.error() != null) {
            return resolved.error();
        }
        Response wrongType = requireUploadSource(resolved.source(), sourceId);
        if (wrongType != null) {
            return wrongType;
        }
        return Response.ok(ingestedFileService.listWithStatus(ragConfigId, resolved.source()).stream()
                .map(RestRagIngestion::describeFile).toList()).build();
    }

    @Override
    public Response deleteSourceFile(String ragConfigId, String sourceId, String fileId, Integer version) {
        resourceAccessGuard.requireAccess(ragConfigId, AccessLevel.EDIT, "RAG configuration");

        var resolved = resolveSource(ragConfigId, sourceId, version);
        if (resolved.error() != null) {
            return resolved.error();
        }
        Response wrongType = requireUploadSource(resolved.source(), sourceId);
        if (wrongType != null) {
            return wrongType;
        }
        if (sourceIngestionService.activeRun(ragConfigId, resolved.source()).isPresent()) {
            // A run in flight is reading these files and writing state rows for them.
            // Deleting one underneath it leaves the run recording a document whose
            // vectors were just removed.
            return Response.status(Response.Status.CONFLICT)
                    .entity(Map.of("error", "A run is in flight for this source. Delete files once it has finished.",
                            "sourceId", sourceId))
                    .build();
        }

        var outcome = ingestedFileService.delete(ragConfigId, resolved.knowledgeBase(), resolved.source(), fileId);
        return switch (outcome) {
            case NOT_FOUND -> Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "No file '" + fileId + "' on this source")).build();
            case BUSY -> Response.status(Response.Status.CONFLICT)
                    .entity(Map.of("error", "A run started for this source. Delete files once it has finished.",
                            "sourceId", sourceId))
                    .build();
            case REMOVAL_FAILED -> Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity(Map.of("error", "The vector store could not remove the text this file produced, so "
                            + "the file was kept. Try deleting it again.", "fileId", fileId))
                    .build();
            case DELETED -> Response.ok(Map.of("status", "deleted", "fileId", fileId)).build();
            case DELETED_BUT_CHUNKS_REMAIN -> Response.ok(Map.of(
                    "status", "deleted",
                    "fileId", fileId,
                    "warning", "The file is gone, but this knowledge base's vector store cannot delete by "
                            + "metadata, so the text it produced is still retrievable."))
                    .build();
        };
    }

    /**
     * Files belong to an upload source; on any other type the request is a mistake.
     */
    private static Response requireUploadSource(IngestionSource source, String sourceId) {
        if (source.isUpload()) {
            return null;
        }
        return Response.status(Response.Status.CONFLICT)
                .entity(Map.of("error", "Source '" + sourceId + "' is of type '" + source.getType()
                        + "' and does not take uploaded files.", "sourceId", sourceId))
                .build();
    }

    private static Map<String, Object> describeFile(IngestedFileService.FileStatus status) {
        IIngestedFileStore.StoredFile file = status.file();
        Map<String, Object> described = new LinkedHashMap<>();
        described.put("fileId", file.fileId());
        described.put("fileName", file.fileName());
        described.put("mimeType", file.mimeType());
        described.put("sizeBytes", file.sizeBytes());
        described.put("contentHash", file.contentHash());
        described.put("uploadedAt", file.uploadedAt().toString());
        // Whether the knowledge base actually answers from this file. Without it an
        // uploaded file, an indexed one and one that was changed after it was
        // indexed are indistinguishable, and the only way to find out is to run the
        // source and compare counters.
        described.put("indexState", status.indexState().name());
        return described;
    }

    /** As above, for a file that has just been stored and has no state yet. */
    private static Map<String, Object> describeFile(IIngestedFileStore.StoredFile file) {
        return describeFile(new IngestedFileService.FileStatus(file, IngestedFileService.IndexState.NOT_INDEXED));
    }

    /**
     * Loads the knowledge base and the named source, or the response that says why
     * it could not. Access is checked by the caller BEFORE this runs, so a refusal
     * is a 403 rather than being masked as the 404 this produces.
     */
    private ResolvedSource resolveSource(String ragConfigId, String sourceId, Integer version) {
        ResolvedSource knowledgeBase = resolveKnowledgeBase(ragConfigId, version);
        if (knowledgeBase.error() != null) {
            return knowledgeBase;
        }
        IngestionSource source = knowledgeBase.knowledgeBase().findSource(sourceId);
        if (source == null) {
            return new ResolvedSource(null, null, Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "No ingestion source '" + sourceId + "' on this knowledge base")).build());
        }
        return new ResolvedSource(knowledgeBase.knowledgeBase(), source, null);
    }

    /** As {@link #resolveSource}, for a request about the whole knowledge base. */
    private ResolvedSource resolveKnowledgeBase(String ragConfigId, Integer version) {
        if (version == null) {
            // An omitted ?version binds as null, which RestVersionInfo.read rejects
            // with an IllegalArgumentException — caught below and answered as a 404,
            // telling the caller the knowledge base does not exist when the request
            // was simply incomplete.
            return new ResolvedSource(null, null, Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Query parameter 'version' is required")).build());
        }
        RagConfiguration ragConfig;
        try {
            ragConfig = readEngineConfig(ragConfigId, version);
        } catch (IllegalArgumentException e) {
            // A version that cannot be used at all — negative, say — is a bad request.
            // Answering "not found" sends the caller looking for a knowledge base
            // that is sitting there.
            return new ResolvedSource(null, null, Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Invalid request: " + e.getMessage())).build());
        } catch (RuntimeException e) {
            // A store that is down is not a missing knowledge base. Reporting 404 for
            // an outage hides it behind a client error that nobody investigates.
            LOGGER.errorf(e, "Failed to load RAG config %s v%d", sanitize(ragConfigId), version);
            throw e;
        } catch (Exception e) {
            LOGGER.warnf("Failed to load RAG config %s v%d: %s", sanitize(ragConfigId), version, e.getMessage());
            return new ResolvedSource(null, null, Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "RAG configuration not found: " + ragConfigId + " v" + version)).build());
        }
        return new ResolvedSource(ragConfig, null, null);
    }

    private record ResolvedSource(RagConfiguration knowledgeBase, IngestionSource source, Response error) {
    }
}
