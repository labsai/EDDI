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

    /** Neither a source key nor a document URL can contain a newline. */
    private static final String KEY_SEPARATOR = "\n";

    private static String key(String sourceId, String documentId) {
        return sourceId + KEY_SEPARATOR + documentId;
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
        DocumentState existing = documents.get(key(sourceId, documentId));
        Instant firstIngestedAt = existing == null || existing.firstIngestedAt() == null
                ? now
                : existing.firstIngestedAt();

        documents.put(key(sourceId, documentId), new DocumentState(sourceId, documentId, contentHash,
                etag, lastModified, firstIngestedAt, now, runId, 0, false));
    }

    @Override
    public synchronized void recordSeen(String sourceId, String documentId, String runId) {
        DocumentState existing = documents.get(key(sourceId, documentId));
        if (existing == null) {
            return;
        }
        documents.put(key(sourceId, documentId), new DocumentState(sourceId, documentId, existing.contentHash(),
                existing.etag(), existing.lastModified(), existing.firstIngestedAt(), existing.lastIngestedAt(),
                runId, 0, false));
    }

    @Override
    public synchronized List<DocumentState> tombstoneMissing(String sourceId, String runId, int missedRunsThreshold) {
        int threshold = Math.max(1, missedRunsThreshold);
        List<DocumentState> tombstoned = new ArrayList<>();

        for (Map.Entry<String, DocumentState> entry : documents.entrySet()) {
            DocumentState state = entry.getValue();
            if (!state.sourceId().equals(sourceId) || state.tombstoned()) {
                continue;
            }
            int missed = state.missedRuns();
            if (!runId.equals(state.lastRunId())) {
                missed++;
            }
            boolean nowTombstoned = missed >= threshold;
            DocumentState updated = new DocumentState(state.sourceId(), state.documentId(), state.contentHash(),
                    state.etag(), state.lastModified(), state.firstIngestedAt(), state.lastIngestedAt(),
                    state.lastRunId(), missed, nowTombstoned);
            entry.setValue(updated);
            if (nowTombstoned) {
                tombstoned.add(updated);
            }
        }
        return tombstoned;
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
    }

    @Override
    public synchronized Optional<String> startRun(String sourceId) {
        if (activeRun(sourceId).isPresent()) {
            return Optional.empty();
        }
        String runId = UUID.randomUUID().toString();
        runs.put(runId, new IngestionRun(runId, sourceId, IngestionRun.Status.RUNNING, Instant.now(), null,
                0, 0, 0, 0, 0, 0, 0.0, null));
        return Optional.of(runId);
    }

    @Override
    public synchronized void finishRun(IngestionRun run) {
        IngestionRun existing = runs.get(run.runId());
        if (existing == null) {
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
                .sorted(Comparator.comparing(IngestionRun::startedAt).reversed())
                .limit(Math.max(1, limit))
                .toList();
    }

    @Override
    public synchronized int reapStaleRuns(Instant startedBefore) {
        int reaped = 0;
        for (Map.Entry<String, IngestionRun> entry : runs.entrySet()) {
            IngestionRun run = entry.getValue();
            if (run.status() == IngestionRun.Status.RUNNING && run.startedAt().isBefore(startedBefore)) {
                entry.setValue(new IngestionRun(run.runId(), run.sourceId(), IngestionRun.Status.FAILED,
                        run.startedAt(), Instant.now(), run.documentsSeen(), run.documentsIngested(),
                        run.documentsUnchanged(), run.documentsFailed(), run.documentsTombstoned(),
                        run.segmentsStored(), run.costUsd(),
                        "Run abandoned — no completion recorded before the stale threshold"));
                reaped++;
            }
        }
        return reaped;
    }
}
