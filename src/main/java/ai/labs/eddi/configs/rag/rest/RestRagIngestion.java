/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.rag.rest;

import ai.labs.eddi.configs.descriptors.model.AccessLevel;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import ai.labs.eddi.engine.security.spaces.SpaceReferenceGuard;
import ai.labs.eddi.configs.rag.IRestRagIngestion;
import ai.labs.eddi.configs.rag.IRestRagStore;
import ai.labs.eddi.configs.rag.model.IngestionSource;
import ai.labs.eddi.configs.rag.model.RagConfiguration;
import ai.labs.eddi.modules.ingestion.IngestionPipeline;
import ai.labs.eddi.modules.ingestion.KnowledgeBaseInspector;
import ai.labs.eddi.modules.ingestion.PreviewBusyException;
import ai.labs.eddi.modules.ingestion.RagSourceIngestionService;
import ai.labs.eddi.modules.ingestion.files.IIngestedFileStore;
import ai.labs.eddi.modules.ingestion.files.IngestedFileService;
import ai.labs.eddi.modules.rag.RagIngestionService;
import ai.labs.eddi.modules.rag.evaluation.RetrievalMetrics;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.multipart.FileUpload;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
    private final SpaceReferenceGuard spaceReferenceGuard;

    @Inject
    public RestRagIngestion(IRestRagStore restRagStore, RagIngestionService ragIngestionService,
            RagSourceIngestionService sourceIngestionService, IngestedFileService ingestedFileService,
            KnowledgeBaseInspector inspector, ResourceAccessGuard resourceAccessGuard, SpaceReferenceGuard spaceReferenceGuard) {
        this.inspector = inspector;
        this.resourceAccessGuard = resourceAccessGuard;
        this.spaceReferenceGuard = spaceReferenceGuard;
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
     * The deployment rule for a knowledge base run here, outside any agent: the
     * model and store are built from {@code config} on the spot, resolving every
     * {@code ${vars:<tenant>/…}} and {@code ${vault:<tenant>/…}} it names with the
     * server's authority, and no deployment ever checked those. The caller must
     * belong to each space tenant named (see {@link SpaceReferenceGuard}).
     */
    private void requireMayUseReferences(RagConfiguration config) {
        spaceReferenceGuard.requireMayUse(config, "knowledge base");
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
        requireMayUseReferences(ragConfig);

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
    public Response deleteDocument(String ragConfigId, Integer version, String documentName) {
        if (documentName == null || documentName.isBlank() || UNNAMED_DOCUMENT.equals(documentName)) {
            // Deletion is keyed on the name, like replace=true: every document ingested
            // without one shares "unnamed", and deleting that would delete all of them.
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "documentName is required: the document is deleted by the name it was ingested under"))
                    .build();
        }

        // EDIT, as for ingesting: removing text changes what every agent using this
        // knowledge base answers from. Checked first so a refusal is a 403.
        resourceAccessGuard.requireAccess(ragConfigId, AccessLevel.EDIT, "RAG configuration");

        var resolved = resolveKnowledgeBase(ragConfigId, version);
        if (resolved.error() != null) {
            return resolved.error();
        }
        RagConfiguration knowledgeBase = resolved.knowledgeBase();

        IngestionSource owner;
        try {
            owner = sourceIngestionService.sourceHoldingDocument(ragConfigId, knowledgeBase, documentName).orElse(null);
        } catch (RuntimeException e) {
            LOGGER.errorf(e, "Could not check whether a source of KB %s holds document %s", sanitize(ragConfigId),
                    sanitize(documentName));
            return storeUnavailable(documentName, "Could not check this knowledge base's ingestion sources");
        }
        if (owner != null) {
            String sourceId = owner.effectiveId();
            return Response.status(Response.Status.CONFLICT)
                    .entity(Map.of("error", "Ingestion source '" + sourceId + "' holds a document named '" + documentName
                            + "'. Its text is removed through the source: delete the file from an upload source "
                            + "(DELETE /ragstore/rags/{id}/sources/{sourceId}/files/{fileId}), or exclude the page "
                            + "from a crawling source and run it.",
                            "documentName", documentName, "sourceId", String.valueOf(sourceId)))
                    .build();
        }

        RagIngestionService.DeleteOutcome outcome;
        try {
            outcome = ragIngestionService.delete(ragConfigId, documentName, knowledgeBase);
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("error", e.getMessage())).build();
        } catch (RuntimeException e) {
            // pgvector wraps its SQLException in a RuntimeException; a store that is
            // down is the client's cue to retry, not a client error.
            LOGGER.errorf(e, "Deleting document %s from KB %s failed", sanitize(documentName), sanitize(ragConfigId));
            return storeUnavailable(documentName, "The vector store could not remove this document");
        }

        return switch (outcome) {
            case DELETED -> Response.ok(Map.of("status", "deleted", "kbId", ragConfigId, "documentName", documentName)).build();
            case UNSUPPORTED -> Response.ok(Map.of(
                    "status", "not_deleted",
                    "kbId", ragConfigId,
                    "documentName", documentName,
                    "warning", "This knowledge base's vector store cannot delete by metadata, so the text of '"
                            + documentName + "' is still retrievable."))
                    .build();
            case BUSY -> Response.status(Response.Status.CONFLICT)
                    .entity(Map.of("error", "A replacement of '" + documentName + "' is still in flight. Retry once it "
                            + "has finished.", "documentName", documentName))
                    .build();
        };
    }

    private static Response storeUnavailable(String documentName, String reason) {
        return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                .entity(Map.of("error", reason + ". Nothing may have been removed; retry.", "documentName", documentName))
                .build();
    }

    @Override
    public Response getIngestionStatus(String ragConfigId, String ingestionId) {
        // The status of an ingestion is only meaningful to whoever could have started
        // it, and the caller names the RAG config in the path — so check it.
        // The service answers only for an ingestion of *this* RAG config: an ingestion
        // id is global, so an id of another knowledge base is unknown here.
        resourceAccessGuard.requireAccess(ragConfigId, AccessLevel.VIEW, "RAG configuration");
        String status = ragIngestionService.getStatus(ragConfigId, ingestionId);
        if (RagIngestionService.STATUS_UNKNOWN.equals(status)) {
            // Never started for this knowledge base, or its status has already expired. A
            // 200 with
            // status "unknown" read as "still in flight" to every polling client.
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("ingestionId", ingestionId, "status", status, "error",
                            "No ingestion with this id is known (statuses are kept for one hour)"))
                    .build();
        }
        String warning = ragIngestionService.getWarning(ragConfigId, ingestionId);
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

    // --- Benchmarking retrieval ---

    /**
     * The metadata keys whose values identify a document when a request names none:
     * {@code source} is the {@code documentName} a direct ingest stores,
     * {@code documentId} and {@code url} what an ingestion source stores.
     */
    static final List<String> DEFAULT_MATCH_KEYS = List.of("source", IngestionPipeline.METADATA_DOCUMENT_ID,
            IngestionPipeline.METADATA_URL);

    /** More than this is not a golden query but a dump of the knowledge base. */
    static final int MAX_EXPECTED_PER_QUERY = 100;

    static final int MAX_MATCH_KEYS = 10;

    static final String MAX_EVALUATION_QUERIES_PROPERTY = "eddi.rag.evaluation.max-queries";

    /**
     * Most golden queries one evaluation may run. Each costs an embedding call and
     * the request is synchronous, so this bounds both the bill and the time a
     * request holds a worker.
     */
    @ConfigProperty(name = MAX_EVALUATION_QUERIES_PROPERTY, defaultValue = "100")
    int maxEvaluationQueries = 100;

    @Override
    public Response evaluateRetrieval(String ragConfigId, Integer version, RetrievalEvaluation evaluation) {
        // EDIT, as for search: every query costs an embedding call, and the response
        // shows what the knowledge base holds.
        resourceAccessGuard.requireAccess(ragConfigId, AccessLevel.EDIT, "RAG configuration");

        String invalid = validateEvaluation(evaluation);
        if (invalid != null) {
            return badRequest(invalid);
        }
        var resolved = evaluation.sourceId() == null || evaluation.sourceId().isBlank()
                ? resolveKnowledgeBase(ragConfigId, version)
                : resolveSource(ragConfigId, evaluation.sourceId(), version);
        if (resolved.error() != null) {
            return resolved.error();
        }
        RagConfiguration knowledgeBase = resolved.knowledgeBase();
        int defaultK = evaluation.k() != null
                ? evaluation.k()
                : knowledgeBase.getMaxResults() != null ? knowledgeBase.getMaxResults() : 5;
        // A knowledge base's own maxResults may exceed what a search returns; the
        // benchmark then scores at the most it can see rather than claiming a k it
        // never looked at.
        defaultK = Math.max(1, Math.min(KnowledgeBaseInspector.MAX_SEARCH_RESULTS, defaultK));
        double minScore = evaluation.minScore() != null
                ? evaluation.minScore()
                : knowledgeBase.getMinScore() != null ? knowledgeBase.getMinScore() : 0.0;
        List<String> matchKeys = evaluation.matchKeys() == null || evaluation.matchKeys().isEmpty()
                ? DEFAULT_MATCH_KEYS
                : evaluation.matchKeys().stream().map(String::trim).distinct().toList();

        List<RetrievalMetrics.QueryScore> scores = new ArrayList<>();
        List<RetrievalMetrics.Observation> observations = new ArrayList<>();
        List<Map<String, Object>> described = new ArrayList<>();
        List<GoldenQuery> queries = evaluation.queries();
        for (int i = 0; i < queries.size(); i++) {
            GoldenQuery golden = queries.get(i);
            int k = golden.k() != null ? golden.k() : defaultK;
            List<KnowledgeBaseInspector.Chunk> hits;
            try {
                hits = inspector.search(ragConfigId, knowledgeBase, resolved.source(), golden.query(), k, minScore);
            } catch (UnsupportedOperationException e) {
                return Response.status(Response.Status.NOT_IMPLEMENTED).entity(Map.of("error", e.getMessage())).build();
            } catch (RuntimeException e) {
                LOGGER.warnf(e, "Evaluating knowledge base %s failed at query %d", sanitize(ragConfigId), i);
                return Response.status(Response.Status.BAD_GATEWAY)
                        .entity(Map.of("error", "The embedding model or vector store failed at query " + i + ": "
                                + e.getMessage(), "queryIndex", i))
                        .build();
            }
            List<List<String>> ranked = hits.stream().map(chunk -> identifiers(chunk, matchKeys)).toList();
            RetrievalMetrics.QueryScore score = RetrievalMetrics.score(ranked, golden.expected(), k);
            scores.add(score);
            observations.add(RetrievalMetrics.Observation.of(queryKey(golden.id(), golden.query()), score));
            described.add(describeScore(golden, score, hits, ranked));
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("k", defaultK);
        body.put("minScore", minScore);
        body.put("matchKeys", matchKeys);
        body.put("summary", RetrievalMetrics.aggregate(scores));
        body.put("queries", described);
        if (evaluation.baseline() != null) {
            // validateEvaluation has already refused unkeyed and duplicate baseline
            // queries, so the comparison cannot fail once the calls have been paid for.
            List<RetrievalMetrics.Observation> baseline = new ArrayList<>();
            for (BaselineQuery query : baselineQueries(evaluation)) {
                baseline.add(new RetrievalMetrics.Observation(queryKey(query.id(), query.query()), query.k(),
                        query.hit(), query.reciprocalRank(), query.recall()));
            }
            body.put("comparison", RetrievalMetrics.compare(observations, baseline));
        }
        return Response.ok(body).build();
    }

    private static List<BaselineQuery> baselineQueries(RetrievalEvaluation evaluation) {
        return evaluation.baseline() == null || evaluation.baseline().queries() == null
                ? List.of()
                : evaluation.baseline().queries();
    }

    /**
     * Why the request cannot be run, or null. Everything is checked before the
     * first embedding call, so a typo in the 90th query costs nothing.
     */
    private String validateEvaluation(RetrievalEvaluation evaluation) {
        if (evaluation == null || evaluation.queries() == null || evaluation.queries().isEmpty()) {
            return "At least one golden query is required in 'queries'";
        }
        if (evaluation.queries().size() > maxEvaluationQueries) {
            return "At most " + maxEvaluationQueries + " queries can be evaluated in one request (got "
                    + evaluation.queries().size() + "); split the set, or raise " + MAX_EVALUATION_QUERIES_PROPERTY;
        }
        String badK = checkK(evaluation.k(), "'k'");
        if (badK != null) {
            return badK;
        }
        if (evaluation.minScore() != null && (evaluation.minScore().isNaN() || evaluation.minScore() < 0.0
                || evaluation.minScore() > 1.0)) {
            return "'minScore' must be between 0 and 1";
        }
        if (evaluation.matchKeys() != null) {
            if (evaluation.matchKeys().size() > MAX_MATCH_KEYS) {
                return "At most " + MAX_MATCH_KEYS + " 'matchKeys' are allowed";
            }
            for (String key : evaluation.matchKeys()) {
                if (blank(key)) {
                    return "'matchKeys' must not contain an empty key";
                }
            }
        }
        Set<String> keys = new HashSet<>();
        List<GoldenQuery> queries = evaluation.queries();
        for (int i = 0; i < queries.size(); i++) {
            GoldenQuery query = queries.get(i);
            String at = "Query " + i;
            if (query == null || blank(query.query())) {
                return at + " has no 'query' text";
            }
            if (query.query().length() > MAX_SEARCH_QUERY_LENGTH) {
                return at + " is longer than " + MAX_SEARCH_QUERY_LENGTH + " characters";
            }
            if (query.expected() == null || query.expected().stream().allMatch(RestRagIngestion::blank)) {
                // A query with nothing expected cannot be scored, so it would only cost a call.
                return at + " names no 'expected' documents; a golden query must say what a good retrieval returns";
            }
            if (query.expected().size() > MAX_EXPECTED_PER_QUERY) {
                return at + " expects more than " + MAX_EXPECTED_PER_QUERY + " documents";
            }
            String queryK = checkK(query.k(), at + "'s 'k'");
            if (queryK != null) {
                return queryK;
            }
            if (!keys.add(queryKey(query.id(), query.query()))) {
                return at + " repeats the key '" + queryKey(query.id(), query.query())
                        + "'; give each query a unique 'id'";
            }
        }
        Set<String> baselineKeys = new HashSet<>();
        List<BaselineQuery> baseline = baselineQueries(evaluation);
        for (int i = 0; i < baseline.size(); i++) {
            BaselineQuery query = baseline.get(i);
            if (query == null || (blank(query.id()) && blank(query.query()))) {
                return "Query " + i + " of the baseline needs an 'id' or a 'query' to be paired by";
            }
            String key = queryKey(query.id(), query.query());
            if (!baselineKeys.add(key)) {
                return "The baseline holds the query '" + key + "' twice; give each query a unique id";
            }
        }
        return null;
    }

    private static String checkK(Integer k, String what) {
        if (k != null && (k < 1 || k > KnowledgeBaseInspector.MAX_SEARCH_RESULTS)) {
            return what + " must be between 1 and " + KnowledgeBaseInspector.MAX_SEARCH_RESULTS;
        }
        return null;
    }

    /**
     * A query is paired with the baseline by its id, or by its text without one.
     */
    private static String queryKey(String id, String query) {
        return blank(id) ? query.trim() : id.trim();
    }

    /** The values a chunk carries under the match keys, as text. */
    private static List<String> identifiers(KnowledgeBaseInspector.Chunk chunk, List<String> matchKeys) {
        List<String> values = new ArrayList<>();
        Map<String, Object> metadata = chunk.metadata() == null ? Map.of() : chunk.metadata();
        for (String key : matchKeys) {
            Object value = metadata.get(key);
            if (value != null) {
                values.add(value.toString());
            }
        }
        return values;
    }

    private static Map<String, Object> describeScore(GoldenQuery golden, RetrievalMetrics.QueryScore score,
                                                     List<KnowledgeBaseInspector.Chunk> hits,
                                                     List<List<String>> ranked) {
        Map<String, Object> described = new LinkedHashMap<>();
        described.put("id", golden.id());
        described.put("query", golden.query());
        described.put("k", score.k());
        described.put("expected", golden.expected());
        described.put("resultCount", score.resultCount());
        described.put("firstHitRank", score.firstHitRank());
        described.put("hit", score.hit());
        described.put("reciprocalRank", score.reciprocalRank());
        described.put("recall", score.recall());
        described.put("found", score.found());
        described.put("missing", score.missing());
        // What came back instead, so a miss can be read without a second search.
        // Identifiers and scores only — the text is what /search is for.
        Set<String> expected = new HashSet<>(score.found());
        expected.addAll(score.missing());
        List<Map<String, Object>> retrieved = new ArrayList<>();
        for (int i = 0; i < Math.min(score.k(), hits.size()); i++) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("rank", i + 1);
            result.put("score", hits.get(i).score());
            result.put("ids", ranked.get(i));
            result.put("relevant", ranked.get(i).stream().anyMatch(expected::contains));
            retrieved.add(result);
        }
        described.put("retrieved", retrieved);
        return described;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static Response badRequest(String message) {
        return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("error", message)).build();
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
        requireMayUseReferences(ragConfig);
        return new ResolvedSource(ragConfig, null, null);
    }

    private record ResolvedSource(RagConfiguration knowledgeBase, IngestionSource source, Response error) {
    }
}
