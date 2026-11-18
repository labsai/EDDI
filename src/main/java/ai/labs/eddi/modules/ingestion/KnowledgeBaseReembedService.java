/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion;

import ai.labs.eddi.configs.rag.model.IngestionSource;
import ai.labs.eddi.configs.rag.model.KnowledgeBaseStorage;
import ai.labs.eddi.configs.rag.model.RagConfiguration;
import ai.labs.eddi.configs.rag.rest.KnowledgeBaseStorageGuard;
import ai.labs.eddi.modules.ingestion.IIngestionStateStore.IngestionRun;
import ai.labs.eddi.modules.llm.impl.EmbeddingStoreFactory;
import ai.labs.eddi.modules.rag.VectorSpaceGuard;
import ai.labs.eddi.utils.LogSanitizer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Makes a knowledge base's vectors again, in the embedding space it is now
 * configured for.
 *
 * <p>
 * What a changed embedding model needs is not an edit but a rebuild: every
 * vector in the store was made by the old model and means nothing next to the
 * new one. The order is chosen so that a crash at any point leaves the
 * knowledge base refillable, never empty with an ingestion state that says
 * "unchanged":
 * <ol>
 * <li>refuse what cannot be done safely (another knowledge base keeps the same
 * chunks, or a shared pgvector table would have to change its column
 * size);</li>
 * <li>claim every source's run slot, so nothing runs while the vectors go (a
 * run in flight anywhere refuses the whole operation, and nothing has been
 * changed);</li>
 * <li><b>forget every source's content hashes first</b>
 * ({@link IIngestionStateStore#invalidateContent}): from here a run, scheduled
 * or manual, embeds everything again whatever happens next;</li>
 * <li>remove the knowledge base's vectors, record the configured space, and —
 * per source — forget the state and start an ordinary run. Those runs are the
 * background run with status: they appear in each source's run history.</li>
 * </ol>
 *
 * <p>
 * What it cannot rebuild is reported, not hidden. Text sent through
 * {@code POST /ingest} is embedded and then dropped — EDDI keeps no copy — so
 * it goes with the old vectors and has to be sent again. Uploaded files are
 * kept and web pages are fetched again, so those sources come back by
 * themselves.
 */
@ApplicationScoped
public class KnowledgeBaseReembedService {

    private static final Logger LOGGER = Logger.getLogger(KnowledgeBaseReembedService.class);

    /** What is listed under {@code notReEmbedded} for a direct ingest. */
    static final String DIRECT_INGEST_NOTE = "Documents sent through POST /ragstore/rags/{id}/ingest: EDDI does not keep their "
            + "text, so they were removed with the old vectors and have to be sent again.";

    /** Re-embedding was refused before anything was changed. */
    public static final String CODE_SHARED_CHUNKS = "SHARED_CHUNKS";
    public static final String CODE_SHARED_TABLE_SIZE = "SHARED_TABLE_SIZE";

    private final IngestionPipeline pipeline;
    private final RagSourceIngestionService sources;
    private final IIngestionStateStore stateStore;
    private final EmbeddingStoreFactory embeddingStoreFactory;
    private final VectorSpaceGuard vectorSpaceGuard;
    private final KnowledgeBaseStorageGuard storageGuard;

    @Inject
    public KnowledgeBaseReembedService(IngestionPipeline pipeline, RagSourceIngestionService sources, IIngestionStateStore stateStore,
            EmbeddingStoreFactory embeddingStoreFactory, VectorSpaceGuard vectorSpaceGuard, KnowledgeBaseStorageGuard storageGuard) {
        this.pipeline = pipeline;
        this.sources = sources;
        this.stateStore = stateStore;
        this.embeddingStoreFactory = embeddingStoreFactory;
        this.vectorSpaceGuard = vectorSpaceGuard;
        this.storageGuard = storageGuard;
    }

    /**
     * What happened to one source.
     *
     * @param runId
     *            the run that re-embeds it, or null when none was started
     * @param note
     *            why none was started, or null
     */
    public record SourceOutcome(String sourceId, String runId, String note) {
    }

    /**
     * @param busySourceId
     *            the source whose run was in flight, in which case nothing was
     *            changed; otherwise null
     * @param notReEmbedded
     *            what could not be made again from what EDDI keeps
     */
    public record Result(String busySourceId, List<SourceOutcome> sources, List<String> notReEmbedded) {

        public boolean refused() {
            return busySourceId != null;
        }
    }

    /**
     * Re-embedding cannot be done safely; nothing was changed.
     */
    public static class ReembedRefusedException extends RuntimeException {

        private final String code;
        private final List<String> knowledgeBases;

        public ReembedRefusedException(String code, String message, List<String> knowledgeBases) {
            super(message);
            this.code = code;
            this.knowledgeBases = List.copyOf(knowledgeBases);
        }

        public String code() {
            return code;
        }

        /** The other knowledge bases involved. */
        public List<String> knowledgeBases() {
            return knowledgeBases;
        }
    }

    /**
     * @throws ReembedRefusedException
     *             when another knowledge base keeps the same chunks, or a shared
     *             pgvector table would have to change its column size; nothing was
     *             changed
     * @throws UnsupportedOperationException
     *             when the vector store cannot delete by metadata; the ingestion
     *             state is marked for a rebuild, the vectors are untouched
     * @throws RuntimeException
     *             when the store failed; the claims are released, and the sources'
     *             content hashes were already forgotten, so the next run of each
     *             embeds everything again
     */
    public Result reembed(String ragConfigId, RagConfiguration knowledgeBase) {
        List<KnowledgeBaseStorageGuard.Sharer> sharers = storageGuard == null ? List.of() : storageGuard.sharersOf(ragConfigId, knowledgeBase);
        requireNoSharedChunks(ragConfigId, knowledgeBase, sharers);
        requireNoSharedSizeChange(ragConfigId, knowledgeBase);
        // Before anything is claimed or removed: an unresolved variable refuses the
        // whole operation.
        vectorSpaceGuard.configured(knowledgeBase);

        List<IngestionSource> all = new ArrayList<>();
        if (knowledgeBase.getSources() != null) {
            knowledgeBase.getSources().stream().filter(source -> source != null).forEach(all::add);
        }

        List<IngestionSource> claimed = new ArrayList<>();
        List<String> claims = new ArrayList<>();
        for (IngestionSource source : all) {
            Optional<String> claim = pipeline.claimForMaintenance(ragConfigId, source);
            if (claim.isEmpty()) {
                release(ragConfigId, claimed, claims);
                return new Result(source.effectiveId(), List.of(), List.of());
            }
            claimed.add(source);
            claims.add(claim.get());
        }

        // A direct /ingest on this instance waits until the vectors are made again.
        vectorSpaceGuard.beginMaintenance(ragConfigId);
        try {
            // First, and durable: from here every run — scheduled, manual, or the next
            // one after a crash — embeds everything again, whatever happens below.
            // Without it, vectors gone + record reset + state "unchanged" left the
            // knowledge base empty with nothing to say so.
            for (IngestionSource source : all) {
                stateStore.invalidateContent(IngestionPipeline.stateKey(ragConfigId, source));
            }
            boolean emptied = embeddingStoreFactory.removeKnowledgeBaseVectors(ragConfigId, knowledgeBase);
            vectorSpaceGuard.reset(ragConfigId, knowledgeBase, emptied || sharers.isEmpty());
        } catch (RuntimeException e) {
            release(ragConfigId, claimed, claims);
            throw e;
        } finally {
            vectorSpaceGuard.endMaintenance(ragConfigId);
        }
        LOGGER.infof("Re-embedding knowledge base %s: its vectors were removed and %d source(s) are being run again",
                LogSanitizer.sanitize(ragConfigId), all.size());

        List<SourceOutcome> outcomes = new ArrayList<>();
        for (IngestionSource source : all) {
            String sourceId = source.effectiveId();
            try {
                // Takes the claim's own row with it, which is what releases the claim.
                stateStore.purgeSource(IngestionPipeline.stateKey(ragConfigId, source));
            } catch (RuntimeException e) {
                LOGGER.errorf(e, "Could not forget the ingestion state of source %s of knowledge base %s; run it after "
                        + "purging it by hand", LogSanitizer.sanitize(sourceId), LogSanitizer.sanitize(ragConfigId));
                outcomes.add(new SourceOutcome(sourceId, null,
                        "Its ingestion state could not be cleared; purge the source, then run it."));
                continue;
            }
            if (!source.isEnabled()) {
                outcomes.add(new SourceOutcome(sourceId, null, "The source is disabled; enable and run it to embed it again."));
                continue;
            }
            Optional<String> runId = sources.runAsync(ragConfigId, knowledgeBase, source, IngestionRun.Trigger.MANUAL, report -> {
            });
            outcomes.add(runId.isPresent()
                    ? new SourceOutcome(sourceId, runId.get(), null)
                    : new SourceOutcome(sourceId, null, "A run started for it in the meantime and will embed everything again."));
        }
        return new Result(null, outcomes, List.of(DIRECT_INGEST_NOTE));
    }

    /**
     * In the 6.5.0 name layout two knowledge bases with one name share a location
     * <em>and</em> a chunk tag: deleting by the tag would take the other one's
     * chunks, and its ingestion state would go on saying "unchanged".
     */
    private static void requireNoSharedChunks(String ragConfigId, RagConfiguration knowledgeBase,
                                              List<KnowledgeBaseStorageGuard.Sharer> sharers) {
        String chunkKbId;
        try {
            chunkKbId = KnowledgeBaseStorage.chunkKbId(ragConfigId, knowledgeBase);
        } catch (IllegalArgumentException e) {
            return;
        }
        List<String> same = sharers.stream().filter(sharer -> chunkKbId.equals(sharer.chunkKbId())).map(KnowledgeBaseStorageGuard.Sharer::id)
                .toList();
        if (!same.isEmpty()) {
            throw new ReembedRefusedException(CODE_SHARED_CHUNKS, "Knowledge base(s) " + String.join(", ", same) + " keep their chunks "
                    + "under the same tag in the same store (6.5.0 layout: same name, same table), so removing this one's vectors would "
                    + "remove theirs, and their ingestion state would still say \"unchanged\". Nothing was changed. Give the knowledge "
                    + "bases separate tables (storeParameters.table / collectionName) and re-embed each.", same);
        }
    }

    /**
     * A pgvector column has one size. Changing it means dropping the table, which
     * is not allowed while other knowledge bases keep rows in it.
     */
    private void requireNoSharedSizeChange(String ragConfigId, RagConfiguration knowledgeBase) {
        if (!"pgvector".equals(knowledgeBase.getStoreType())) {
            return;
        }
        String chunkKbId;
        try {
            chunkKbId = KnowledgeBaseStorage.chunkKbId(ragConfigId, knowledgeBase);
        } catch (IllegalArgumentException e) {
            return;
        }
        var info = embeddingStoreFactory.inspectPgVectorTable(ragConfigId, knowledgeBase, chunkKbId);
        if (!info.exists() || !info.layoutMatches() || info.columnDimension() == null || !info.otherRows()) {
            return;
        }
        int declared = declaredDimension(knowledgeBase);
        if (declared > 0 && declared != info.columnDimension()) {
            throw new ReembedRefusedException(CODE_SHARED_TABLE_SIZE, "The table holds other knowledge bases' rows and its vectors have "
                    + info.columnDimension() + " dimensions, but this knowledge base is configured for " + declared + ". A table cannot "
                    + "change its vector size while it is shared. Nothing was changed. Give this knowledge base a table of its own "
                    + "(storeParameters.table), or set storeParameters.dimension back to " + info.columnDimension() + ".", List.of());
        }
    }

    private static int declaredDimension(RagConfiguration knowledgeBase) {
        var params = knowledgeBase.getStoreParameters();
        String raw = params == null ? null : params.get("dimension");
        if (raw == null || raw.isBlank()) {
            return VectorSpaceGuard.PGVECTOR_DEFAULT_DIMENSION;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private void release(String ragConfigId, List<IngestionSource> claimed, List<String> claims) {
        for (int i = 0; i < claimed.size(); i++) {
            try {
                pipeline.releaseClaim(ragConfigId, claimed.get(i), claims.get(i));
            } catch (RuntimeException e) {
                LOGGER.warnf(e, "Could not release the claim on source %s of knowledge base %s; it is reaped after its time budget",
                        LogSanitizer.sanitize(claimed.get(i).effectiveId()), LogSanitizer.sanitize(ragConfigId));
            }
        }
    }
}
