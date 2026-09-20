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
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.Updates;
import com.mongodb.ErrorCategory;
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

/**
 * MongoDB implementation of {@link IIngestionStateStore}.
 *
 * <p>
 * Two collections: one document-state row per (source, document), and one row
 * per run. The unique index on {@code (sourceId, status=RUNNING)} is what makes
 * {@link #startRun} a real mutual exclusion rather than a check-then-act race —
 * a second caller gets a duplicate-key error and is told a run is already in
 * flight.
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

    private static final String FIELD_RUN_ID = "runId";
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

    @Override
    public Optional<DocumentState> lookup(String sourceId, String documentId) {
        if (sourceId == null || documentId == null) {
            return Optional.empty();
        }
        Document found = documents.find(byDocument(sourceId, documentId)).first();
        return Optional.ofNullable(found).map(MongoIngestionStateStore::toDocumentState);
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

        documents.updateOne(byDocument(sourceId, documentId), update, new UpdateOptions().upsert(true));
    }

    @Override
    public void recordSeen(String sourceId, String documentId, String runId) {
        documents.updateOne(byDocument(sourceId, documentId),
                Updates.combine(
                        Updates.set(FIELD_LAST_RUN_ID, runId),
                        Updates.set(FIELD_MISSED_RUNS, 0),
                        Updates.set(FIELD_TOMBSTONED, false)),
                new UpdateOptions().upsert(false));
    }

    @Override
    public void recordUnreachable(String sourceId, String documentId, String runId) {
        // Only the run marker: the miss counter and the tombstone flag are left
        // exactly as they were, so this run neither condemns the document nor
        // absolves it.
        documents.updateOne(byDocument(sourceId, documentId),
                Updates.set(FIELD_LAST_RUN_ID, runId),
                new UpdateOptions().upsert(false));
    }

    @Override
    public List<DocumentState> tombstoneMissing(String sourceId, String runId, int missedRunsThreshold) {
        int threshold = Math.max(1, missedRunsThreshold);

        Bson missed = Filters.and(
                Filters.eq(FIELD_SOURCE_ID, sourceId),
                Filters.ne(FIELD_LAST_RUN_ID, runId),
                Filters.ne(FIELD_TOMBSTONED, true));

        documents.updateMany(missed, Updates.inc(FIELD_MISSED_RUNS, 1));

        Bson dueForTombstone = Filters.and(
                Filters.eq(FIELD_SOURCE_ID, sourceId),
                Filters.ne(FIELD_TOMBSTONED, true),
                Filters.gte(FIELD_MISSED_RUNS, threshold));

        List<DocumentState> tombstoned = new ArrayList<>();
        for (Document document : documents.find(dueForTombstone)) {
            tombstoned.add(toDocumentState(document));
        }
        if (!tombstoned.isEmpty()) {
            documents.updateMany(dueForTombstone, Updates.set(FIELD_TOMBSTONED, true));
        }
        return tombstoned;
    }

    @Override
    public List<DocumentState> listDocuments(String sourceId, int limit) {
        List<DocumentState> states = new ArrayList<>();
        for (Document document : documents.find(Filters.eq(FIELD_SOURCE_ID, sourceId)).limit(Math.max(1, limit))) {
            states.add(toDocumentState(document));
        }
        return states;
    }

    @Override
    public void purgeSource(String sourceId) {
        documents.deleteMany(Filters.eq(FIELD_SOURCE_ID, sourceId));
        runs.deleteMany(Filters.eq(FIELD_SOURCE_ID, sourceId));
    }

    @Override
    public Optional<String> startRun(String sourceId) {
        String runId = UUID.randomUUID().toString();
        Document run = new Document(FIELD_RUN_ID, runId)
                .append(FIELD_SOURCE_ID, sourceId)
                .append(FIELD_STATUS, IngestionRun.Status.RUNNING.name())
                .append(FIELD_STARTED_AT, Date.from(Instant.now()));
        try {
            runs.insertOne(run);
            return Optional.of(runId);
        } catch (MongoWriteException e) {
            if (e.getError().getCategory() == ErrorCategory.DUPLICATE_KEY) {
                // The partial unique index rejected it: another run is in flight.
                // Losing that race is the expected outcome, not an error.
                return Optional.empty();
            }
            // Anything else is a real failure. Reporting it as "already running"
            // would show the operator a 409 for a broken database.
            throw new IngestionStateStoreException("Failed to start an ingestion run", e);
        }
    }

    @Override
    public void finishRun(IngestionRun run) {
        var result = runs.updateOne(Filters.and(Filters.eq(FIELD_RUN_ID, run.runId()),
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
                        Updates.set(FIELD_ERROR, run.error())));
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
        Document found = runs.find(Filters.and(
                Filters.eq(FIELD_SOURCE_ID, sourceId),
                Filters.eq(FIELD_STATUS, IngestionRun.Status.RUNNING.name()))).first();
        return Optional.ofNullable(found).map(MongoIngestionStateStore::toRun);
    }

    @Override
    public List<IngestionRun> listRuns(String sourceId, int limit) {
        List<IngestionRun> history = new ArrayList<>();
        for (Document document : runs.find(Filters.eq(FIELD_SOURCE_ID, sourceId))
                .sort(Sorts.descending(FIELD_STARTED_AT))
                .limit(Math.max(1, limit))) {
            history.add(toRun(document));
        }
        return history;
    }

    @Override
    public int reapStaleRuns(String sourceId, Instant startedBefore) {
        var result = runs.updateMany(
                Filters.and(
                        Filters.eq(FIELD_SOURCE_ID, sourceId),
                        Filters.eq(FIELD_STATUS, IngestionRun.Status.RUNNING.name()),
                        Filters.lt(FIELD_STARTED_AT, Date.from(startedBefore))),
                Updates.combine(
                        Updates.set(FIELD_STATUS, IngestionRun.Status.FAILED.name()),
                        Updates.set(FIELD_FINISHED_AT, Date.from(Instant.now())),
                        Updates.set(FIELD_ERROR, "Run abandoned — no completion recorded before the stale threshold")));
        return (int) result.getModifiedCount();
    }

    private static Bson byDocument(String sourceId, String documentId) {
        return Filters.and(Filters.eq(FIELD_SOURCE_ID, sourceId), Filters.eq(FIELD_DOCUMENT_ID, documentId));
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
                IngestionRun.Status.valueOf(document.getString(FIELD_STATUS)),
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
