/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * An in-memory {@link IIngestionStateStore} for tests that exercise something
 * else — the pipeline, mostly — without paying for a container.
 *
 * <p>
 * Held honest by {@code InMemoryIngestionStateStoreTest}, which runs the same
 * {@link IngestionStateStoreContract} as the MongoDB and PostgreSQL backends. A
 * test double that quietly behaves differently from production turns a green
 * suite into a lie, which is exactly the trap the two real stores fell into
 * before the shared contract existed.
 */
public class InMemoryIngestionStateStore implements IIngestionStateStore {

    private final Map<String, DocumentState> documents = new LinkedHashMap<>();
    private final Map<String, IngestionRun> runs = new LinkedHashMap<>();

    /**
     * The fence, kept beside the documents because {@link DocumentState} does not
     * expose it: production keeps it in fields of its own and no caller reads it
     * back. A document with no entry here is unowned.
     */
    private final Map<String, String> fencingRunIds = new LinkedHashMap<>();
    private final Map<String, Long> fencingGenerations = new LinkedHashMap<>();

    /** Each run's generation, which production keeps on the run row. */
    private final Map<String, Long> runGenerations = new LinkedHashMap<>();

    /** Neither a source key nor a document URL can contain a newline. */
    private static final String KEY_SEPARATOR = "\n";

    private static String key(String sourceId, String documentId) {
        return sourceId + KEY_SEPARATOR + documentId;
    }

    /** Whether {@code runId} currently holds this document's fence. */
    private boolean owns(String documentKey, String runId) {
        return runId != null && runId.equals(fencingRunIds.get(documentKey));
    }

    /**
     * The generation a new run gets: one past the highest this source has issued.
     * Production reads it off the newest run row.
     */
    private long nextGeneration(String sourceId) {
        long highest = runs.values().stream()
                .filter(run -> run.sourceId().equals(sourceId))
                .map(run -> runGenerations.getOrDefault(run.runId(), 0L))
                .max(Comparator.naturalOrder())
                .orElse(0L);
        return highest + 1;
    }

    /**
     * Claims every document of the source that no newer run already holds, exactly
     * as both backends do when a run starts.
     */
    private void takeOwnership(String sourceId, String runId, long generation) {
        for (DocumentState state : documents.values()) {
            if (!state.sourceId().equals(sourceId)) {
                continue;
            }
            String documentKey = key(state.sourceId(), state.documentId());
            Long held = fencingGenerations.get(documentKey);
            if (held == null || held < generation) {
                fencingRunIds.put(documentKey, runId);
                fencingGenerations.put(documentKey, generation);
            }
        }
    }

    /**
     * Sets a document's fence directly, for the one contract property that cannot
     * be reached through the interface. Mirrors the backends' test hooks.
     */
    public synchronized void forceDocumentOwner(String sourceId, String documentId, String runId) {
        fencingRunIds.put(key(sourceId, documentId), runId);
    }

    @Override
    public synchronized Optional<DocumentState> lookup(String sourceId, String documentId) {
        if (sourceId == null || documentId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(documents.get(key(sourceId, documentId)));
    }

    @Override
    public synchronized void recordIngested(String sourceId, String documentId, String contentHash,
                                            String etag, String lastModified, String runId) {

        Instant now = Instant.now();
        String documentKey = key(sourceId, documentId);
        DocumentState existing = documents.get(documentKey);
        if (existing != null && !owns(documentKey, runId)) {
            // Fenced: a run that has been superseded writes nothing.
            return;
        }
        if (existing == null) {
            // A document this source has never held. Production upserts it through a
            // filter that carries the fence, so the new row lands owned by this run --
            // and, deliberately mirrored here, with no generation stamped on it.
            fencingRunIds.put(documentKey, runId);
        }
        Instant firstIngestedAt = existing == null || existing.firstIngestedAt() == null
                ? now
                : existing.firstIngestedAt();

        documents.put(documentKey, new DocumentState(sourceId, documentId, contentHash,
                etag, lastModified, firstIngestedAt, now, runId, 0, false));
    }

    @Override
    public synchronized void recordSeen(String sourceId, String documentId, String runId) {
        DocumentState existing = documents.get(key(sourceId, documentId));
        if (existing == null || !owns(key(sourceId, documentId), runId)) {
            return;
        }
        documents.put(key(sourceId, documentId), new DocumentState(sourceId, documentId, existing.contentHash(),
                existing.etag(), existing.lastModified(), existing.firstIngestedAt(), existing.lastIngestedAt(),
                runId, 0, false));
    }

    @Override
    public synchronized void recordSeen(String sourceId, String documentId, String runId, String etag,
                                        String lastModified) {
        DocumentState existing = documents.get(key(sourceId, documentId));
        if (existing == null || !owns(key(sourceId, documentId), runId)) {
            return;
        }
        documents.put(key(sourceId, documentId), new DocumentState(sourceId, documentId, existing.contentHash(),
                etag, lastModified, existing.firstIngestedAt(), existing.lastIngestedAt(), runId, 0, false));
    }

    @Override
    public synchronized void recordUnreachable(String sourceId, String documentId, String runId) {
        DocumentState existing = documents.get(key(sourceId, documentId));
        if (existing == null || !owns(key(sourceId, documentId), runId)) {
            return;
        }
        // Not recordSeen: the miss counter and the tombstone flag stay as they are.
        documents.put(key(sourceId, documentId), new DocumentState(sourceId, documentId, existing.contentHash(),
                existing.etag(), existing.lastModified(), existing.firstIngestedAt(), existing.lastIngestedAt(),
                runId, existing.missedRuns(), existing.tombstoned()));
    }

    @Override
    public synchronized List<DocumentState> bumpAndFindMissing(String sourceId, String runId,
                                                               int missedRunsThreshold) {
        int threshold = Math.max(1, missedRunsThreshold);
        List<DocumentState> missing = new ArrayList<>();

        for (Map.Entry<String, DocumentState> entry : documents.entrySet()) {
            DocumentState state = entry.getValue();
            if (!state.sourceId().equals(sourceId) || state.tombstoned()) {
                continue;
            }
            // Fenced like both backends: a superseded run raises nobody's miss
            // counter and reports nobody as gone, so it deletes no vectors.
            if (!owns(entry.getKey(), runId)) {
                continue;
            }
            int missed = state.missedRuns();
            if (!runId.equals(state.lastRunId())) {
                missed++;
            }
            DocumentState updated = new DocumentState(state.sourceId(), state.documentId(), state.contentHash(),
                    state.etag(), state.lastModified(), state.firstIngestedAt(), state.lastIngestedAt(),
                    state.lastRunId(), missed, false);
            entry.setValue(updated);
            if (missed >= threshold) {
                missing.add(updated);
            }
        }
        return missing;
    }

    @Override
    public synchronized void markTombstoned(String sourceId, List<String> documentIds) {
        for (String documentId : documentIds) {
            DocumentState state = documents.get(key(sourceId, documentId));
            if (state == null) {
                continue;
            }
            documents.put(key(sourceId, documentId), new DocumentState(state.sourceId(), state.documentId(),
                    state.contentHash(), state.etag(), state.lastModified(), state.firstIngestedAt(),
                    state.lastIngestedAt(), state.lastRunId(), state.missedRuns(), true));
        }
    }

    @Override
    public synchronized List<DocumentState> listDocuments(String sourceId, int limit) {
        return documents.values().stream()
                .filter(state -> state.sourceId().equals(sourceId))
                .limit(Math.max(1, limit))
                .toList();
    }

    @Override
    public synchronized void purgeSource(String sourceId) {
        documents.values().removeIf(state -> state.sourceId().equals(sourceId));
        runs.values().removeIf(run -> run.sourceId().equals(sourceId));
        String prefix = sourceId + KEY_SEPARATOR;
        fencingRunIds.keySet().removeIf(documentKey -> documentKey.startsWith(prefix));
        fencingGenerations.keySet().removeIf(documentKey -> documentKey.startsWith(prefix));
    }

    @Override
    public synchronized Optional<String> startRun(String sourceId) {
        if (activeRun(sourceId).isPresent()) {
            return Optional.empty();
        }
        String runId = UUID.randomUUID().toString();
        long generation = nextGeneration(sourceId);
        runs.put(runId, new IngestionRun(runId, sourceId, IngestionRun.Status.RUNNING, Instant.now(), null,
                0, 0, 0, 0, 0, 0, 0.0, null));
        runGenerations.put(runId, generation);
        takeOwnership(sourceId, runId, generation);
        return Optional.of(runId);
    }

    @Override
    public synchronized void finishRun(IngestionRun run) {
        IngestionRun existing = runs.get(run.runId());
        if (existing == null || existing.status() != IngestionRun.Status.RUNNING) {
            // Already closed — reaped while this worker was still going. Both real
            // backends compare-and-set on RUNNING, so the double must too, or it
            // hides the divergence it exists to catch.
            return;
        }
        runs.put(run.runId(), new IngestionRun(run.runId(), existing.sourceId(), run.status(),
                existing.startedAt(), run.finishedAt() == null ? Instant.now() : run.finishedAt(),
                run.documentsSeen(), run.documentsIngested(), run.documentsUnchanged(), run.documentsFailed(),
                run.documentsTombstoned(), run.segmentsStored(), run.costUsd(), run.error()));
    }

    @Override
    public synchronized Optional<IngestionRun> activeRun(String sourceId) {
        return runs.values().stream()
                .filter(run -> run.sourceId().equals(sourceId) && run.status() == IngestionRun.Status.RUNNING)
                .findFirst();
    }

    @Override
    public synchronized List<IngestionRun> listRuns(String sourceId, int limit) {
        return runs.values().stream()
                .filter(run -> run.sourceId().equals(sourceId))
                .filter(run -> run.status() != IngestionRun.Status.MAINTENANCE)
                .sorted(Comparator.comparing(IngestionRun::startedAt).reversed())
                .limit(Math.max(1, limit))
                .toList();
    }

    /**
     * Backdates a run, so a test can produce the one state that matters here: a
     * RUNNING row older than the stale threshold, left by a process that died.
     * Without it a reaping test can only reap runs it started moments ago, which
     * the production threshold would never touch.
     */
    public synchronized void backdateRun(String runId, Instant startedAt) {
        IngestionRun run = runs.get(runId);
        if (run == null) {
            throw new IllegalArgumentException("no such run: " + runId);
        }
        runs.put(runId, new IngestionRun(run.runId(), run.sourceId(), run.status(), startedAt, run.finishedAt(),
                run.documentsSeen(), run.documentsIngested(), run.documentsUnchanged(), run.documentsFailed(),
                run.documentsTombstoned(), run.segmentsStored(), run.costUsd(), run.error()));
    }

    @Override
    public synchronized int reapStaleRuns(String sourceId, Instant startedBefore) {
        List<String> reaped = new ArrayList<>();
        for (Map.Entry<String, IngestionRun> entry : runs.entrySet()) {
            IngestionRun run = entry.getValue();
            if (run.sourceId().equals(sourceId) && run.status() == IngestionRun.Status.RUNNING
                    && run.startedAt().isBefore(startedBefore)) {
                entry.setValue(new IngestionRun(run.runId(), run.sourceId(), IngestionRun.Status.FAILED,
                        run.startedAt(), Instant.now(), run.documentsSeen(), run.documentsIngested(),
                        run.documentsUnchanged(), run.documentsFailed(), run.documentsTombstoned(),
                        run.segmentsStored(), run.costUsd(),
                        "Run abandoned — no completion recorded before the stale threshold"));
                reaped.add(run.runId());
            }
        }
        // Scoped to the runs this call reaped, and to nobody else. A source-wide
        // release would wipe the fence of a replacement run that claimed the source
        // between the two writes, and every write it made afterwards would silently
        // match nothing.
        if (!reaped.isEmpty()) {
            String prefix = sourceId + KEY_SEPARATOR;
            fencingRunIds.entrySet().removeIf(
                    fence -> fence.getKey().startsWith(prefix) && reaped.contains(fence.getValue()));
        }
        return reaped.size();
    }
}
