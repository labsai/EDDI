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
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;
import org.bson.conversions.Bson;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * MongoDB implementation of {@link IIngestionStateStore}.
 *
 * <p>
 * Two collections: one document-state row per (source, document), and one row
 * per run. The unique index on {@code (sourceId, status=RUNNING)} is what makes
 * {@link #startRun} a real mutual exclusion rather than a check-then-act race —
 * a second caller gets a duplicate-key error and is told a run is already in
 * flight.
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

    private static final String FIELD_SOURCE_ID = "sourceId";
    private static final String FIELD_DOCUMENT_ID = "documentId";
    private static final String FIELD_CONTENT_HASH = "contentHash";
    private static final String FIELD_ETAG = "etag";
    private static final String FIELD_LAST_MODIFIED = "lastModified";
    private static final String FIELD_FIRST_INGESTED_AT = "firstIngestedAt";
    private static final String FIELD_LAST_INGESTED_AT = "lastIngestedAt";
    private static final String FIELD_LAST_RUN_ID = "lastRunId";
    private static final String FIELD_MISSED_RUNS = "missedRuns";
    private static final String FIELD_TOMBSTONED = "tombstoned";
    /** The run that currently owns this document row — see the class comment. */
    private static final String FIELD_FENCING_RUN_ID = "fencingRunId";
    /** The claim sequence number that stamped {@link #FIELD_FENCING_RUN_ID}. */
    private static final String FIELD_FENCING_GENERATION = "fencingGeneration";

    private static final String FIELD_RUN_ID = "runId";
    private static final String FIELD_GENERATION = "generation";
    private static final String FIELD_STATUS = "status";
    private static final String FIELD_STARTED_AT = "startedAt";
    private static final String FIELD_FINISHED_AT = "finishedAt";
    private static final String FIELD_DOCS_SEEN = "documentsSeen";
    private static final String FIELD_DOCS_INGESTED = "documentsIngested";
    private static final String FIELD_DOCS_UNCHANGED = "documentsUnchanged";
    private static final String FIELD_DOCS_FAILED = "documentsFailed";
    private static final String FIELD_DOCS_TOMBSTONED = "documentsTombstoned";
    private static final String FIELD_SEGMENTS_STORED = "segmentsStored";
    private static final String FIELD_COST_USD = "costUsd";
    private static final String FIELD_ERROR = "error";

    private final MongoCollection<Document> documents;
    private final MongoCollection<Document> runs;

    @Inject
    public MongoIngestionStateStore(MongoDatabase database) {
        this.documents = database.getCollection(DOCUMENTS_COLLECTION);
        this.runs = database.getCollection(RUNS_COLLECTION);

        this.documents.createIndex(Indexes.ascending(FIELD_SOURCE_ID, FIELD_DOCUMENT_ID),
                new IndexOptions().name("idx_ingestion_doc").unique(true).background(true));
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
                               String etag, String lastModified, String runId) {

        Instant now = Instant.now();
        Bson update = Updates.combine(
                Updates.set(FIELD_CONTENT_HASH, contentHash),
                Updates.set(FIELD_ETAG, etag),
                Updates.set(FIELD_LAST_MODIFIED, lastModified),
                Updates.set(FIELD_LAST_INGESTED_AT, Date.from(now)),
                Updates.set(FIELD_LAST_RUN_ID, runId),
                Updates.set(FIELD_MISSED_RUNS, 0),
                Updates.set(FIELD_TOMBSTONED, false),
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
        });
    }

    @Override
    public Optional<String> startRun(String sourceId) {
        String runId = UUID.randomUUID().toString();
        long generation = nextGeneration(sourceId);
        Document run = new Document(FIELD_RUN_ID, runId)
                .append(FIELD_SOURCE_ID, sourceId)
                .append(FIELD_STATUS, IngestionRun.Status.RUNNING.name())
                .append(FIELD_GENERATION, generation)
                .append(FIELD_STARTED_AT, Date.from(Instant.now()));
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
        var result = translating("finish a run", () -> runs.updateOne(
                Filters.and(Filters.eq(FIELD_RUN_ID, run.runId()),
                        Filters.eq(FIELD_STATUS, IngestionRun.Status.RUNNING.name())),
                Updates.combine(
                        Updates.set(FIELD_STATUS, run.status().name()),
                        Updates.set(FIELD_FINISHED_AT,
                                Date.from(run.finishedAt() == null ? Instant.now() : run.finishedAt())),
                        Updates.set(FIELD_DOCS_SEEN, run.documentsSeen()),
                        Updates.set(FIELD_DOCS_INGESTED, run.documentsIngested()),
                        Updates.set(FIELD_DOCS_UNCHANGED, run.documentsUnchanged()),
                        Updates.set(FIELD_DOCS_FAILED, run.documentsFailed()),
                        Updates.set(FIELD_DOCS_TOMBSTONED, run.documentsTombstoned()),
                        Updates.set(FIELD_SEGMENTS_STORED, run.segmentsStored()),
                        Updates.set(FIELD_COST_USD, run.costUsd()),
                        Updates.set(FIELD_ERROR, run.error()))));
        if (result.getMatchedCount() == 0) {
            // The run was reaped while it was still working. Its own result is
            // discarded — the reaper already declared it dead, and a second run may
            // have started since — but it must not pass unrecorded.
            LOGGER.warnf("Ingestion run %s was already closed (reaped) before it finished; its result is discarded",
                    LogSanitizer.sanitize(run.runId()));
        }
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
            // recordUnreachable / tombstoneMissing would all silently match nothing
            // while it kept crawling and embedding.
            translating("release ownership of a reaped source",
                    () -> documents.updateMany(
                            Filters.and(Filters.eq(FIELD_SOURCE_ID, sourceId),
                                    Filters.in(FIELD_FENCING_RUN_ID, reaped)),
                            Updates.unset(FIELD_FENCING_RUN_ID)));
        }
        return reaped.size();
    }

    /**
     * Fails every stale run of this source and returns their ids.
     *
     * <p>
     * One at a time, each id claimed by the same statement that fails its run — the
     * ids have to come back <em>with</em> the write, not from a read after it, or a
     * replacement claiming the source in between would be released by the caller.
     * The same shape {@code tombstoneMissing} uses, and it terminates for the same
     * reason: every update removes that run from the filter's own match set.
     * </p>
     */
    private List<String> doReap(String sourceId, Instant startedBefore) {
        return translating("reap stale runs", () -> {
            var stale = Filters.and(
                    Filters.eq(FIELD_SOURCE_ID, sourceId),
                    Filters.eq(FIELD_STATUS, IngestionRun.Status.RUNNING.name()),
                    Filters.lt(FIELD_STARTED_AT, Date.from(startedBefore)));
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
                Boolean.TRUE.equals(document.getBoolean(FIELD_TOMBSTONED)));
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
                document.getString(FIELD_ERROR));
    }

    private static int intOrZero(Document document, String field) {
        return document.get(field) == null ? 0 : document.getInteger(field);
    }

    private static Instant toInstant(Date date) {
        return date == null ? null : date.toInstant();
    }
}
