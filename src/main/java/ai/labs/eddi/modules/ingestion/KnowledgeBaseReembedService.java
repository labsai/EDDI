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
import ai.labs.eddi.modules.llm.impl.EmbeddingModelFactory;
import ai.labs.eddi.modules.llm.impl.EmbeddingStoreFactory;
import ai.labs.eddi.modules.rag.RagFailures;
import ai.labs.eddi.modules.rag.VectorSpaceGuard;
import dev.langchain4j.model.embedding.request.EmbeddingInputType;
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
    public static final String CODE_EMBEDDING_FAILED = "EMBEDDING_PROBE_FAILED";
    public static final String CODE_NOT_A_PGVECTOR_TABLE = "NOT_A_PGVECTOR_TABLE";
    public static final String CODE_DIMENSION_SETTING = "DIMENSION_SETTING_MISMATCH";
    public static final String CODE_STORE_CANNOT_RESIZE = "STORE_CANNOT_CHANGE_SIZE";

    private final IngestionPipeline pipeline;
    private final RagSourceIngestionService sources;
    private final IIngestionStateStore stateStore;
    private final EmbeddingStoreFactory embeddingStoreFactory;
    private final VectorSpaceGuard vectorSpaceGuard;
    private final KnowledgeBaseStorageGuard storageGuard;
    private final EmbeddingModelFactory embeddingModelFactory;

    @Inject
    public KnowledgeBaseReembedService(IngestionPipeline pipeline, RagSourceIngestionService sources, IIngestionStateStore stateStore,
            EmbeddingStoreFactory embeddingStoreFactory, VectorSpaceGuard vectorSpaceGuard, KnowledgeBaseStorageGuard storageGuard,
            EmbeddingModelFactory embeddingModelFactory) {
        this.embeddingModelFactory = embeddingModelFactory;
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
     * @param tableRecreated
     *            pgvector only: whether the table was dropped to be recreated at
     *            the configured vector size (false when it was busy or still holds
     *            other knowledge bases' rows, and so keeps its old size); null for
     *            any other store
     * @param warnings
     *            what the operator still has to do, in words
     */
    public record Result(String busySourceId, List<SourceOutcome> sources, List<String> notReEmbedded, Boolean tableRecreated,
            List<String> warnings) {

        public Result(String busySourceId, List<SourceOutcome> sources, List<String> notReEmbedded) {
            this(busySourceId, sources, notReEmbedded, null, List.of());
        }

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
        // Before anything is claimed or removed: an unresolved variable refuses the
        // whole operation.
        vectorSpaceGuard.configured(knowledgeBase);
        List<KnowledgeBaseStorageGuard.Sharer> sharers = storageGuard == null
                ? List.of()
                : storageGuard.sharersOf(ragConfigId, knowledgeBase, vectorSpaceGuard::locationOf);
        requireNoSharedChunks(ragConfigId, knowledgeBase, sharers);
        // One embedding call with the new settings, before anything is removed: it
        // proves the credentials and the model
        // work, and it measures the size the new vectors have — the number every check
        // below needs.
        int newSize = probeVectorSize(knowledgeBase);
        Integer columnBefore = requireSizeFeasible(ragConfigId, knowledgeBase, sharers, newSize);

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

        // A direct /ingest on this instance is refused until the vectors are made
        // again.
        EmbeddingStoreFactory.RemovalOutcome removal = null;
        vectorSpaceGuard.beginMaintenance(ragConfigId);
        try {
            // First, and durable: from here every run — scheduled, manual, or the next
            // one after a crash — embeds everything again, whatever happens below.
            // Without it, vectors gone + record reset + state "unchanged" left the
            // knowledge base empty with nothing to say so.
            for (IngestionSource source : all) {
                stateStore.invalidateContent(IngestionPipeline.stateKey(ragConfigId, source));
            }
            removal = embeddingStoreFactory.removeKnowledgeBaseVectors(ragConfigId, knowledgeBase);
            boolean emptied = removal == EmbeddingStoreFactory.RemovalOutcome.STORE_DROPPED
                    || removal == EmbeddingStoreFactory.RemovalOutcome.TABLE_DROPPED || removal == EmbeddingStoreFactory.RemovalOutcome.NO_TABLE;
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
        Boolean tableRecreated = null;
        List<String> warnings = new ArrayList<>();
        if ("pgvector".equals(knowledgeBase.getStoreType()) && removal != null) {
            tableRecreated = removal == EmbeddingStoreFactory.RemovalOutcome.TABLE_DROPPED
                    || removal == EmbeddingStoreFactory.RemovalOutcome.NO_TABLE;
            if (!tableRecreated && columnBefore != null && columnBefore != newSize) {
                warnings.add("The pgvector table was " + (removal == EmbeddingStoreFactory.RemovalOutcome.TABLE_BUSY
                        ? "in use and could not be dropped"
                        : "not dropped because other knowledge bases keep rows in it")
                        + ", so it keeps its old vector size of " + columnBefore + " and the new vectors (" + newSize + " dimensions) "
                        + "will be refused. Once the table is idle and empty, drop it (DROP TABLE) or re-embed again; the next write "
                        + "recreates it.");
            }
        }
        return new Result(null, outcomes, List.of(DIRECT_INGEST_NOTE), tableRecreated, warnings);
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
     * One embedding call with the knowledge base's settings: the vector size, and a
     * proof that the credentials and the model work. A re-embed that cannot embed
     * must not delete anything.
     */
    private int probeVectorSize(RagConfiguration knowledgeBase) {
        try {
            int size = embeddingModelFactory.getOrCreate(knowledgeBase, EmbeddingInputType.DOCUMENT).embed("vector size probe").content()
                    .dimension();
            if (size <= 0) {
                throw new IllegalStateException("the embedding model returned an empty vector");
            }
            return size;
        } catch (RuntimeException e) {
            LOGGER.warnf(e, "The embedding probe of a re-embed failed");
            throw new ReembedRefusedException(CODE_EMBEDDING_FAILED, "The embedding model could not be called with this knowledge "
                    + "base's settings, so nothing was removed. " + RagFailures.clientMessage(e), List.of());
        }
    }

    /**
     * Decides, with the real size of the new vectors, whether the store can take
     * them, before anything is removed.
     *
     * <ul>
     * <li><b>pgvector</b>: a table that is not an EDDI table is refused; the
     * {@code storeParameters.dimension} must be the new size (the table is
     * recreated from it); a table whose column has another size is dropped and
     * recreated, which is refused while other knowledge bases keep rows in it.</li>
     * <li><b>Qdrant, Chroma, Elasticsearch, Atlas</b>: a collection keeps its
     * dimension and EDDI cannot recreate it. When a size is recorded for the
     * location and differs: refused while other knowledge bases keep vectors there,
     * and refused with what the operator must do when this one is alone. A location
     * with no recorded size (never ingested since sizes are tracked) cannot be
     * judged here; the first ingestion is then refused with the same advice.</li>
     * <li><b>in-memory</b>: nothing to decide, the store is replaced.</li>
     * </ul>
     *
     * @return the pgvector column size before the re-embed, or null
     */
    private Integer requireSizeFeasible(String ragConfigId, RagConfiguration knowledgeBase, List<KnowledgeBaseStorageGuard.Sharer> sharers,
                                        int newSize) {
        String type = knowledgeBase.getStoreType();
        if ("pgvector".equals(type)) {
            String chunkKbId = KnowledgeBaseStorage.chunkKbId(ragConfigId, knowledgeBase);
            var info = embeddingStoreFactory.inspectPgVectorTable(ragConfigId, knowledgeBase, chunkKbId);
            if (info.exists() && !info.layoutMatches()) {
                throw new ReembedRefusedException(CODE_NOT_A_PGVECTOR_TABLE, "The table named in storeParameters.table is not a pgvector "
                        + "table EDDI created, so EDDI will not delete from or drop it. Nothing was changed. Name a table of this "
                        + "knowledge base's own.", List.of());
            }
            int declared = declaredDimension(knowledgeBase);
            if (declared != newSize) {
                throw new ReembedRefusedException(CODE_DIMENSION_SETTING, "The embedding model returns vectors of " + newSize
                        + " dimensions, but storeParameters.dimension is " + (declared > 0 ? declared : "not a number")
                        + " (default " + VectorSpaceGuard.PGVECTOR_DEFAULT_DIMENSION + "), and the table is recreated from it. Nothing "
                        + "was changed. Set storeParameters.dimension to " + newSize + ", then re-embed.", List.of());
            }
            if (info.exists() && info.columnDimension() != null && info.columnDimension() != newSize && info.otherRows()) {
                throw new ReembedRefusedException(CODE_SHARED_TABLE_SIZE, "The table holds other knowledge bases' rows and its vectors "
                        + "have " + info.columnDimension() + " dimensions, but the new vectors have " + newSize + ". A table cannot change "
                        + "its vector size while it is shared. Nothing was changed. Give this knowledge base a table of its own "
                        + "(storeParameters.table), or use a model of " + info.columnDimension() + " dimensions.", List.of());
            }
            return info.exists() ? info.columnDimension() : null;
        }
        if (type == null || "in-memory".equals(type)) {
            return null;
        }
        Integer recorded = vectorSpaceGuard.recordedSize(ragConfigId, knowledgeBase);
        if (recorded == null || recorded == newSize) {
            return null;
        }
        String location = KnowledgeBaseStorage.physicalNameParameter(type);
        if (!sharers.isEmpty()) {
            throw new ReembedRefusedException(CODE_SHARED_TABLE_SIZE, "Other knowledge bases keep vectors of " + recorded + " dimensions in "
                    + "this store, and the new vectors have " + newSize + ". A store keeps one vector size. Nothing was changed. Give "
                    + "this knowledge base a " + location + " of its own, or use a model of " + recorded + " dimensions.",
                    sharers.stream().map(KnowledgeBaseStorageGuard.Sharer::id).toList());
        }
        throw new ReembedRefusedException(CODE_STORE_CANNOT_RESIZE, "This store's " + location + " holds vectors of " + recorded
                + " dimensions and keeps that size: EDDI can delete chunks from it but cannot recreate it. The new vectors have " + newSize
                + ". Nothing was changed. Delete or recreate the " + location + " in the vector store with the new size, or point "
                + "storeParameters." + location + " at a new one, then re-embed.", List.of());
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
