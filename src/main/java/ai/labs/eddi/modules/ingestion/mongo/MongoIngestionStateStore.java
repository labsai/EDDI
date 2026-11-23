/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion.mongo;

import ai.labs.eddi.modules.ingestion.IIngestionStateStore;
import ai.labs.eddi.utils.LogSanitizer;
import org.jboss.logging.Logger;
import ai.labs.eddi.modules.ingestion.IngestionStateStoreException;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.Updates;
import com.mongodb.ErrorCategory;
import com.mongodb.MongoException;
import com.mongodb.MongoWriteException;
import com.mongodb.MongoBulkWriteException;
import com.mongodb.bulk.BulkWriteError;
import com.mongodb.client.model.BulkWriteOptions;
import com.mongodb.client.model.InsertOneModel;
import com.mongodb.client.model.UpdateOneModel;
import com.mongodb.client.model.WriteModel;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;
import org.bson.conversions.Bson;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * MongoDB implementation of {@link IIngestionStateStore}.
 *
 * <p>
 * Three collections: one document-state row per (source, document), one row per
 * run, and one row per source for what concerns the source as a whole (the
 * store fingerprint). The unique index on {@code (sourceId, status=RUNNING)} is
 * what makes {@link #startRun} a real mutual exclusion rather than a
 * check-then-act race — a second caller gets a duplicate-key error and is told
 * a run is already in flight.
 *
 * <h2>Fencing without a join</h2>
 *
 * <p>
 * The fence the interface describes cannot be a lookup of the run row: MongoDB
 * cannot join collections in an update, and multi-document transactions need a
 * replica set, while EDDI supports standalone MongoDB and ships {@code mongo:7}
 * standalone in {@code docker-compose.yml}. So ownership is denormalized onto
 * the document row as {@link #FIELD_FENCING_RUN_ID} and every document write
 * puts the caller's {@code runId} in its own filter. A superseded run's update
 * then matches no document — and on the upsert path it collides with the unique
 * {@code (sourceId, documentId)} index instead, which is the same answer.
 *
 * <p>
 * {@link #FIELD_FENCING_GENERATION} carries the claim's sequence number so the
 * ownership stamps themselves are ordered: a stamp delayed past its own run's
 * reaping cannot take the source back from the run that replaced it.
 */
@ApplicationScoped
@DefaultBean
public class MongoIngestionStateStore implements IIngestionStateStore {

    private static final Logger LOGGER = Logger.getLogger(MongoIngestionStateStore.class);

    static final String DOCUMENTS_COLLECTION = "rag_ingestion_documents";
    static final String RUNS_COLLECTION = "rag_ingestion_runs";
    /** One row per source, for what is about the source rather than a document. */
    static final String SOURCES_COLLECTION = "rag_ingestion_sources";

    private static final String FIELD_SOURCE_ID = "sourceId";
    private static final String FIELD_STORE_FINGERPRINT = "storeFingerprint";
    private static final String FIELD_RESOLVED_SCOPE = "resolvedScope";
    private static final String FIELD_SCOPE_SIGNATURE = "scopeSignature";
    private static final String FIELD_UPDATED_AT = "updatedAt";
    private static final String FIELD_DOCUMENT_ID = "documentId";
    private static final String FIELD_CONTENT_HASH = "contentHash";
    private static final String FIELD_ETAG = "etag";
    private static final String FIELD_LAST_MODIFIED = "lastModified";
    private static final String FIELD_FIRST_INGESTED_AT = "firstIngestedAt";
    private static final String FIELD_LAST_INGESTED_AT = "lastIngestedAt";
    private static final String FIELD_LAST_RUN_ID = "lastRunId";
    private static final String FIELD_MISSED_RUNS = "missedRuns";
    private static final String FIELD_TOMBSTONED = "tombstoned";
    private static final String FIELD_CITATION_HASH = "citationHash";
    /** The run that currently owns this document row — see the class comment. */
    private static final String FIELD_FENCING_RUN_ID = "fencingRunId";
    /** The claim sequence number that stamped {@link #FIELD_FENCING_RUN_ID}. */
    private static final String FIELD_FENCING_GENERATION = "fencingGeneration";

    private static final String FIELD_RUN_ID = "runId";
    private static final String FIELD_GENERATION = "generation";
    private static final String FIELD_STATUS = "status";
    private static final String FIELD_STARTED_AT = "startedAt";
    private static final String FIELD_STALE_AFTER = "staleAfter";
    private static final String FIELD_FINISHED_AT = "finishedAt";
    private static final String FIELD_DOCS_SEEN = "documentsSeen";
    private static final String FIELD_DOCS_INGESTED = "documentsIngested";
    private static final String FIELD_DOCS_UNCHANGED = "documentsUnchanged";
    private static final String FIELD_DOCS_FAILED = "documentsFailed";
    private static final String FIELD_DOCS_TOMBSTONED = "documentsTombstoned";
    private static final String FIELD_SEGMENTS_STORED = "segmentsStored";
    private static final String FIELD_COST_USD = "costUsd";
    private static final String FIELD_ERROR = "error";
    private static final String FIELD_DOCS_SKIPPED = "documentsSkipped";
    private static final String FIELD_STOP_REASON = "stopReason";
    private static final String FIELD_TRIGGER = "trigger";
    private static final String FIELD_DOCS_THROTTLED = "documentsThrottled";
    private static final String FIELD_RESUME_AFTER = "resumeAfter";

    // The work table (IngestionWorkQueue), on the document rows. Absent on a row
    // written before it, which reads as "no work".
    private static final String FIELD_WORK_STATE = "workState";
    private static final String FIELD_WORK_VERSION = "workVersion";
    private static final String FIELD_WORK_TOKEN = "workToken";
    private static final String FIELD_WORK_PAYLOAD = "workPayload";
    private static final String FIELD_NOT_BEFORE = "notBefore";
    private static final String FIELD_ATTEMPTS = "attempts";
    private static final String FIELD_LAST_ERROR = "lastError";
    private static final String FIELD_CLAIMED_BY = "claimedBy";
    private static final String FIELD_CLAIM_UNTIL = "claimUntil";

    /** The longest error text a work row keeps. */
    private static final int MAX_WORK_ERROR_LENGTH = 1_000;

    private final MongoCollection<Document> documents;
    private final MongoCollection<Document> runs;
    private final MongoCollection<Document> sources;

    @Inject
    public MongoIngestionStateStore(MongoDatabase database) {
        this.documents = database.getCollection(DOCUMENTS_COLLECTION);
        this.runs = database.getCollection(RUNS_COLLECTION);
        this.sources = database.getCollection(SOURCES_COLLECTION);

        this.sources.createIndex(Indexes.ascending(FIELD_SOURCE_ID),
                new IndexOptions().name("idx_ingestion_source").unique(true).background(true));

        this.documents.createIndex(Indexes.ascending(FIELD_SOURCE_ID, FIELD_DOCUMENT_ID),
                new IndexOptions().name("idx_ingestion_doc").unique(true).background(true));
        // The claim's index: only rows with work to claim, by due time. Partial on
        // the field's presence, so rows that never had work cost it nothing.
        // With the claim's tiebreak, so the sort is served by the index. Replaces an
        // index of the same purpose without it.
        dropIndexQuietly(this.documents, "idx_ingestion_work_due");
        this.documents.createIndex(
                Indexes.ascending(FIELD_SOURCE_ID, FIELD_WORK_STATE, FIELD_NOT_BEFORE, FIELD_DOCUMENT_ID),
                new IndexOptions().name("idx_ingestion_work_due_v2").background(true)
                        .partialFilterExpression(Filters.exists(FIELD_WORK_STATE, true)));
        this.runs.createIndex(Indexes.ascending(FIELD_RUN_ID),
                new IndexOptions().name("idx_ingestion_run").unique(true).background(true));
        this.runs.createIndex(Indexes.descending(FIELD_SOURCE_ID, FIELD_STARTED_AT),
                new IndexOptions().name("idx_ingestion_run_source").background(true));
        // Partial unique index: at most one RUNNING row per source. This is the
        // single-in-flight guarantee — enforced by the database, so it holds
        // across instances rather than only within one JVM.
        this.runs.createIndex(Indexes.ascending(FIELD_SOURCE_ID),
                new IndexOptions().name("idx_ingestion_run_active").unique(true).background(true)
                        .partialFilterExpression(Filters.eq(FIELD_STATUS, IngestionRun.Status.RUNNING.name())));
    }

    /**
     * Runs a driver call, translating its failures.
     *
     * <p>
     * The interface promises {@link IngestionStateStoreException}, and the
     * PostgreSQL store translates every {@code SQLException} to it. This one used
     * to translate a single case in {@link #startRun} and let the rest escape as
     * {@code MongoException}, so what a caller had to catch during a database
     * outage depended on which backend the operator had chosen — which is the drift
     * the shared contract exists to prevent.
     */
    private <T> T translating(String what, Supplier<T> call) {
        try {
            return call.get();
        } catch (IngestionStateStoreException e) {
            throw e;
        } catch (MongoException e) {
            throw new IngestionStateStoreException("Failed to " + what, e);
        }
    }

    private void translating(String what, Runnable call) {
        translating(what, () -> {
            call.run();
            return null;
        });
    }

    @Override
    public Optional<DocumentState> lookup(String sourceId, String documentId) {
        if (sourceId == null || documentId == null) {
            return Optional.empty();
        }
        return translating("look a document up", () -> {
            Document found = documents.find(byDocument(sourceId, documentId)).first();
            return Optional.ofNullable(found).map(MongoIngestionStateStore::toDocumentState);
        });
    }

    @Override
    public void recordIngested(String sourceId, String documentId, String contentHash,
                               String etag, String lastModified, String runId, String citationHash) {

        Instant now = Instant.now();
        Bson update = Updates.combine(
                Updates.set(FIELD_CONTENT_HASH, contentHash),
                Updates.set(FIELD_ETAG, etag),
                Updates.set(FIELD_LAST_MODIFIED, lastModified),
                Updates.set(FIELD_LAST_INGESTED_AT, Date.from(now)),
                Updates.set(FIELD_LAST_RUN_ID, runId),
                Updates.set(FIELD_MISSED_RUNS, 0),
                Updates.set(FIELD_TOMBSTONED, false),
                Updates.set(FIELD_CITATION_HASH, citationHash),
                // setOnInsert, so re-ingesting a changed page keeps the date it first
                // entered the knowledge base rather than resetting its history.
                Updates.setOnInsert(FIELD_FIRST_INGESTED_AT, Date.from(now)));

        translating("record an ingested document", () -> {
            try {
                // upsert, so a document this source has never held gets a row. The
                // fence is in the filter: an existing row owned by another run does
                // not match, the upsert tries to insert instead, and the unique
                // (sourceId, documentId) index rejects it. A duplicate key here can
                // mean nothing else, because that index is the only unique one on
                // this collection.
                return documents.updateOne(ownedDocument(sourceId, documentId, runId), update,
                        new UpdateOptions().upsert(true));
            } catch (MongoWriteException e) {
                if (e.getError().getCategory() == ErrorCategory.DUPLICATE_KEY) {
                    fenced("record an ingested document", sourceId, documentId, runId);
                    return null;
                }
                throw e;
            }
        });
    }

    @Override
    public void recordCitation(String sourceId, String documentId, String runId, String citationHash) {
        translating("record a document's citation",
                () -> documents.updateOne(ownedDocument(sourceId, documentId, runId),
                        Updates.set(FIELD_CITATION_HASH, citationHash),
                        new UpdateOptions().upsert(false)));
    }

    @Override
    public void recordSeen(String sourceId, String documentId, String runId) {
        translating("record a document as seen",
                () -> documents.updateOne(ownedDocument(sourceId, documentId, runId),
                        Updates.combine(
                                Updates.set(FIELD_LAST_RUN_ID, runId),
                                Updates.set(FIELD_MISSED_RUNS, 0),
                                Updates.set(FIELD_TOMBSTONED, false)),
                        new UpdateOptions().upsert(false)));
    }

    @Override
    public void recordSeen(String sourceId, String documentId, String runId, String etag, String lastModified) {
        translating("record a document as seen",
                () -> documents.updateOne(ownedDocument(sourceId, documentId, runId),
                        Updates.combine(
                                Updates.set(FIELD_LAST_RUN_ID, runId),
                                Updates.set(FIELD_MISSED_RUNS, 0),
                                Updates.set(FIELD_TOMBSTONED, false),
                                Updates.set(FIELD_ETAG, etag),
                                Updates.set(FIELD_LAST_MODIFIED, lastModified)),
                        new UpdateOptions().upsert(false)));
    }

    @Override
    public void recordUnreachable(String sourceId, String documentId, String runId) {
        // Only the run marker: the miss counter and the tombstone flag are left
        // exactly as they were, so this run neither condemns the document nor
        // absolves it.
        translating("record a document as unreachable",
                () -> documents.updateOne(ownedDocument(sourceId, documentId, runId),
                        Updates.set(FIELD_LAST_RUN_ID, runId),
                        new UpdateOptions().upsert(false)));
    }

    @Override
    public List<DocumentState> bumpAndFindMissing(String sourceId, String runId, int missedRunsThreshold) {
        int threshold = Math.max(1, missedRunsThreshold);
        return translating("reconcile missing documents", () -> {

            // Both filters are fenced on the run: a superseded run raises nobody's
            // miss counter and tombstones nobody, so it hands its caller an empty
            // list and deletes no vectors.
            Bson missed = Filters.and(
                    Filters.eq(FIELD_SOURCE_ID, sourceId),
                    Filters.eq(FIELD_FENCING_RUN_ID, runId),
                    Filters.ne(FIELD_LAST_RUN_ID, runId),
                    Filters.ne(FIELD_TOMBSTONED, true));

            documents.updateMany(missed, Updates.inc(FIELD_MISSED_RUNS, 1));

            List<DocumentState> gone = new ArrayList<>();
            for (Document document : documents.find(Filters.and(missed, Filters.gte(FIELD_MISSED_RUNS, threshold)))) {
                gone.add(toDocumentState(document));
            }
            return gone;
        });
    }

    @Override
    public void markTombstoned(String sourceId, List<String> documentIds) {
        if (documentIds == null || documentIds.isEmpty()) {
            return;
        }
        translating("tombstone documents", () -> documents.updateMany(
                Filters.and(Filters.eq(FIELD_SOURCE_ID, sourceId), Filters.in(FIELD_DOCUMENT_ID, documentIds)),
                Updates.set(FIELD_TOMBSTONED, true)));
    }

    @Override
    public void invalidateContent(String sourceId) {
        translating("invalidate a source's content", () -> documents.updateMany(
                Filters.eq(FIELD_SOURCE_ID, sourceId),
                Updates.combine(
                        Updates.set(FIELD_CONTENT_HASH, null),
                        Updates.set(FIELD_ETAG, null),
                        Updates.set(FIELD_LAST_MODIFIED, null))));
    }

    @Override
    public Optional<String> storeFingerprint(String sourceId) {
        return translating("read a source's store fingerprint", () -> {
            Document found = sources.find(Filters.eq(FIELD_SOURCE_ID, sourceId)).first();
            return Optional.ofNullable(found).map(row -> row.getString(FIELD_STORE_FINGERPRINT));
        });
    }

    @Override
    public void recordStoreFingerprint(String sourceId, String fingerprint) {
        translating("record a source's store fingerprint", () -> sources.updateOne(
                Filters.eq(FIELD_SOURCE_ID, sourceId),
                Updates.combine(
                        Updates.set(FIELD_STORE_FINGERPRINT, fingerprint),
                        Updates.set(FIELD_UPDATED_AT, Date.from(Instant.now()))),
                new UpdateOptions().upsert(true)));
    }

    @Override
    public Optional<String> resolvedScope(String sourceId) {
        return readSourceField(sourceId, FIELD_RESOLVED_SCOPE, "read a source's resolved scope");
    }

    @Override
    public void recordResolvedScope(String sourceId, String resolvedScope) {
        writeSourceField(sourceId, FIELD_RESOLVED_SCOPE, resolvedScope, "record a source's resolved scope");
    }

    @Override
    public Optional<String> scopeSignature(String sourceId) {
        return readSourceField(sourceId, FIELD_SCOPE_SIGNATURE, "read a source's scope signature");
    }

    @Override
    public void recordScopeSignature(String sourceId, String signature) {
        writeSourceField(sourceId, FIELD_SCOPE_SIGNATURE, signature, "record a source's scope signature");
    }

    private Optional<String> readSourceField(String sourceId, String field, String what) {
        return translating(what, () -> {
            Document found = sources.find(Filters.eq(FIELD_SOURCE_ID, sourceId)).first();
            return Optional.ofNullable(found).map(row -> row.getString(field));
        });
    }

    private void writeSourceField(String sourceId, String field, String value, String what) {
        translating(what, () -> sources.updateOne(
                Filters.eq(FIELD_SOURCE_ID, sourceId),
                Updates.combine(
                        Updates.set(field, value),
                        Updates.set(FIELD_UPDATED_AT, Date.from(Instant.now()))),
                new UpdateOptions().upsert(true)));
    }

    @Override
    public List<DocumentState> listDocuments(String sourceId, int limit) {
        return translating("list a source's documents", () -> {
            List<DocumentState> states = new ArrayList<>();
            for (Document document : documents.find(Filters.eq(FIELD_SOURCE_ID, sourceId))
                    .limit(Math.max(1, limit))) {
                states.add(toDocumentState(document));
            }
            return states;
        });
    }

    @Override
    public void purgeSource(String sourceId) {
        translating("purge a source", () -> {
            documents.deleteMany(Filters.eq(FIELD_SOURCE_ID, sourceId));
            runs.deleteMany(Filters.eq(FIELD_SOURCE_ID, sourceId));
            sources.deleteMany(Filters.eq(FIELD_SOURCE_ID, sourceId));
        });
    }

    @Override
    public Optional<String> startRun(String sourceId, Instant staleAfter) {
        String runId = UUID.randomUUID().toString();
        long generation = nextGeneration(sourceId);
        Document run = new Document(FIELD_RUN_ID, runId)
                .append(FIELD_SOURCE_ID, sourceId)
                .append(FIELD_STATUS, IngestionRun.Status.RUNNING.name())
                .append(FIELD_GENERATION, generation)
                .append(FIELD_STARTED_AT, Date.from(Instant.now()));
        if (staleAfter != null) {
            run.append(FIELD_STALE_AFTER, Date.from(staleAfter));
        }
        // Inside translating, like every other operation on this store: only the
        // duplicate-key case is special, and handling it here rather than around
        // the helper means a connection failure, a timeout or a step-down during
        // the insert leaves as IngestionStateStoreException rather than as a raw
        // MongoException — which is the backend-dependent exception contract the
        // helper exists to remove.
        return translating("start an ingestion run", () -> {
            try {
                runs.insertOne(run);
            } catch (MongoWriteException e) {
                if (e.getError().getCategory() == ErrorCategory.DUPLICATE_KEY) {
                    // The partial unique index rejected it: another run is in flight.
                    // Losing that race is the expected outcome, not an error.
                    return Optional.<String>empty();
                }
                // Anything else is a real failure, and reporting it as "already
                // running" would show the operator a 409 for a broken database. It
                // falls through to translating, which names it as what it is.
                throw e;
            }
            takeOwnership(sourceId, runId, generation);
            return Optional.of(runId);
        });
    }

    @Override
    public void finishRun(IngestionRun run) {
        List<Bson> updates = new ArrayList<>(counterUpdates(run));
        updates.add(Updates.set(FIELD_STATUS, run.status().name()));
        updates.add(Updates.set(FIELD_FINISHED_AT,
                Date.from(run.finishedAt() == null ? Instant.now() : run.finishedAt())));
        updates.add(Updates.set(FIELD_ERROR, run.error()));
        if (run.stopReason() != null) {
            updates.add(Updates.set(FIELD_STOP_REASON, run.stopReason()));
        }
        if (run.resumeAfter() != null) {
            updates.add(Updates.set(FIELD_RESUME_AFTER, Date.from(run.resumeAfter())));
        }
        var result = translating("finish a run", () -> runs.updateOne(
                Filters.and(Filters.eq(FIELD_RUN_ID, run.runId()),
                        Filters.eq(FIELD_STATUS, IngestionRun.Status.RUNNING.name())),
                Updates.combine(updates)));
        if (result.getMatchedCount() == 0) {
            // The run was reaped while it was still working. Its own result is
            // discarded — the reaper already declared it dead, and a second run may
            // have started since — but it must not pass unrecorded.
            LOGGER.warnf("Ingestion run %s was already closed (reaped) before it finished; its result is discarded",
                    LogSanitizer.sanitize(run.runId()));
        }
        // A finished run holds no claims: whatever its drain did not release goes
        // back to pending here too.
        try {
            releaseClaims(run.sourceId(), run.runId());
        } catch (RuntimeException e) {
            LOGGER.debugf(e, "Could not release the claims of run %s; the next run takes them over",
                    LogSanitizer.sanitize(run.runId()));
        }
    }

    @Override
    public void reportProgress(IngestionRun progress) {
        // Fenced on RUNNING like finishRun, so a late progress write cannot reopen a
        // finished or reaped run, nor overwrite the counters finishRun wrote.
        translating("report a run's progress", () -> runs.updateOne(
                Filters.and(Filters.eq(FIELD_RUN_ID, progress.runId()),
                        Filters.eq(FIELD_STATUS, IngestionRun.Status.RUNNING.name())),
                Updates.combine(counterUpdates(progress))));
    }

    /**
     * The counters and the trigger, which a progress write and the final write
     * share. The trigger is only ever set, never cleared: a caller that does not
     * know it leaves what the first progress write recorded.
     */
    private static List<Bson> counterUpdates(IngestionRun run) {
        List<Bson> updates = new ArrayList<>(List.of(
                Updates.set(FIELD_DOCS_SEEN, run.documentsSeen()),
                Updates.set(FIELD_DOCS_INGESTED, run.documentsIngested()),
                Updates.set(FIELD_DOCS_UNCHANGED, run.documentsUnchanged()),
                Updates.set(FIELD_DOCS_FAILED, run.documentsFailed()),
                Updates.set(FIELD_DOCS_TOMBSTONED, run.documentsTombstoned()),
                Updates.set(FIELD_DOCS_SKIPPED, run.documentsSkipped()),
                Updates.set(FIELD_SEGMENTS_STORED, run.segmentsStored()),
                Updates.set(FIELD_COST_USD, run.costUsd()),
                Updates.set(FIELD_DOCS_THROTTLED, run.documentsThrottled())));
        if (run.trigger() != null) {
            updates.add(Updates.set(FIELD_TRIGGER, run.trigger().name()));
        }
        return updates;
    }

    @Override
    public Optional<IngestionRun> activeRun(String sourceId) {
        return translating("read the active run", () -> {
            Document found = runs.find(Filters.and(
                    Filters.eq(FIELD_SOURCE_ID, sourceId),
                    Filters.eq(FIELD_STATUS, IngestionRun.Status.RUNNING.name()))).first();
            return Optional.ofNullable(found).map(MongoIngestionStateStore::toRun);
        });
    }

    @Override
    public List<IngestionRun> listRuns(String sourceId, int limit) {
        return translating("list a source's runs", () -> {
            List<IngestionRun> history = new ArrayList<>();
            for (Document document : runs.find(Filters.and(Filters.eq(FIELD_SOURCE_ID, sourceId),
                    Filters.ne(FIELD_STATUS, IngestionRun.Status.MAINTENANCE.name())))
                    .sort(Sorts.descending(FIELD_STARTED_AT))
                    .limit(Math.max(1, limit))) {
                history.add(toRun(document));
            }
            return history;
        });
    }

    @Override
    public int reapStaleRuns(String sourceId, Instant startedBefore) {
        List<String> reaped = doReap(sourceId, startedBefore);
        if (!reaped.isEmpty()) {
            // Ownership is taken from the runs this call reaped, and from nobody
            // else, so the worker just declared dead is fenced from this moment
            // rather than only once a replacement run claims the source. No runId
            // matches a missing field.
            //
            // Scoped to those run ids because these are two writes, not one. Once
            // the runs are failed the partial unique index on (sourceId, RUNNING) is
            // free, so a replacement can claim the source and stamp every document
            // with its own id before the release runs. A source-wide release would
            // then wipe the live run's fence, and its recordSeen / recordIngested /
            // recordUnreachable / bumpAndFindMissing would all silently match nothing
            // while it kept crawling and embedding.
            translating("release ownership of a reaped source",
                    () -> documents.updateMany(
                            Filters.and(Filters.eq(FIELD_SOURCE_ID, sourceId),
                                    Filters.in(FIELD_FENCING_RUN_ID, reaped)),
                            Updates.unset(FIELD_FENCING_RUN_ID)));
            // The dead runs' claims go back to pending now, rather than once their
            // leases pass: the crash costs the run its slice, not the documents.
            translating("release a reaped run's claims",
                    () -> documents.updateMany(
                            Filters.and(Filters.eq(FIELD_SOURCE_ID, sourceId),
                                    Filters.eq(FIELD_WORK_STATE, WorkState.CLAIMED.name()),
                                    Filters.in(FIELD_CLAIMED_BY, reaped)),
                            Updates.combine(Updates.set(FIELD_WORK_STATE, WorkState.PENDING.name()),
                                    Updates.unset(FIELD_CLAIMED_BY), Updates.unset(FIELD_CLAIM_UNTIL))));
        }
        return reaped.size();
    }

    // === the work table (IngestionWorkQueue) ===

    @Override
    public List<EnqueueOutcome> enqueueWork(String sourceId, String runId, List<WorkRequest> requests,
                                            Instant now) {
        if (requests.size() > MAX_ENQUEUE_BATCH) {
            throw new IllegalArgumentException("At most " + MAX_ENQUEUE_BATCH + " documents are queued at a time");
        }
        for (WorkRequest request : requests) {
            if (request.payload() != null && request.payload().length() > MAX_PAYLOAD_LENGTH) {
                throw new IllegalArgumentException("A work payload is limited to " + MAX_PAYLOAD_LENGTH
                        + " characters");
            }
        }
        if (requests.isEmpty()) {
            return List.of();
        }
        return translating("queue documents", () -> {
            // One read of the page's rows decides each document; one unordered bulk
            // write applies it. The decision is safe to take outside the write: only the
            // run that owns the source queues for it, and every write still carries the
            // fence and the equal-work condition in its own filter.
            Map<String, Document> existing = new HashMap<>();
            List<String> ids = requests.stream().map(WorkRequest::documentId).distinct().toList();
            for (Document row : documents.find(Filters.and(Filters.eq(FIELD_SOURCE_ID, sourceId),
                    Filters.in(FIELD_DOCUMENT_ID, ids)))) {
                existing.put(row.getString(FIELD_DOCUMENT_ID), row);
            }
            EnqueueOutcome[] outcomes = new EnqueueOutcome[requests.size()];
            List<WriteModel<Document>> writes = new ArrayList<>();
            List<Integer> writeIndex = new ArrayList<>();
            Set<String> inserting = new HashSet<>();
            for (int i = 0; i < requests.size(); i++) {
                WorkRequest request = requests.get(i);
                String documentId = request.documentId();
                Document row = existing.get(documentId);
                if (row == null) {
                    if (!inserting.add(documentId)) {
                        // Listed twice on one page: the first insert queued it.
                        outcomes[i] = EnqueueOutcome.ALREADY_QUEUED;
                        continue;
                    }
                    writes.add(new InsertOneModel<>(newWorkRow(sourceId, runId, request, now)));
                    outcomes[i] = EnqueueOutcome.QUEUED;
                } else if (!runId.equals(row.getString(FIELD_FENCING_RUN_ID))) {
                    fenced("queue a document", sourceId, documentId, runId);
                    outcomes[i] = EnqueueOutcome.FENCED;
                    continue;
                } else if (isEqualWork(row, request.versionToken())) {
                    writes.add(new UpdateOneModel<>(ownedDocument(sourceId, documentId, runId),
                            Updates.set(FIELD_LAST_RUN_ID, runId)));
                    outcomes[i] = EnqueueOutcome.ALREADY_QUEUED;
                } else {
                    writes.add(new UpdateOneModel<>(
                            Filters.and(ownedDocument(sourceId, documentId, runId),
                                    Filters.nor(equalWork(request.versionToken()))),
                            rearm(runId, request, now)));
                    outcomes[i] = EnqueueOutcome.QUEUED;
                }
                writeIndex.add(i);
            }
            if (!writes.isEmpty()) {
                try {
                    documents.bulkWrite(writes, new BulkWriteOptions().ordered(false));
                } catch (MongoBulkWriteException e) {
                    for (BulkWriteError error : e.getWriteErrors()) {
                        if (error.getCategory() != ErrorCategory.DUPLICATE_KEY) {
                            throw e;
                        }
                        // Inserted meanwhile by another run, which owns it.
                        outcomes[writeIndex.get(error.getIndex())] = EnqueueOutcome.FENCED;
                    }
                }
            }
            return List.of(outcomes);
        });
    }

    /**
     * Equal work: the same token (two nulls included), pending, claimed or poisoned
     * — or done for a document never ingested (a file with no text), which
     * re-queueing would only download again.
     */
    private static Bson equalWork(String versionToken) {
        return Filters.and(
                Filters.or(
                        Filters.in(FIELD_WORK_STATE, WorkState.PENDING.name(), WorkState.CLAIMED.name(),
                                WorkState.POISON.name()),
                        Filters.and(Filters.eq(FIELD_WORK_STATE, WorkState.DONE.name()),
                                Filters.eq(FIELD_LAST_INGESTED_AT, null))),
                Filters.eq(FIELD_WORK_TOKEN, versionToken));
    }

    /** {@link #equalWork}, judged on a row already read. */
    private static boolean isEqualWork(Document row, String versionToken) {
        WorkState state = WorkState.parse(row.getString(FIELD_WORK_STATE));
        boolean pending = state == WorkState.PENDING || state == WorkState.CLAIMED || state == WorkState.POISON;
        boolean doneNeverIngested = state == WorkState.DONE && row.get(FIELD_LAST_INGESTED_AT) == null;
        return (pending || doneNeverIngested) && Objects.equals(row.getString(FIELD_WORK_TOKEN), versionToken);
    }

    private static Bson rearm(String runId, WorkRequest request, Instant now) {
        return Updates.combine(
                Updates.set(FIELD_LAST_RUN_ID, runId),
                Updates.set(FIELD_WORK_STATE, WorkState.PENDING.name()),
                Updates.inc(FIELD_WORK_VERSION, 1L),
                Updates.set(FIELD_WORK_TOKEN, request.versionToken()),
                Updates.set(FIELD_WORK_PAYLOAD, request.payload()),
                Updates.set(FIELD_NOT_BEFORE, Date.from(now)),
                Updates.set(FIELD_ATTEMPTS, 0),
                Updates.unset(FIELD_LAST_ERROR),
                Updates.unset(FIELD_CLAIMED_BY),
                Updates.unset(FIELD_CLAIM_UNTIL));
    }

    /**
     * A document this source never held: a row with no vectors (tombstoned, no
     * content hash), owned by this run, with its work.
     */
    private static Document newWorkRow(String sourceId, String runId, WorkRequest request, Instant now) {
        return new Document(FIELD_SOURCE_ID, sourceId)
                .append(FIELD_DOCUMENT_ID, request.documentId())
                .append(FIELD_LAST_RUN_ID, runId)
                .append(FIELD_MISSED_RUNS, 0)
                .append(FIELD_TOMBSTONED, true)
                .append(FIELD_FENCING_RUN_ID, runId)
                .append(FIELD_WORK_STATE, WorkState.PENDING.name())
                .append(FIELD_WORK_VERSION, 1L)
                .append(FIELD_WORK_TOKEN, request.versionToken())
                .append(FIELD_WORK_PAYLOAD, request.payload())
                .append(FIELD_NOT_BEFORE, Date.from(now))
                .append(FIELD_ATTEMPTS, 0);
    }

    @Override
    public List<WorkClaim> claimWork(String sourceId, String claimer, int limit, Instant now, Instant claimUntil) {
        if (limit <= 0) {
            return List.of();
        }
        Date at = Date.from(now);
        // One findOneAndUpdate per row: each moves exactly one row out of the
        // filter's match set atomically, so two claimers on two nodes never come back
        // with the same row. Fenced on the claimer, like every document write.
        Bson due = Filters.and(
                Filters.eq(FIELD_SOURCE_ID, sourceId),
                Filters.eq(FIELD_FENCING_RUN_ID, claimer),
                Filters.or(
                        Filters.and(Filters.eq(FIELD_WORK_STATE, WorkState.PENDING.name()),
                                Filters.lte(FIELD_NOT_BEFORE, at)),
                        Filters.and(Filters.eq(FIELD_WORK_STATE, WorkState.CLAIMED.name()),
                                Filters.or(Filters.lte(FIELD_CLAIM_UNTIL, at),
                                        Filters.ne(FIELD_CLAIMED_BY, claimer)))));
        Bson claim = Updates.combine(
                Updates.set(FIELD_WORK_STATE, WorkState.CLAIMED.name()),
                Updates.set(FIELD_CLAIMED_BY, claimer),
                Updates.set(FIELD_CLAIM_UNTIL, Date.from(claimUntil)));
        var options = new FindOneAndUpdateOptions()
                .sort(Sorts.ascending(FIELD_NOT_BEFORE, FIELD_DOCUMENT_ID))
                .returnDocument(ReturnDocument.AFTER);
        return translating("claim queued documents", () -> {
            List<WorkClaim> claims = new ArrayList<>();
            Document claimed;
            while (claims.size() < limit && (claimed = documents.findOneAndUpdate(due, claim, options)) != null) {
                claims.add(new WorkClaim(claimed.getString(FIELD_DOCUMENT_ID), longOrZero(claimed, FIELD_WORK_VERSION),
                        claimed.getString(FIELD_WORK_TOKEN), claimed.getString(FIELD_WORK_PAYLOAD),
                        intOrZero(claimed, FIELD_ATTEMPTS)));
            }
            return claims;
        });
    }

    /** The condition every claimed-row update carries. */
    private static Bson held(String sourceId, String documentId, String claimer, long workVersion) {
        return Filters.and(byDocument(sourceId, documentId),
                Filters.eq(FIELD_CLAIMED_BY, claimer),
                Filters.eq(FIELD_WORK_VERSION, workVersion),
                Filters.eq(FIELD_WORK_STATE, WorkState.CLAIMED.name()));
    }

    @Override
    public boolean completeWork(String sourceId, String documentId, String claimer, long workVersion) {
        return translating("complete a queued document", () -> documents.updateOne(
                held(sourceId, documentId, claimer, workVersion),
                Updates.combine(
                        Updates.set(FIELD_WORK_STATE, WorkState.DONE.name()),
                        Updates.set(FIELD_ATTEMPTS, 0),
                        Updates.unset(FIELD_CLAIMED_BY),
                        Updates.unset(FIELD_CLAIM_UNTIL),
                        Updates.unset(FIELD_NOT_BEFORE),
                        Updates.unset(FIELD_LAST_ERROR),
                        Updates.unset(FIELD_WORK_PAYLOAD)))
                .getMatchedCount() > 0);
    }

    @Override
    public boolean deferWork(String sourceId, String documentId, String claimer, long workVersion,
                             Instant notBefore) {
        return translating("defer a queued document", () -> documents.updateOne(
                held(sourceId, documentId, claimer, workVersion),
                Updates.combine(
                        Updates.set(FIELD_WORK_STATE, WorkState.PENDING.name()),
                        Updates.set(FIELD_NOT_BEFORE, Date.from(notBefore)),
                        Updates.unset(FIELD_CLAIMED_BY),
                        Updates.unset(FIELD_CLAIM_UNTIL)))
                .getMatchedCount() > 0);
    }

    @Override
    public FailOutcome failWork(String sourceId, String documentId, String claimer, long workVersion, String error,
                                Instant notBefore, int maxAttempts) {
        int cap = Math.max(1, maxAttempts);
        // A pipeline update, so the attempt count after this failure decides the
        // state in the same atomic write.
        Document attemptsAfter = new Document("$add",
                List.of(new Document("$ifNull", List.of("$" + FIELD_ATTEMPTS, 0)), 1));
        Document poisoned = new Document("$gte", List.of(attemptsAfter, cap));
        List<Bson> update = List.of(
                new Document("$set", new Document(FIELD_WORK_STATE,
                        new Document("$cond", List.of(poisoned, WorkState.POISON.name(), WorkState.PENDING.name())))
                        .append(FIELD_NOT_BEFORE, new Document("$cond", Arrays.asList(poisoned, null, Date.from(notBefore))))
                        .append(FIELD_ATTEMPTS, attemptsAfter)
                        // $literal: an error text starting with "$" must not be read as a
                        // field path by the pipeline update.
                        .append(FIELD_LAST_ERROR, new Document("$literal", truncate(error)))),
                new Document("$unset", List.of(FIELD_CLAIMED_BY, FIELD_CLAIM_UNTIL)));
        return translating("record a failed attempt", () -> {
            Document after = documents.findOneAndUpdate(held(sourceId, documentId, claimer, workVersion), update,
                    new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
            if (after == null) {
                return FailOutcome.NOT_CLAIMED;
            }
            return WorkState.POISON.name().equals(after.getString(FIELD_WORK_STATE))
                    ? FailOutcome.POISONED
                    : FailOutcome.RETRY;
        });
    }

    @Override
    public int releaseClaims(String sourceId, String claimer) {
        return translating("release claimed documents", () -> (int) documents.updateMany(
                Filters.and(Filters.eq(FIELD_SOURCE_ID, sourceId),
                        Filters.eq(FIELD_WORK_STATE, WorkState.CLAIMED.name()),
                        Filters.eq(FIELD_CLAIMED_BY, claimer)),
                Updates.combine(Updates.set(FIELD_WORK_STATE, WorkState.PENDING.name()),
                        Updates.unset(FIELD_CLAIMED_BY), Updates.unset(FIELD_CLAIM_UNTIL)))
                .getModifiedCount());
    }

    @Override
    public int cancelUnlistedWork(String sourceId, String runId) {
        return translating("withdraw the work of unlisted documents", () -> (int) documents.updateMany(
                Filters.and(Filters.eq(FIELD_SOURCE_ID, sourceId),
                        Filters.eq(FIELD_FENCING_RUN_ID, runId),
                        Filters.in(FIELD_WORK_STATE, WorkState.PENDING.name(), WorkState.POISON.name()),
                        Filters.ne(FIELD_LAST_RUN_ID, runId)),
                Updates.combine(
                        Updates.unset(FIELD_WORK_STATE),
                        Updates.unset(FIELD_WORK_PAYLOAD),
                        Updates.unset(FIELD_NOT_BEFORE),
                        Updates.set(FIELD_ATTEMPTS, 0),
                        Updates.unset(FIELD_LAST_ERROR),
                        Updates.unset(FIELD_CLAIMED_BY),
                        Updates.unset(FIELD_CLAIM_UNTIL)))
                .getModifiedCount());
    }

    @Override
    public WorkCounts workCounts(String sourceId, Instant now) {
        Date at = Date.from(now);
        Document pending = new Document("$eq", List.of("$" + FIELD_WORK_STATE, WorkState.PENDING.name()));
        Document claimed = new Document("$eq", List.of("$" + FIELD_WORK_STATE, WorkState.CLAIMED.name()));
        Document due = new Document("$lte", List.of("$" + FIELD_NOT_BEFORE, at));
        Document lapsed = new Document("$lte", List.of("$" + FIELD_CLAIM_UNTIL, at));
        Document waiting = new Document("$and", List.of(pending, new Document("$gt", List.of("$" + FIELD_NOT_BEFORE,
                at))));
        Document group = new Document("_id", null)
                .append("queued", countIf(new Document("$or", List.of(
                        new Document("$and", List.of(pending, due)),
                        new Document("$and", List.of(claimed, lapsed))))))
                .append("waiting", countIf(waiting))
                .append("inFlight", countIf(new Document("$and", List.of(claimed,
                        new Document("$gt", List.of("$" + FIELD_CLAIM_UNTIL, at))))))
                .append("done", countIf(new Document("$eq", List.of("$" + FIELD_WORK_STATE, WorkState.DONE.name()))))
                .append("failed", countIf(new Document("$eq", List.of("$" + FIELD_WORK_STATE,
                        WorkState.POISON.name()))))
                .append("next", new Document("$min", new Document("$cond",
                        Arrays.asList(waiting, "$" + FIELD_NOT_BEFORE, null))));
        // One pass over the source's rows with work, served by the claim's index.
        List<Bson> pipeline = List.of(
                new Document("$match", new Document(FIELD_SOURCE_ID, sourceId)
                        .append(FIELD_WORK_STATE, new Document("$exists", true))),
                new Document("$group", group));
        return translating("count queued documents", () -> {
            Document result = documents.aggregate(pipeline).first();
            if (result == null) {
                return WorkCounts.NONE;
            }
            return new WorkCounts(longOrZero(result, "queued"), longOrZero(result, "waiting"),
                    longOrZero(result, "inFlight"), longOrZero(result, "done"), longOrZero(result, "failed"),
                    toInstant(result.getDate("next")));
        });
    }

    private static Document countIf(Document condition) {
        return new Document("$sum", new Document("$cond", List.of(condition, 1L, 0L)));
    }

    private static void dropIndexQuietly(MongoCollection<Document> collection, String name) {
        try {
            collection.dropIndex(name);
        } catch (MongoException e) {
            // Absent, which is the usual case.
        }
    }

    @Override
    public int retryFailedWork(String sourceId, Instant now) {
        return translating("re-arm failed documents", () -> (int) documents.updateMany(
                Filters.and(Filters.eq(FIELD_SOURCE_ID, sourceId),
                        Filters.eq(FIELD_WORK_STATE, WorkState.POISON.name())),
                Updates.combine(
                        Updates.set(FIELD_WORK_STATE, WorkState.PENDING.name()),
                        Updates.set(FIELD_ATTEMPTS, 0),
                        Updates.unset(FIELD_LAST_ERROR),
                        Updates.set(FIELD_NOT_BEFORE, Date.from(now))))
                .getModifiedCount());
    }

    private static String truncate(String error) {
        if (error == null) {
            return null;
        }
        return error.length() <= MAX_WORK_ERROR_LENGTH ? error : error.substring(0, MAX_WORK_ERROR_LENGTH);
    }

    private static long longOrZero(Document document, String field) {
        Object value = document.get(field);
        return value instanceof Number number ? number.longValue() : 0L;
    }

    /**
     * Fails every stale run of this source and returns their ids.
     *
     * <p>
     * One at a time, each id claimed by the same statement that fails its run — the
     * ids have to come back <em>with</em> the write, not from a read after it, or a
     * replacement claiming the source in between would be released by the caller.
     * It terminates because every update removes that run from the filter's own
     * match set.
     * </p>
     */
    private List<String> doReap(String sourceId, Instant startedBefore) {
        return translating("reap stale runs", () -> {
            // A run's own deadline decides when it has one; the start-time cut-off only
            // for runs claimed before deadlines were recorded.
            var stale = Filters.and(
                    Filters.eq(FIELD_SOURCE_ID, sourceId),
                    Filters.eq(FIELD_STATUS, IngestionRun.Status.RUNNING.name()),
                    Filters.or(
                            Filters.lt(FIELD_STALE_AFTER, Date.from(Instant.now())),
                            Filters.and(Filters.exists(FIELD_STALE_AFTER, false),
                                    Filters.lt(FIELD_STARTED_AT, Date.from(startedBefore)))));
            var fail = Updates.combine(
                    Updates.set(FIELD_STATUS, IngestionRun.Status.FAILED.name()),
                    Updates.set(FIELD_FINISHED_AT, Date.from(Instant.now())),
                    Updates.set(FIELD_ERROR,
                            "Run abandoned — no completion recorded before the stale threshold"));
            var claimOne = new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER);
            List<String> reaped = new ArrayList<>();
            Document claimed;
            while ((claimed = runs.findOneAndUpdate(stale, fail, claimOne)) != null) {
                reaped.add(claimed.getString(FIELD_RUN_ID));
            }
            return reaped;
        });
    }

    /**
     * The sequence number for the next claim of this source. Racing claimants can
     * compute the same number, which is harmless: only one of them survives the
     * partial unique index on {@code (sourceId, status=RUNNING)}.
     */
    private long nextGeneration(String sourceId) {
        return translating("read a source's run generation", () -> {
            Document newest = runs.find(Filters.eq(FIELD_SOURCE_ID, sourceId))
                    .sort(Sorts.descending(FIELD_GENERATION))
                    .limit(1)
                    .first();
            Number current = newest == null ? null : newest.get(FIELD_GENERATION, Number.class);
            return (current == null ? 0L : current.longValue()) + 1;
        });
    }

    /**
     * Hands the source's existing document rows to the run that has just claimed
     * it, so every later write can be fenced on the {@code runId} the caller
     * already holds.
     *
     * <p>
     * The generation guard is what keeps the stamps ordered. Without it a stamp
     * held up long enough for its own run to be reaped could land after the
     * replacement run's and take the source back, silencing the run that is
     * actually working.
     */
    private void takeOwnership(String sourceId, String runId, long generation) {
        translating("take ownership of a source's documents",
                () -> documents.updateMany(
                        Filters.and(
                                Filters.eq(FIELD_SOURCE_ID, sourceId),
                                Filters.or(
                                        Filters.exists(FIELD_FENCING_GENERATION, false),
                                        Filters.lt(FIELD_FENCING_GENERATION, generation))),
                        Updates.combine(
                                Updates.set(FIELD_FENCING_RUN_ID, runId),
                                Updates.set(FIELD_FENCING_GENERATION, generation))));
    }

    private static void fenced(String what, String sourceId, String documentId, String runId) {
        // Debug, not warn: the reaper has already decided this run is dead and
        // finishRun says so once. A doomed crawl would otherwise log a line per
        // document about a result that is discarded anyway.
        LOGGER.debugf("Ignored an attempt to %s for source '%s', document '%s': run %s no longer owns the source",
                what, LogSanitizer.sanitize(sourceId), LogSanitizer.sanitize(documentId),
                LogSanitizer.sanitize(runId));
    }

    private static Bson byDocument(String sourceId, String documentId) {
        return Filters.and(Filters.eq(FIELD_SOURCE_ID, sourceId), Filters.eq(FIELD_DOCUMENT_ID, documentId));
    }

    /** {@link #byDocument} plus the fence — see the class comment. */
    private static Bson ownedDocument(String sourceId, String documentId, String runId) {
        return Filters.and(byDocument(sourceId, documentId), Filters.eq(FIELD_FENCING_RUN_ID, runId));
    }

    private static DocumentState toDocumentState(Document document) {
        return new DocumentState(
                document.getString(FIELD_SOURCE_ID),
                document.getString(FIELD_DOCUMENT_ID),
                document.getString(FIELD_CONTENT_HASH),
                document.getString(FIELD_ETAG),
                document.getString(FIELD_LAST_MODIFIED),
                toInstant(document.getDate(FIELD_FIRST_INGESTED_AT)),
                toInstant(document.getDate(FIELD_LAST_INGESTED_AT)),
                document.getString(FIELD_LAST_RUN_ID),
                document.get(FIELD_MISSED_RUNS) == null ? 0 : document.getInteger(FIELD_MISSED_RUNS),
                Boolean.TRUE.equals(document.getBoolean(FIELD_TOMBSTONED)),
                document.getString(FIELD_CITATION_HASH));
    }

    private static IngestionRun toRun(Document document) {
        return new IngestionRun(
                document.getString(FIELD_RUN_ID),
                document.getString(FIELD_SOURCE_ID),
                IngestionRun.Status.parse(document.getString(FIELD_STATUS)),
                toInstant(document.getDate(FIELD_STARTED_AT)),
                toInstant(document.getDate(FIELD_FINISHED_AT)),
                intOrZero(document, FIELD_DOCS_SEEN),
                intOrZero(document, FIELD_DOCS_INGESTED),
                intOrZero(document, FIELD_DOCS_UNCHANGED),
                intOrZero(document, FIELD_DOCS_FAILED),
                intOrZero(document, FIELD_DOCS_TOMBSTONED),
                intOrZero(document, FIELD_SEGMENTS_STORED),
                document.get(FIELD_COST_USD) == null ? 0.0 : document.getDouble(FIELD_COST_USD),
                document.getString(FIELD_ERROR),
                intOrZero(document, FIELD_DOCS_SKIPPED),
                document.getString(FIELD_STOP_REASON),
                IngestionRun.Trigger.parse(document.getString(FIELD_TRIGGER)),
                intOrZero(document, FIELD_DOCS_THROTTLED),
                toInstant(document.getDate(FIELD_RESUME_AFTER)));
    }

    private static int intOrZero(Document document, String field) {
        return document.get(field) == null ? 0 : document.getInteger(field);
    }

    private static Instant toInstant(Date date) {
        return date == null ? null : date.toInstant();
    }
}
