/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion;

import ai.labs.eddi.configs.rag.model.IngestionSource;
import ai.labs.eddi.configs.rag.model.RagConfiguration;
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
 * new one. The operation is:
 * <ol>
 * <li>claim every source's run slot, so no run starts or is mid-way while the
 * vectors go (a run in flight anywhere refuses the whole operation, and nothing
 * has been changed);</li>
 * <li>remove all of the knowledge base's vectors from its store
 * ({@link EmbeddingStoreFactory#removeKnowledgeBaseVectors});</li>
 * <li>record the configured space as the store's
 * ({@link VectorSpaceGuard#reset} );</li>
 * <li>forget each source's ingestion state, which also releases its claim, and
 * start an ordinary run of every enabled source. Those runs are the "background
 * run with status": they appear in each source's run history like any
 * other.</li>
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

    private final IngestionPipeline pipeline;
    private final RagSourceIngestionService sources;
    private final IIngestionStateStore stateStore;
    private final EmbeddingStoreFactory embeddingStoreFactory;
    private final VectorSpaceGuard vectorSpaceGuard;

    @Inject
    public KnowledgeBaseReembedService(IngestionPipeline pipeline, RagSourceIngestionService sources, IIngestionStateStore stateStore,
            EmbeddingStoreFactory embeddingStoreFactory, VectorSpaceGuard vectorSpaceGuard) {
        this.pipeline = pipeline;
        this.sources = sources;
        this.stateStore = stateStore;
        this.embeddingStoreFactory = embeddingStoreFactory;
        this.vectorSpaceGuard = vectorSpaceGuard;
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
     * @throws UnsupportedOperationException
     *             when the vector store cannot delete by metadata; nothing was
     *             changed
     * @throws RuntimeException
     *             when the store failed; the claims are released and the ingestion
     *             state is as it was
     */
    public Result reembed(String ragConfigId, RagConfiguration knowledgeBase) {
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

        try {
            embeddingStoreFactory.removeKnowledgeBaseVectors(ragConfigId, knowledgeBase);
            vectorSpaceGuard.reset(ragConfigId, knowledgeBase);
        } catch (RuntimeException e) {
            release(ragConfigId, claimed, claims);
            throw e;
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
