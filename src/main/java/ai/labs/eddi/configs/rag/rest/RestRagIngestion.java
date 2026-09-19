/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.rag.rest;

import ai.labs.eddi.configs.descriptors.model.AccessLevel;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import ai.labs.eddi.configs.rag.IRestRagIngestion;
import ai.labs.eddi.configs.rag.IRestRagStore;
import ai.labs.eddi.configs.rag.model.IngestionSource;
import ai.labs.eddi.configs.rag.model.RagConfiguration;
import ai.labs.eddi.modules.ingestion.RagSourceIngestionService;
import ai.labs.eddi.modules.rag.RagIngestionService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

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

    private final ResourceAccessGuard resourceAccessGuard;

    @Inject
    public RestRagIngestion(IRestRagStore restRagStore, RagIngestionService ragIngestionService,
            RagSourceIngestionService sourceIngestionService, ResourceAccessGuard resourceAccessGuard) {
        this.resourceAccessGuard = resourceAccessGuard;
        this.restRagStore = restRagStore;
        this.ragIngestionService = ragIngestionService;
        this.sourceIngestionService = sourceIngestionService;
    }

    @Override
    public Response ingestDocument(String ragConfigId, Integer version, String kbId, String documentName, String documentContent) {
        if (documentContent == null || documentContent.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("error", "Document content is required")).build();
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
            ragConfig = restRagStore.readRag(ragConfigId, version);
        } catch (Exception e) {
            LOGGER.warnf("Failed to load RAG config %s v%d: %s", sanitize(ragConfigId), version, e.getMessage());
            return Response.status(Response.Status.NOT_FOUND).entity(Map.of("error", "RAG configuration not found: " + ragConfigId + " v" + version))
                    .build();
        }

        // Use provided kbId, or fall back to the RAG config name, or the config ID
        String effectiveKbId = kbId != null && !kbId.isBlank() ? kbId : ragConfig.getName() != null ? ragConfig.getName() : ragConfigId;

        String ingestionId = ragIngestionService.ingest(effectiveKbId, documentContent, documentName, ragConfig);

        LOGGER.infof("Ingestion started: id=%s, kb=%s, doc=%s, chars=%d", ingestionId, sanitize(effectiveKbId), sanitize(documentName),
                documentContent.length());

        return Response.accepted(Map.of("ingestionId", ingestionId, "kbId", effectiveKbId, "status", "pending")).build();
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

        return sourceIngestionService.runAsync(ragConfigId, resolved.knowledgeBase(), resolved.source())
                .map(runKey -> Response.accepted(Map.of("status", "started", "sourceId", sourceId)).build())
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
        return Response.ok(sourceIngestionService.preview(ragConfigId, resolved.knowledgeBase(), resolved.source()))
                .build();
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
        sourceIngestionService.purge(ragConfigId, resolved.source());
        LOGGER.infof("Purged ingestion state for source %s of RAG config %s", sanitize(sourceId), sanitize(ragConfigId));
        return Response.ok(Map.of("status", "purged", "sourceId", sourceId)).build();
    }

    /**
     * Loads the knowledge base and the named source, or the response that says why
     * it could not. Access is checked by the caller BEFORE this runs, so a refusal
     * is a 403 rather than being masked as the 404 this produces.
     */
    private ResolvedSource resolveSource(String ragConfigId, String sourceId, Integer version) {
        RagConfiguration ragConfig;
        try {
            ragConfig = restRagStore.readRag(ragConfigId, version);
        } catch (Exception e) {
            LOGGER.warnf("Failed to load RAG config %s v%d: %s", sanitize(ragConfigId), version, e.getMessage());
            return new ResolvedSource(null, null, Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "RAG configuration not found: " + ragConfigId + " v" + version)).build());
        }

        IngestionSource source = ragConfig.findSource(sourceId);
        if (source == null) {
            return new ResolvedSource(null, null, Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "No ingestion source '" + sourceId + "' on this knowledge base")).build());
        }
        return new ResolvedSource(ragConfig, source, null);
    }

    private record ResolvedSource(RagConfiguration knowledgeBase, IngestionSource source, Response error) {
    }
}
