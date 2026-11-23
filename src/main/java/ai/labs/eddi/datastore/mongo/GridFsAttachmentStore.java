/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.mongo;

import ai.labs.eddi.engine.attachments.AttachmentLockOrder;
import ai.labs.eddi.engine.attachments.AttachmentLockOrder.Lock;
import ai.labs.eddi.engine.attachments.AttachmentQuotas;
import ai.labs.eddi.engine.attachments.AttachmentRootDeleted;
import ai.labs.eddi.engine.attachments.Derivation;
import ai.labs.eddi.engine.attachments.Derivation.Kind;
import ai.labs.eddi.engine.attachments.DerivedBlobs;
import ai.labs.eddi.engine.attachments.DerivedCaps;
import ai.labs.eddi.engine.attachments.IAttachmentGrantMigration;
import ai.labs.eddi.engine.attachments.IAttachmentStore;
import ai.labs.eddi.engine.attachments.AttachmentOwnerChanged;
import ai.labs.eddi.engine.attachments.LibraryEntry;
import ai.labs.eddi.engine.attachments.LibraryMeta;
import ai.labs.eddi.engine.attachments.LibraryStoreSupport;
import ai.labs.eddi.engine.attachments.MimeValidator;
import ai.labs.eddi.engine.attachments.OwnerScope;
import ai.labs.eddi.engine.attachments.QuotaMode;
import ai.labs.eddi.engine.attachments.QuotaReservation;
import ai.labs.eddi.engine.attachments.blob.BlobBackend;
import ai.labs.eddi.engine.attachments.blob.BlobBackendSelector;
import ai.labs.eddi.engine.attachments.blob.BlobKeys;
import ai.labs.eddi.engine.attachments.blob.BlobLocator;
import ai.labs.eddi.engine.attachments.blob.BlobMetrics;
import ai.labs.eddi.engine.attachments.blob.BlobStreams;
import ai.labs.eddi.engine.attachments.blob.StreamedUpload;
import com.mongodb.ErrorCategory;
import com.mongodb.MongoTimeoutException;
import com.mongodb.MongoWriteException;
import com.mongodb.ReadPreference;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.gridfs.GridFSBucket;
import com.mongodb.client.gridfs.GridFSBuckets;
import com.mongodb.client.gridfs.model.GridFSFile;
import com.mongodb.client.gridfs.model.GridFSUploadOptions;
import com.mongodb.client.model.Accumulators;
import com.mongodb.client.model.Aggregates;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.Projections;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.Updates;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.NoSuchFileException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * MongoDB GridFS implementation of {@link IAttachmentStore}.
 * <p>
 * Files are stored in the {@code attachments} GridFS bucket. The public
 * {@code storageRef} is a random UUID kept in the file metadata (not the raw
 * ObjectId), so references are unguessable; legacy blobs referenced by their
 * plain ObjectId hex still resolve.
 * <p>
 * <b>Grants</b> live in their own collection, {@value #GRANTS_COLLECTION}
 * ({@code storageRef, conversationId, createdAt}), keyed by the blob's
 * canonical reference. For one release they are also written to the legacy
 * {@code metadata.grants} array and read from both, so a node of the previous
 * release in a rolling upgrade still authorizes a grant made here, and the
 * reverse; {@code AttachmentGrantBackfill} copies the legacy array over.
 * Deleting a blob deletes the grants on it; deleting a conversation's
 * attachments also deletes the grants that conversation <em>held</em> on other
 * blobs, in both shapes.
 * <p>
 * <b>Lineage.</b> A derived blob is a file of the same bucket whose
 * {@code metadata.rootRef} names its root; its {@code conversationId},
 * {@code userId}, {@code tenantId}, {@code ownerScope} and {@code ownerKey} are
 * copied from the root when it is written, so the conversation cascade reaches
 * it and a grantee's erasure does not. A derived file is authorized on its
 * root's file. {@code ownerKey} absent (every file stored before it existed)
 * reads as {@code conversationId}.
 * <p>
 * <b>Quotas.</b> A GridFS upload is not one atomic write, so the quota check
 * and the upload run under a lease lock per quota scope — a document in
 * {@code attachments.quota_locks} keyed by conversation or user, inserted to
 * take the lock (a duplicate key means it is held) and deleted to release it. A
 * lock whose holder died is taken over once its lease has expired. The usage
 * itself is always counted from the blobs, so nothing can drift. A derived
 * write takes the root's source lock, from the same collection, after them.
 * <p>
 * <b>Reservations</b> ({@value #RESERVATIONS_COLLECTION}) hold quota for writes
 * in flight and count as usage of their buckets until they expire by the
 * server's {@code $$NOW}; a TTL index removes stragglers. They are written
 * under the same lease locks as an upload, and deleted with their conversation.
 * <p>
 * <b>Large files.</b> A streamed upload above
 * {@code eddi.attachments.max-size-bytes} keeps its bytes in a blob backend
 * ({@code gridfs}, its own {@code attachment_blobs} bucket); it is recorded as
 * an empty file of this bucket whose metadata names the locator and the size
 * ({@link #sizeOf}), so every query of the bucket sees it as before. A delete
 * of such a file records a tombstone in {@value #TOMBSTONES_COLLECTION} before
 * the file goes, then deletes the object; an upload records an intent tombstone
 * before its first byte. See {@link #sweepTombstones}.
 *
 * @since 6.0.0
 */
@ApplicationScoped
@DefaultBean
public class GridFsAttachmentStore implements IAttachmentStore, IAttachmentGrantMigration {

    private static final Logger LOGGER = Logger.getLogger(GridFsAttachmentStore.class);
    private static final String BUCKET_NAME = "attachments";
    private static final String META_CONVERSATION_ID = "conversationId";
    private static final String META_STORAGE_REF = "storageRef";
    private static final String META_MIME_TYPE = "mimeType";
    private static final String META_GRANTS = "grants";
    private static final String META_USER_ID = "userId";
    private static final String META_TENANT_ID = "tenantId";
    static final String META_OWNER_SCOPE = "ownerScope";
    static final String META_OWNER_KEY = "ownerKey";
    static final String META_ROOT_REF = "rootRef";
    static final String META_PARENT_REF = "parentRef";
    static final String META_KIND = "kind";
    static final String META_UNIT_ID = "unitId";
    static final String META_PAGE = "page";
    static final String META_SHA256 = "sha256";
    static final String META_PAGE_COUNT = "pageCount";
    static final String META_LAST_ACCESSED_AT = "lastAccessedAt";
    static final String META_SIZE_BYTES = "sizeBytes";
    /**
     * Where a file's bytes live when they are not in the bucket: the backend and
     * key of a {@link BlobLocator}. Such a file is stored empty, and
     * {@value #META_SIZE_BYTES} is its size.
     */
    static final String META_BLOB_BACKEND = "blobBackend";
    static final String META_BLOB_KEY = "blobKey";
    /**
     * The {@link QuotaMode} of a streamed upload; absent for
     * {@link QuotaMode#STANDARD}.
     */
    static final String META_QUOTA_MODE = "quotaMode";
    /**
     * A library document's own fields, on its root file; absent on every
     * conversation-owned file. {@value #META_LAST_ACCESSED_AT} is its last access,
     * from which retention counts.
     */
    static final String META_LIBRARY_TITLE = "libraryTitle";
    static final String META_LIBRARY_SLUG = "librarySlug";
    static final String META_AGENT_SCOPE = "agentScope";
    static final String META_CONTRIBUTED_BY = "contributedBy";
    static final String META_SAVED_AT = "savedAt";
    static final String META_REMOVED_AT = "removedAt";
    static final String META_RETENTION_DAYS = "retentionDays";
    /** The scopes of a library owner, as stored. */
    private static final List<String> LIBRARY_SCOPES = List.of(OwnerScope.USER.name(), OwnerScope.TEAM.name());
    /**
     * Owned by a conversation: no owner scope recorded (every file stored before it
     * existed) or {@code CONVERSATION}. {@code $nin} matches a missing field.
     */
    private static final Bson CONVERSATION_OWNED = Filters.nin("metadata." + META_OWNER_SCOPE, LIBRARY_SCOPES);
    private static final Bson LIBRARY_OWNED = Filters.in("metadata." + META_OWNER_SCOPE, LIBRARY_SCOPES);
    private static final String LIBRARY_DELETE_DENIED = "Delete denied: this is a library document; library documents are removed through the library";
    /**
     * How often a derived write starts over when its root changed owner meanwhile.
     */
    static final int OWNER_CHANGE_ATTEMPTS = 3;
    static final String LOCKS_COLLECTION = BUCKET_NAME + ".quota_locks";
    static final String GRANTS_COLLECTION = "attachment_grants";
    static final String GRANT_STORAGE_REF = "storageRef";
    static final String GRANT_CONVERSATION_ID = "conversationId";
    static final String GRANT_CREATED_AT = "createdAt";
    static final String BACKFILL_COLLECTION = "attachment_grants_backfill";
    static final String RESERVATIONS_COLLECTION = "attachment_reservations";
    static final String RESERVATION_OWNER_KEY = "ownerKey";
    static final String RESERVATION_BYTES = "bytes";
    static final String RESERVATION_COUNT = "count";
    static final String RESERVATION_EXPIRES_AT = "expiresAt";
    static final String RESERVATION_CREATED_AT = "createdAt";
    static final String RESERVATION_QUOTA_MODE = "quotaMode";
    /**
     * Backend objects waiting to be removed: of a deleted file whose backend could
     * not delete its bytes (due at once), or of an upload in flight (due once its
     * reservation could no longer be converted). See {@link #sweepTombstones}.
     */
    static final String TOMBSTONES_COLLECTION = "attachment_blob_tombstones";
    static final String TOMBSTONE_BACKEND = "backend";
    static final String TOMBSTONE_KEY = "key";
    static final String TOMBSTONE_OWNER_KEY = "ownerKey";
    static final String TOMBSTONE_NOT_BEFORE = "notBefore";
    static final String TOMBSTONE_ATTEMPTS = "attempts";
    static final String TOMBSTONE_CREATED_AT = "createdAt";
    static final String TOMBSTONE_CLAIMED_UNTIL = "claimedUntil";
    /** How long a sweep holds a tombstone it is working on. */
    static final Duration TOMBSTONE_CLAIM = Duration.ofMinutes(5);
    /** How long past its reservation an upload's intent tombstone waits. */
    static final Duration INTENT_GRACE = Duration.ofMinutes(1);
    /** The tombstones a delete or a streamed upload retries on its way out. */
    static final int TOMBSTONE_SWEEP_BATCH = 5;
    /** A reservation that has not expired, by the database's clock. */
    private static final Bson LIVE_RESERVATION = Filters.expr(new Document("$gt", List.of("$" + RESERVATION_EXPIRES_AT, "$$NOW")));
    private static final Bson EXPIRED_RESERVATION = Filters.expr(new Document("$lte", List.of("$" + RESERVATION_EXPIRES_AT, "$$NOW")));
    private static final String BACKFILL_CURSOR = "cursor";
    /**
     * How long a quota lock is held at most before another upload may take it over.
     */
    static final long LOCK_LEASE_MILLIS = 60_000;
    private static final String SOURCE_LOCK_KEY_PREFIX = "source:";
    /** How long an upload waits for a quota lock before giving up. */
    static final long LOCK_WAIT_MILLIS = 15_000;
    private static final long LOCK_POLL_MILLIS = 25;
    static final long INDEX_TIMEOUT_SECONDS = 10;
    /** Matches roots only: a file with no {@code metadata.rootRef}. */
    private static final Bson ROOTS_ONLY = Filters.eq("metadata." + META_ROOT_REF, null);

    private final GridFSBucket gridFSBucket;
    /**
     * The same bucket, read from the PRIMARY, for the two reads that must see every
     * upload already committed: the quota usage and the erasure sweep. The client
     * reads {@code nearest} ({@code PersistenceModule}); a lagging secondary
     * undercounted the uploads a quota had admitted, and left committed blobs out
     * of a conversation's erasure while its discovery records were deleted.
     */
    private final GridFSBucket primaryReadBucket;
    private final MongoCollection<Document> filesCollection;
    private final MongoCollection<Document> locksCollection;
    /**
     * Read from the PRIMARY, like the quota usage: a member's first read follows
     * the grant that admitted it by milliseconds, and a lagging secondary would
     * refuse it.
     */
    private final MongoCollection<Document> grantsCollection;
    /** The backfill's cursor, kept across starts. */
    private final MongoCollection<Document> backfillCollection;
    /**
     * Quota held by writes in flight, read from the PRIMARY: it is part of every
     * usage count.
     */
    private final MongoCollection<Document> reservationsCollection;
    /** Read from the PRIMARY, like the reservations. */
    private final MongoCollection<Document> tombstonesCollection;

    @ConfigProperty(name = "eddi.attachments.max-size-bytes", defaultValue = "20971520") // 20 MB
    long maxSizeBytes;

    @ConfigProperty(name = "eddi.attachments.max-per-conversation", defaultValue = "50")
    long maxPerConversation;

    @ConfigProperty(name = "eddi.attachments.max-total-bytes-per-conversation", defaultValue = "104857600") // 100 MB
    long maxTotalBytesPerConversation;

    @ConfigProperty(name = "eddi.attachments.max-per-user", defaultValue = "500")
    long maxPerUser;

    @ConfigProperty(name = "eddi.attachments.max-total-bytes-per-user", defaultValue = "1073741824")
    long maxTotalBytesPerUser;

    @ConfigProperty(name = "eddi.attachments.derived.max-per-source", defaultValue = "" + DerivedCaps.DEFAULT_MAX_PER_SOURCE)
    long derivedMaxPerSource = DerivedCaps.DEFAULT_MAX_PER_SOURCE;

    @ConfigProperty(name = "eddi.attachments.derived.max-total-bytes-per-source",
                    defaultValue = "" + DerivedCaps.DEFAULT_MAX_TOTAL_BYTES_PER_SOURCE)
    long derivedMaxTotalBytesPerSource = DerivedCaps.DEFAULT_MAX_TOTAL_BYTES_PER_SOURCE;

    @ConfigProperty(name = "eddi.attachments.derived.max-slices-per-source", defaultValue = "" + DerivedCaps.DEFAULT_MAX_SLICES_PER_SOURCE)
    long derivedMaxSlicesPerSource = DerivedCaps.DEFAULT_MAX_SLICES_PER_SOURCE;

    @ConfigProperty(name = "eddi.attachments.derived.max-slice-bytes-per-source",
                    defaultValue = "" + DerivedCaps.DEFAULT_MAX_SLICE_BYTES_PER_SOURCE)
    long derivedMaxSliceBytesPerSource = DerivedCaps.DEFAULT_MAX_SLICE_BYTES_PER_SOURCE;

    @ConfigProperty(name = "eddi.attachments.derived.max-text-bytes-per-source",
                    defaultValue = "" + DerivedCaps.DEFAULT_MAX_TEXT_BYTES_PER_SOURCE)
    long derivedMaxTextBytesPerSource = DerivedCaps.DEFAULT_MAX_TEXT_BYTES_PER_SOURCE;

    @ConfigProperty(name = "eddi.documents.library.max-per-user", defaultValue = "" + LibraryStoreSupport.DEFAULT_MAX_PER_USER)
    long libraryMaxPerUser = LibraryStoreSupport.DEFAULT_MAX_PER_USER;

    @ConfigProperty(name = "eddi.documents.library.max-total-bytes-per-user",
                    defaultValue = "" + LibraryStoreSupport.DEFAULT_MAX_TOTAL_BYTES_PER_USER)
    long libraryMaxTotalBytesPerUser = LibraryStoreSupport.DEFAULT_MAX_TOTAL_BYTES_PER_USER;

    @ConfigProperty(name = "eddi.documents.library.retention-days", defaultValue = "" + LibraryStoreSupport.DEFAULT_RETENTION_DAYS)
    int libraryRetentionDays = LibraryStoreSupport.DEFAULT_RETENTION_DAYS;

    @ConfigProperty(name = "eddi.documents.library.max-grants-per-document",
                    defaultValue = "" + LibraryStoreSupport.DEFAULT_MAX_GRANTS_PER_DOCUMENT)
    int libraryMaxGrantsPerDocument = LibraryStoreSupport.DEFAULT_MAX_GRANTS_PER_DOCUMENT;

    /**
     * The longest a streamed upload may take: the reservation the store takes for
     * one lasts this long.
     */
    @ConfigProperty(name = "eddi.attachments.blob.upload-ttl-seconds", defaultValue = "3600")
    long uploadTtlSeconds = 3600;

    /**
     * Where large blobs go; {@code null} outside CDI (tests), which stores none.
     */
    @Inject
    BlobBackendSelector blobBackends;

    /** Announces deleted roots; {@code null} outside CDI (tests). */
    @Inject
    Event<AttachmentRootDeleted> rootDeletedEvent;

    /** Announces roots that changed owner; {@code null} outside CDI (tests). */
    @Inject
    Event<AttachmentOwnerChanged> ownerChangedEvent;

    /** {@code null} outside CDI (tests). */
    @Inject
    MeterRegistry meterRegistry;

    @Inject
    public GridFsAttachmentStore(MongoDatabase database) {
        this.gridFSBucket = GridFSBuckets.create(database, BUCKET_NAME);
        this.primaryReadBucket = gridFSBucket.withReadPreference(ReadPreference.primary());
        this.filesCollection = database.getCollection(BUCKET_NAME + ".files");
        this.locksCollection = database.getCollection(LOCKS_COLLECTION);
        this.grantsCollection = database.getCollection(GRANTS_COLLECTION).withReadPreference(ReadPreference.primary());
        this.backfillCollection = database.getCollection(BACKFILL_COLLECTION).withReadPreference(ReadPreference.primary());
        this.reservationsCollection = database.getCollection(RESERVATIONS_COLLECTION).withReadPreference(ReadPreference.primary());
        this.tombstonesCollection = database.getCollection(TOMBSTONES_COLLECTION).withReadPreference(ReadPreference.primary());
    }

    /**
     * The reservation indexes, within a budget of their own; called at startup by
     * {@link GridFsIndexInitializer}. A TTL index on the expiry removes what no
     * reserve swept — only a backstop: usage filters on the expiry itself, since
     * the TTL monitor runs about once a minute.
     */
    public void ensureReservationIndexes() {
        ensureReservationIndexes(reservationsCollection, System.nanoTime() + TimeUnit.SECONDS.toNanos(INDEX_TIMEOUT_SECONDS), System::nanoTime);
    }

    static void ensureReservationIndexes(MongoCollection<Document> reservations, long deadlineNanos, LongSupplier nanoClock) {
        List<Map.Entry<Bson, IndexOptions>> indexes = List.of(
                Map.entry(Indexes.ascending(RESERVATION_EXPIRES_AT), new IndexOptions().expireAfter(0L, TimeUnit.SECONDS)),
                Map.entry(Indexes.ascending(META_CONVERSATION_ID), new IndexOptions()),
                Map.entry(Indexes.ascending(META_USER_ID), new IndexOptions()));
        for (Map.Entry<Bson, IndexOptions> index : indexes) {
            long remainingMillis = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - nanoClock.getAsLong());
            if (remainingMillis <= 0) {
                LOGGER.warn("Ran out of time creating the attachment reservation indexes; reservation lookups fall back to a scan");
                return;
            }
            try {
                reservations.withTimeout(remainingMillis, TimeUnit.MILLISECONDS).createIndex(index.getKey(), index.getValue());
            } catch (MongoTimeoutException e) {
                LOGGER.warnf("Timed out creating an attachment reservation index; reservation lookups fall back to a scan: %s",
                        sanitize(e.getMessage()));
                return;
            } catch (RuntimeException e) {
                LOGGER.warnf("Could not create an attachment reservation index; reservation lookups fall back to a scan: %s",
                        sanitize(e.getMessage()));
            }
        }
    }

    /**
     * Creates the metadata indexes within one {@link #INDEX_TIMEOUT_SECONDS} budget
     * for the whole pass, so an unreachable database delays startup by that much at
     * most rather than by one timeout per index. Called once at startup by
     * {@link GridFsIndexInitializer}, not from the constructor: the bean is created
     * on first use, which put a blocking index build on the first attachment
     * request.
     */
    public void ensureIndexes() {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(INDEX_TIMEOUT_SECONDS);
        ensureIndexes(filesCollection, deadline, System::nanoTime);
        ensureGrantIndexes(grantsCollection, deadline, System::nanoTime);
    }

    /**
     * The lineage indexes, within a budget of their own; called at startup by
     * {@link GridFsIndexInitializer} after {@link #ensureIndexes()}.
     */
    public void ensureLineageIndexes() {
        ensureLineageIndexes(filesCollection, System.nanoTime() + TimeUnit.SECONDS.toNanos(INDEX_TIMEOUT_SECONDS), System::nanoTime);
    }

    /**
     * The library indexes, within a budget of their own; called at startup by
     * {@link GridFsIndexInitializer}.
     */
    public void ensureLibraryIndexes() {
        ensureLibraryIndexes(filesCollection, System.nanoTime() + TimeUnit.SECONDS.toNanos(INDEX_TIMEOUT_SECONDS), System::nanoTime);
    }

    /**
     * The library listings and the reaper's scans, on {@code (ownerKey,
     * lastAccessedAt)} and {@code (ownerKey, removedAt)}, and the slug check on
     * {@code (ownerKey, librarySlug)} — sparse on the library fields, so they hold
     * library roots only. Slug uniqueness itself runs under the library's bucket
     * lock. A failure is logged, as for the other indexes.
     */
    static void ensureLibraryIndexes(MongoCollection<Document> filesCollection, long deadlineNanos, LongSupplier nanoClock) {
        String ownerKey = "metadata." + META_OWNER_KEY;
        Bson libraryRoots = Filters.and(Filters.exists("metadata." + META_SAVED_AT), Filters.in("metadata." + META_OWNER_SCOPE, LIBRARY_SCOPES));
        List<Map.Entry<Bson, IndexOptions>> indexes = List.of(
                Map.entry(Indexes.ascending(ownerKey, "metadata." + META_LAST_ACCESSED_AT),
                        new IndexOptions().partialFilterExpression(libraryRoots)),
                Map.entry(Indexes.ascending(ownerKey, "metadata." + META_REMOVED_AT), new IndexOptions().partialFilterExpression(libraryRoots)),
                Map.entry(Indexes.ascending(ownerKey, "metadata." + META_LIBRARY_SLUG), new IndexOptions().partialFilterExpression(libraryRoots)));
        for (Map.Entry<Bson, IndexOptions> index : indexes) {
            long remainingMillis = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - nanoClock.getAsLong());
            if (remainingMillis <= 0) {
                LOGGER.warn("Ran out of time creating the attachment library indexes; library lookups fall back to a scan");
                return;
            }
            try {
                filesCollection.withTimeout(remainingMillis, TimeUnit.MILLISECONDS).createIndex(index.getKey(), index.getValue());
            } catch (MongoTimeoutException e) {
                LOGGER.warnf("Timed out creating an attachment library index; library lookups fall back to a scan: %s", sanitize(e.getMessage()));
                return;
            } catch (RuntimeException e) {
                LOGGER.warnf("Could not create an attachment library index; library lookups fall back to a scan: %s", sanitize(e.getMessage()));
            }
        }
    }

    /**
     * The lineage lookups ({@code findDerived}, {@code listDerived}, the cascade)
     * on {@code (rootRef, kind, unitId)}, and the dedup backstop: unique on
     * {@code (ownerKey, rootRef, sha256)} for derived files only. Dedup itself runs
     * under the source lock and does not rely on the unique index. A failure is
     * logged, as for the metadata indexes.
     */
    static void ensureLineageIndexes(MongoCollection<Document> filesCollection, long deadlineNanos, LongSupplier nanoClock) {
        String root = "metadata." + META_ROOT_REF;
        List<Map.Entry<Bson, IndexOptions>> indexes = List.of(
                Map.entry(Indexes.ascending(root, "metadata." + META_KIND, "metadata." + META_UNIT_ID), new IndexOptions()),
                Map.entry(Indexes.ascending("metadata." + META_OWNER_KEY, root, "metadata." + META_SHA256),
                        new IndexOptions().unique(true).partialFilterExpression(Filters.exists(root))));
        for (Map.Entry<Bson, IndexOptions> index : indexes) {
            long remainingMillis = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - nanoClock.getAsLong());
            if (remainingMillis <= 0) {
                LOGGER.warn("Ran out of time creating the attachment lineage indexes; lineage lookups fall back to a scan");
                return;
            }
            try {
                filesCollection.withTimeout(remainingMillis, TimeUnit.MILLISECONDS).createIndex(index.getKey(), index.getValue());
            } catch (MongoTimeoutException e) {
                LOGGER.warnf("Timed out creating an attachment lineage index; lineage lookups fall back to a scan: %s", sanitize(e.getMessage()));
                return;
            } catch (RuntimeException e) {
                LOGGER.warnf("Could not create an attachment lineage index; lineage lookups fall back to a scan: %s", sanitize(e.getMessage()));
            }
        }
    }

    /**
     * The grant indexes, within what is left of the same budget: a unique one on
     * {@code (storageRef, conversationId)} — every authorization and the idempotent
     * grant — and one on {@code conversationId}, for listing and for pruning the
     * grants a deleted conversation held. A failure is logged, as for the metadata
     * indexes; nothing here relies on the unique index for correctness.
     */
    static void ensureGrantIndexes(MongoCollection<Document> grantsCollection, long deadlineNanos, LongSupplier nanoClock) {
        List<Map.Entry<Bson, IndexOptions>> indexes = List.of(
                Map.entry(Indexes.ascending(GRANT_STORAGE_REF, GRANT_CONVERSATION_ID), new IndexOptions().unique(true)),
                Map.entry(Indexes.ascending(GRANT_CONVERSATION_ID), new IndexOptions()));
        for (Map.Entry<Bson, IndexOptions> index : indexes) {
            long remainingMillis = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - nanoClock.getAsLong());
            if (remainingMillis <= 0) {
                LOGGER.warn("Ran out of time creating the attachment grant indexes; grant lookups fall back to a scan");
                return;
            }
            try {
                grantsCollection.withTimeout(remainingMillis, TimeUnit.MILLISECONDS).createIndex(index.getKey(), index.getValue());
            } catch (MongoTimeoutException e) {
                LOGGER.warnf("Timed out creating an attachment grant index; grant lookups fall back to a scan: %s", sanitize(e.getMessage()));
                return;
            } catch (RuntimeException e) {
                LOGGER.warnf("Could not create an attachment grant index; grant lookups fall back to a scan: %s", sanitize(e.getMessage()));
            }
        }
    }

    /**
     * Indexes every metadata field a query here filters on, all before one shared
     * deadline.
     * <p>
     * GridFS indexes only {@code filename} and {@code uploadDate} by itself, so
     * resolving a {@code storageRef} (every load, grant and delete), the quota
     * check on every upload, listing a conversation's attachments and GDPR's
     * per-conversation delete were all full scans of {@code attachments.files},
     * growing with every attachment anyone ever uploaded.
     * <p>
     * None is unique: blobs stored before {@code storageRef} existed have none, and
     * a unique index would refuse to build over them. A failure is logged, not
     * thrown — an index is an optimisation, and a deployment whose database user
     * may not create one must still start. A refusal moves on to the next index; a
     * timeout ends the pass, since the next call would only wait on the same
     * unreachable server. Each call gets what is left of the budget, not a fresh
     * one — {@code withTimeout} bounds a single operation.
     *
     * @param deadlineNanos
     *            the {@code nanoClock} reading by which the whole pass must be done
     */
    static void ensureIndexes(MongoCollection<Document> filesCollection, long deadlineNanos, LongSupplier nanoClock) {
        for (String field : List.of(META_STORAGE_REF, META_CONVERSATION_ID, META_GRANTS, META_USER_ID)) {
            long remainingMillis = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - nanoClock.getAsLong());
            if (remainingMillis <= 0) {
                LOGGER.warnf("Ran out of time creating the attachments metadata indexes, from metadata.%s on; attachment lookups fall back to a scan",
                        field);
                return;
            }
            try {
                filesCollection.withTimeout(remainingMillis, TimeUnit.MILLISECONDS).createIndex(Indexes.ascending("metadata." + field));
            } catch (MongoTimeoutException e) {
                LOGGER.warnf("Timed out creating the index on attachments metadata.%s; skipping the rest, attachment lookups fall back to a scan: %s",
                        field, sanitize(e.getMessage()));
                return;
            } catch (RuntimeException e) {
                LOGGER.warnf("Could not create the index on attachments metadata.%s; attachment lookups fall back to a scan: %s",
                        field, sanitize(e.getMessage()));
            }
        }
    }

    @Override
    public Attachment store(byte[] bytes, String declaredMime, String filename,
                            String conversationId, String tenantId, String userId)
            throws AttachmentStoreException {

        if (bytes == null || bytes.length == 0) {
            throw new AttachmentStoreException("Attachment data is empty");
        }
        if (bytes.length > maxSizeBytes) {
            throw new AttachmentStoreException(
                    "Attachment exceeds max size: %d bytes (limit: %d)".formatted(bytes.length, maxSizeBytes));
        }

        // MIME validation
        String detectedMime = MimeValidator.detectMime(bytes);
        if (!MimeValidator.isCompatibleContent(declaredMime, bytes)) {
            throw new AttachmentStoreException(
                    "MIME type mismatch: declared='%s', detected='%s'".formatted(declaredMime, detectedMime));
        }

        String resolvedMime = MimeValidator.normalize(declaredMime != null ? declaredMime : detectedMime);
        String storageRef = UUID.randomUUID().toString();

        Document metadata = new Document()
                .append(META_CONVERSATION_ID, conversationId)
                .append(META_TENANT_ID, tenantId)
                .append(META_MIME_TYPE, resolvedMime)
                .append("sizeBytes", (long) bytes.length)
                .append(META_STORAGE_REF, storageRef)
                .append(META_GRANTS, new ArrayList<String>())
                .append(META_OWNER_SCOPE, OwnerScope.CONVERSATION.name())
                .append(META_OWNER_KEY, conversationId);
        if (userId != null) {
            metadata.append(META_USER_ID, userId);
        }

        GridFSUploadOptions options = new GridFSUploadOptions().metadata(metadata);

        boolean conversationQuota = maxPerConversation > 0 || maxTotalBytesPerConversation > 0;
        boolean userQuota = userId != null && (maxPerUser > 0 || maxTotalBytesPerUser > 0);
        // Conversation first, then user: every upload takes them in this order, so two
        // uploads can never wait on each other in a cycle.
        List<String[]> held = new ArrayList<>();
        try {
            lockAll(bucketLocks(conversationQuota ? conversationId : null, userQuota ? userId : null), held);
            if (conversationQuota) {
                AttachmentQuotas.checkUsage(AttachmentQuotaExceededException.SCOPE_CONVERSATION, usage(META_CONVERSATION_ID, conversationId),
                        bytes.length, maxPerConversation, maxTotalBytesPerConversation);
            }
            if (userQuota) {
                AttachmentQuotas.checkUsage(AttachmentQuotaExceededException.SCOPE_USER, usage(META_USER_ID, userId), bytes.length, maxPerUser,
                        maxTotalBytesPerUser);
            }
            gridFSBucket.uploadFromStream(
                    filename != null ? filename : "unnamed",
                    new ByteArrayInputStream(bytes),
                    options);
        } finally {
            releaseAll(held);
        }

        LOGGER.debugf("Stored attachment '%s' (%s, %d bytes) for conversation '%s' → GridFS %s",
                sanitize(filename), resolvedMime, bytes.length, sanitize(conversationId), storageRef);

        return new Attachment(storageRef, filename, resolvedMime, bytes.length, conversationId);
    }

    private void releaseAll(List<String[]> held) {
        for (int i = held.size() - 1; i >= 0; i--) {
            releaseLock(held.get(i)[0], held.get(i)[1]);
        }
    }

    // ---------------------------------------------------------------- streaming

    @Override
    public Attachment store(InputStream in, long declaredLength, String declaredMime, String filename, String conversationId,
                            String tenantId, String userId, String reservationId, QuotaMode quotaMode)
            throws AttachmentStoreException {
        if (quotaMode == null) {
            throw new IllegalArgumentException("A streamed upload needs a quota mode");
        }
        if (quotaMode == QuotaMode.LARGE_PER_USER && userId == null) {
            throw new IllegalArgumentException("A large upload counts against its user, so it needs one");
        }
        if (in == null || declaredLength < 1) {
            throw new AttachmentStoreException("Attachment data is empty");
        }
        boolean inline = declaredLength <= maxSizeBytes;
        if (!inline && quotaMode == QuotaMode.STANDARD) {
            throw new AttachmentStoreException(
                    "Attachment exceeds max size: %d bytes (limit: %d)".formatted(declaredLength, maxSizeBytes));
        }
        if (!inline && blobBackends == null) {
            throw new AttachmentStoreException("No blob backend is configured for large attachments");
        }
        // The quota is held from before the first byte until the blob is recorded.
        QuotaReservation reservation = reservationId != null
                ? requireCovering(reservationId, conversationId, userId, declaredLength, quotaMode)
                : reserve(new OwnerRef(OwnerScope.CONVERSATION, conversationId, userId), conversationId, userId, declaredLength, 1,
                        uploadTtl(), quotaMode);
        boolean stored = false;
        try {
            StreamedUpload upload = StreamedUpload.begin(in, declaredLength, declaredMime);
            Attachment attachment = inline
                    ? storeStreamedInline(upload, filename, conversationId, tenantId, userId, reservation, quotaMode)
                    : storeStreamedInBackend(upload, filename, conversationId, tenantId, userId, reservation, quotaMode);
            stored = true;
            LOGGER.debugf("Stored streamed attachment '%s' (%s, %d bytes, %s) for conversation '%s'", sanitize(filename),
                    attachment.mimeType(), declaredLength, inline ? "inline" : "in its blob backend", sanitize(conversationId));
            return attachment;
        } finally {
            if (!stored) {
                // Used up either way: converted above, or given back here.
                release(reservation.id());
            }
            sweepTombstonesQuietly();
        }
    }

    private Duration uploadTtl() {
        return Duration.ofSeconds(Math.max(1, Math.min(uploadTtlSeconds, QuotaReservation.MAX_TTL.toSeconds())));
    }

    /**
     * A caller's reservation, if it can cover this upload: live, of this
     * conversation, user and quota mode, and holding at least its bytes and one
     * file. Checked before the first byte is read.
     */
    private QuotaReservation requireCovering(String reservationId, String conversationId, String userId, long bytes, QuotaMode mode)
            throws AttachmentStoreException {
        Document live = reservationsCollection.find(Filters.and(Filters.eq("_id", reservationId), LIVE_RESERVATION,
                Filters.eq(META_CONVERSATION_ID, conversationId), Filters.eq(META_USER_ID, userId),
                Filters.eq(RESERVATION_QUOTA_MODE, mode.stored()))).first();
        if (live == null) {
            throw new AttachmentQuotaExceededException(AttachmentQuotaExceededException.SCOPE_RESERVATION,
                    "No live quota reservation of this conversation, user and quota mode covers this upload. Please retry.");
        }
        long reserved = ((Number) live.get(RESERVATION_BYTES)).longValue();
        int count = ((Number) live.get(RESERVATION_COUNT)).intValue();
        if (reserved < bytes || count < 1) {
            throw new AttachmentQuotaExceededException(AttachmentQuotaExceededException.SCOPE_RESERVATION,
                    "The upload (%d bytes) is larger than the %d bytes reserved for it".formatted(bytes, reserved));
        }
        return new QuotaReservation(reservationId, live.getString(RESERVATION_OWNER_KEY), reserved, count,
                live.getDate(RESERVATION_EXPIRES_AT).toInstant());
    }

    /**
     * The metadata of a streamed root; {@code locator} {@code null} for one stored
     * inline.
     */
    private static Document streamedMetadata(String storageRef, String conversationId, String tenantId, String userId, String mime,
                                             long size, String sha256, BlobLocator locator, QuotaMode mode) {
        Document metadata = new Document()
                .append(META_CONVERSATION_ID, conversationId)
                .append(META_TENANT_ID, tenantId)
                .append(META_MIME_TYPE, mime)
                .append(META_SIZE_BYTES, size)
                .append(META_STORAGE_REF, storageRef)
                .append(META_GRANTS, new ArrayList<String>())
                .append(META_OWNER_SCOPE, OwnerScope.CONVERSATION.name())
                .append(META_OWNER_KEY, conversationId)
                .append(META_SHA256, sha256);
        if (userId != null) {
            metadata.append(META_USER_ID, userId);
        }
        if (locator != null) {
            metadata.append(META_BLOB_BACKEND, locator.backend()).append(META_BLOB_KEY, locator.key());
        }
        if (mode.stored() != null) {
            metadata.append(META_QUOTA_MODE, mode.stored());
        }
        return metadata;
    }

    /**
     * Small enough for the bucket: read whole (bounded by the memory guard, as
     * every upload always was), stored uncharged, then converted — the blob is
     * visible before the reservation goes, so usage never reads "neither".
     */
    private Attachment storeStreamedInline(StreamedUpload upload, String filename, String conversationId, String tenantId, String userId,
                                           QuotaReservation reservation, QuotaMode mode)
            throws AttachmentStoreException {
        byte[] bytes = upload.readFully();
        String storageRef = UUID.randomUUID().toString();
        Document metadata = streamedMetadata(storageRef, conversationId, tenantId, userId, upload.resolvedMime(), bytes.length,
                upload.sha256Hex(), null, mode);
        ObjectId id = gridFSBucket.uploadFromStream(filename != null ? filename : "unnamed", new ByteArrayInputStream(bytes),
                new GridFSUploadOptions().metadata(metadata));
        try {
            convert(reservation.id(), storageRef);
        } catch (AttachmentStoreException | RuntimeException e) {
            deleteQuietly(id);
            throw e;
        }
        return new Attachment(storageRef, filename, upload.resolvedMime(), bytes.length, conversationId);
    }

    /**
     * Too large for the bucket: streamed to the blob backend holding no lock, then
     * recorded as an empty GridFS file whose metadata names the bytes' locator and
     * size (so every query of the bucket sees it as before), then converted.
     * <p>
     * An <em>intent tombstone</em>, written before the first byte and due only once
     * the reservation can no longer be converted, leaves the object of a writer
     * that dies mid-upload to the sweep. The writer removes it once the blob is
     * recorded and converted; a sweep that claims it first finds the blob and keeps
     * the object.
     */
    private Attachment storeStreamedInBackend(StreamedUpload upload, String filename, String conversationId, String tenantId,
                                              String userId, QuotaReservation reservation, QuotaMode mode)
            throws AttachmentStoreException {
        BlobBackend backend = blobBackends.forWrites();
        BlobLocator locator = new BlobLocator(backend.name(), BlobKeys.random());
        long length = upload.declaredLength();
        writeTombstone(locator, conversationId, reservation.expiresAt().plus(INTENT_GRACE));
        try {
            backend.put(locator.key(), upload.stream(), length, length);
        } catch (IOException e) {
            if (StreamedUpload.keyTaken(e)) {
                removeTombstone(locator);
            } else {
                discard(locator, conversationId);
            }
            throw StreamedUpload.putFailed(e);
        } catch (RuntimeException e) {
            discard(locator, conversationId);
            throw e;
        }
        BlobMetrics.recordWritten(meterRegistry, backend.name(), length);
        String storageRef = UUID.randomUUID().toString();
        ObjectId id = null;
        try {
            Document metadata = streamedMetadata(storageRef, conversationId, tenantId, userId, upload.resolvedMime(), length,
                    upload.sha256Hex(), locator, mode);
            id = gridFSBucket.uploadFromStream(filename != null ? filename : "unnamed", new ByteArrayInputStream(new byte[0]),
                    new GridFSUploadOptions().metadata(metadata));
            convert(reservation.id(), storageRef);
        } catch (AttachmentStoreException | RuntimeException e) {
            if (id != null) {
                deleteQuietly(id);
            }
            discard(locator, conversationId);
            throw e;
        }
        removeTombstone(locator);
        return new Attachment(storageRef, filename, upload.resolvedMime(), length, conversationId);
    }

    private void deleteQuietly(ObjectId id) {
        try {
            gridFSBucket.delete(id);
        } catch (RuntimeException e) {
            LOGGER.warnf("Could not remove an attachment whose upload failed: %s", sanitize(e.getMessage()));
        }
    }

    @Override
    public InputStream open(String storageRef, String requestingConversationId) throws AttachmentStoreException {
        GridFSFile file = readableForRead(storageRef, requestingConversationId);
        BlobLocator locator = locatorOf(file);
        if (locator == null) {
            return gridFSBucket.openDownloadStream(file.getObjectId());
        }
        try {
            return openLocated(locator, 0, -1);
        } catch (IOException e) {
            throw new AttachmentStoreException("Failed to open the attachment's bytes in its blob backend", e);
        }
    }

    @Override
    public InputStream openRange(String storageRef, String requestingConversationId, long offset, long length)
            throws AttachmentStoreException {
        BlobStreams.requireValidRange(offset, length);
        GridFSFile file = readableForRead(storageRef, requestingConversationId);
        BlobLocator locator = locatorOf(file);
        try {
            if (locator == null) {
                InputStream in = gridFSBucket.openDownloadStream(file.getObjectId());
                try {
                    in.skipNBytes(Math.min(offset, file.getLength()));
                } catch (IOException | RuntimeException e) {
                    in.close();
                    throw e;
                }
                return BlobStreams.limit(in, length);
            }
            return openLocated(locator, offset, length);
        } catch (IOException e) {
            throw new AttachmentStoreException("Failed to open the attachment's bytes", e);
        }
    }

    /**
     * The file, authorized as {@link #load} authorizes it; a slice is touched
     * (slices are a least-recently-used cache; nothing else is, so a read stays a
     * read).
     */
    private GridFSFile readableForRead(String storageRef, String requester) throws AttachmentStoreException {
        GridFSFile file = findFileByRef(storageRef);
        if (file == null) {
            throw new AttachmentNotFoundException("Attachment not found: " + storageRef);
        }
        authorize(file, storageRef, requester);
        if (Kind.PDF_SLICE.name().equals(meta(file, META_KIND))) {
            filesCollection.updateOne(Filters.eq("_id", file.getObjectId()), Updates.set("metadata." + META_LAST_ACCESSED_AT, new Date()));
        }
        return file;
    }

    /**
     * A located blob's bytes from its backend — all of them for a negative
     * {@code length}. A backend that has no object under a key a file still names
     * has lost data: a store failure, never "not found".
     */
    private InputStream openLocated(BlobLocator locator, long offset, long length) throws IOException {
        BlobBackend backend = backendFor(locator);
        try {
            InputStream in = length < 0 ? backend.get(locator.key()) : backend.getRange(locator.key(), offset, length);
            return BlobMetrics.countingReads(meterRegistry, backend.name(), in);
        } catch (NoSuchFileException e) {
            throw new IOException("The blob backend has no object for this attachment", e);
        }
    }

    private BlobBackend backendFor(BlobLocator locator) throws IOException {
        if (blobBackends == null) {
            throw new IOException("No blob backend is configured on this node");
        }
        return blobBackends.forLocator(locator);
    }

    /**
     * Where a file's bytes are when they are not in the bucket; {@code null} for
     * inline.
     */
    private static BlobLocator locatorOf(GridFSFile file) {
        String key = meta(file, META_BLOB_KEY);
        return key != null ? new BlobLocator(meta(file, META_BLOB_BACKEND), key) : null;
    }

    /**
     * A file's size: its GridFS length, or — for one whose bytes live in a blob
     * backend, recorded as an empty file — the size its metadata records.
     */
    static long sizeOf(GridFSFile file) {
        Document metadata = file.getMetadata();
        if (metadata != null && metadata.getString(META_BLOB_KEY) != null && metadata.get(META_SIZE_BYTES) instanceof Number size) {
            return size.longValue();
        }
        return file.getLength();
    }

    // --------------------------------------------------------------- tombstones

    /**
     * Writes a tombstone for {@code locator}, due at {@code notBefore} (a database
     * time) or, for {@code null}, at once — or makes an existing one due sooner.
     * Times are the server's ({@code $$NOW}), as for reservations.
     */
    private void writeTombstone(BlobLocator locator, String ownerKey, Instant notBefore) throws AttachmentStoreException {
        writeTombstone(locator, ownerKey, notBefore != null ? literal(Date.from(notBefore)) : "$$NOW");
    }

    /** As above, due at {@code due}: an aggregation expression for a date. */
    private void writeTombstone(BlobLocator locator, String ownerKey, Object due) throws AttachmentStoreException {
        Document set = new Document(TOMBSTONE_BACKEND, literal(locator.backend()))
                .append(TOMBSTONE_KEY, literal(locator.key()))
                .append(TOMBSTONE_OWNER_KEY, literal(ownerKey))
                .append(TOMBSTONE_ATTEMPTS, new Document("$ifNull", List.of("$" + TOMBSTONE_ATTEMPTS, 0)))
                .append(TOMBSTONE_CREATED_AT, new Document("$ifNull", List.of("$" + TOMBSTONE_CREATED_AT, "$$NOW")))
                .append(TOMBSTONE_NOT_BEFORE, new Document("$min", List.of(
                        new Document("$ifNull", List.of("$" + TOMBSTONE_NOT_BEFORE, due)), due)));
        try {
            tombstonesCollection.updateOne(Filters.eq("_id", tombstoneId(locator)), List.of(new Document("$set", set)),
                    new UpdateOptions().upsert(true));
        } catch (RuntimeException e) {
            throw new AttachmentStoreException("Failed to record an attachment blob tombstone", e);
        }
    }

    private static String tombstoneId(BlobLocator locator) {
        return locator.backend() + "/" + locator.key();
    }

    private void removeTombstone(BlobLocator locator) {
        try {
            tombstonesCollection.deleteOne(Filters.eq("_id", tombstoneId(locator)));
        } catch (RuntimeException e) {
            LOGGER.debugf("Could not remove an attachment blob tombstone; the sweep will: %s", sanitize(e.getMessage()));
        }
    }

    /**
     * Deletes an object no file references any more, and its tombstone. If the
     * backend cannot delete it now, the tombstone stays (due at once) and a later
     * sweep retries — for every reader the blob is gone already.
     */
    private void discard(BlobLocator locator, String ownerKey) {
        try {
            backendFor(locator).delete(locator.key());
        } catch (IOException | RuntimeException e) {
            BlobMetrics.recordTombstone(meterRegistry, locator.backend());
            LOGGER.warnf("Could not delete an attachment's bytes from blob backend '%s'; left for the tombstone sweep: %s", locator.backend(),
                    sanitize(e.getMessage()));
            try {
                writeTombstone(locator, ownerKey, null);
            } catch (AttachmentStoreException recordFailure) {
                LOGGER.errorf("Could not record a tombstone for an undeleted attachment blob in backend '%s': %s", locator.backend(),
                        sanitize(recordFailure.getMessage()));
            }
            return;
        }
        removeTombstone(locator);
    }

    private void sweepTombstonesQuietly() {
        try {
            sweepTombstones(TOMBSTONE_SWEEP_BATCH);
        } catch (RuntimeException e) {
            LOGGER.debugf("Attachment blob tombstone sweep failed; the next one retries: %s", sanitize(e.getMessage()));
        }
    }

    /**
     * Claims each due tombstone for {@link #TOMBSTONE_CLAIM} by the server's clock,
     * so two sweeps never work on one; a sweep that dies leaves a claim that
     * lapses. An object a file still references (an upload that completed before
     * its writer removed the tombstone) is kept and only the tombstone goes. A
     * failed delete is retried later, backing off by the minute.
     */
    @Override
    public int sweepTombstones(int max) {
        int cleared = 0;
        for (int i = 0; i < max; i++) {
            Document claimed = tombstonesCollection.findOneAndUpdate(
                    Filters.and(Filters.expr(new Document("$lte", List.of("$" + TOMBSTONE_NOT_BEFORE, "$$NOW"))),
                            Filters.or(Filters.exists(TOMBSTONE_CLAIMED_UNTIL, false),
                                    Filters.expr(new Document("$lt", List.of("$" + TOMBSTONE_CLAIMED_UNTIL, "$$NOW"))))),
                    List.of(new Document("$set", new Document(TOMBSTONE_CLAIMED_UNTIL,
                            new Document("$add", List.of("$$NOW", TOMBSTONE_CLAIM.toMillis()))))));
            if (claimed == null) {
                break;
            }
            BlobLocator locator = new BlobLocator(claimed.getString(TOMBSTONE_BACKEND), claimed.getString(TOMBSTONE_KEY));
            boolean referenced = primaryReadBucket.find(Filters.and(Filters.eq("metadata." + META_BLOB_KEY, locator.key()),
                    Filters.eq("metadata." + META_BLOB_BACKEND, locator.backend()))).first() != null;
            if (!referenced) {
                try {
                    backendFor(locator).delete(locator.key());
                } catch (IOException | RuntimeException e) {
                    int attempts = claimed.get(TOMBSTONE_ATTEMPTS) instanceof Number n ? n.intValue() + 1 : 1;
                    tombstonesCollection.updateOne(Filters.eq("_id", claimed.get("_id")), List.of(new Document("$set",
                            new Document(TOMBSTONE_ATTEMPTS, attempts)
                                    .append(TOMBSTONE_NOT_BEFORE,
                                            new Document("$add", List.of("$$NOW", Duration.ofMinutes(Math.min(attempts, 60)).toMillis())))),
                            new Document("$unset", TOMBSTONE_CLAIMED_UNTIL)));
                    LOGGER.debugf("Could not delete an attachment blob from backend '%s'; retrying later: %s", locator.backend(),
                            sanitize(e.getMessage()));
                    continue;
                }
            }
            tombstonesCollection.deleteOne(Filters.eq("_id", claimed.get("_id")));
            cleared++;
        }
        return cleared;
    }

    @Override
    public long pendingTombstones(String ownerKeyOrNull) {
        return ownerKeyOrNull == null
                ? tombstonesCollection.countDocuments()
                : tombstonesCollection.countDocuments(Filters.eq(TOMBSTONE_OWNER_KEY, ownerKeyOrNull));
    }

    /**
     * The tombstone indexes and the index the sweep asks "does a file still
     * reference this object?" with, within a budget of their own; called at startup
     * by {@link GridFsIndexInitializer}.
     */
    public void ensureBlobIndexes() {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(INDEX_TIMEOUT_SECONDS);
        List<Map.Entry<MongoCollection<Document>, Bson>> indexes = List.of(
                Map.entry(tombstonesCollection, Indexes.ascending(TOMBSTONE_NOT_BEFORE)),
                Map.entry(tombstonesCollection, Indexes.ascending(TOMBSTONE_OWNER_KEY)),
                Map.entry(filesCollection, Indexes.ascending("metadata." + META_BLOB_KEY)));
        for (Map.Entry<MongoCollection<Document>, Bson> index : indexes) {
            long remainingMillis = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
            if (remainingMillis <= 0) {
                LOGGER.warn("Ran out of time creating the attachment blob indexes; tombstone lookups fall back to a scan");
                return;
            }
            try {
                index.getKey().withTimeout(remainingMillis, TimeUnit.MILLISECONDS).createIndex(index.getValue(),
                        new IndexOptions().sparse(true));
            } catch (MongoTimeoutException e) {
                LOGGER.warnf("Timed out creating an attachment blob index; tombstone lookups fall back to a scan: %s", sanitize(e.getMessage()));
                return;
            } catch (RuntimeException e) {
                LOGGER.warnf("Could not create an attachment blob index; tombstone lookups fall back to a scan: %s", sanitize(e.getMessage()));
            }
        }
    }

    /**
     * Marks a file's bytes for removal before the file goes, when they live in a
     * blob backend: a node that stops between the two leaves a tombstone the sweep
     * finds still referenced, and drops. Returns what to discard once the file is
     * gone, or {@code null} for an inline file.
     */
    private BlobLocator retire(GridFSFile file) {
        BlobLocator locator = locatorOf(file);
        if (locator == null) {
            return null;
        }
        String ownerKey = meta(file, META_OWNER_KEY) != null ? meta(file, META_OWNER_KEY) : meta(file, META_CONVERSATION_ID);
        try {
            // Due only after the file delete and the discard that follows have had time
            // to run: a sweep that came in between would find the file still there and
            // drop the tombstone, and a node stopping then would leave the object
            // unmarked. The discard makes it due at once if it cannot delete.
            writeTombstone(locator, ownerKey, new Document("$add", List.of("$$NOW", TOMBSTONE_CLAIM.toMillis())));
        } catch (AttachmentStoreException e) {
            // The delete goes ahead; the object is then discarded below or, failing
            // that, reported by the backend's own listing.
            LOGGER.warnf("Could not record a tombstone before deleting an attachment: %s", sanitize(e.getMessage()));
        }
        return locator;
    }

    /**
     * Deletes a file, its bytes elsewhere marked first; collects them into
     * {@code retired}.
     */
    private void deleteFile(GridFSFile file, List<Map.Entry<BlobLocator, String>> retired) {
        BlobLocator locator = retire(file);
        gridFSBucket.delete(file.getObjectId());
        if (locator != null) {
            String ownerKey = meta(file, META_OWNER_KEY) != null ? meta(file, META_OWNER_KEY) : meta(file, META_CONVERSATION_ID);
            retired.add(Map.entry(locator, ownerKey));
        }
    }

    private void discardAll(List<Map.Entry<BlobLocator, String>> retired) {
        for (Map.Entry<BlobLocator, String> entry : retired) {
            discard(entry.getKey(), entry.getValue());
        }
        if (!retired.isEmpty()) {
            sweepTombstonesQuietly();
        }
    }

    @Override
    public byte[] load(String storageRef, String requestingConversationId) throws AttachmentStoreException {
        GridFSFile file = findFileByRef(storageRef);
        if (file == null) {
            throw new AttachmentNotFoundException("Attachment not found: " + storageRef);
        }
        authorize(file, storageRef, requestingConversationId);
        // On size, not on where the bytes are: an inline file above a guard lowered
        // later is refused too, and a located one within one raised later loads.
        long size = sizeOf(file);
        if (size > maxSizeBytes) {
            throw new AttachmentTooLargeForMemoryException(size, maxSizeBytes);
        }
        BlobLocator locator = locatorOf(file);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (locator == null) {
            gridFSBucket.downloadToStream(file.getObjectId(), out);
        } else {
            try (InputStream in = openLocated(locator, 0, -1)) {
                in.transferTo(out);
            } catch (IOException e) {
                throw new AttachmentStoreException("Failed to read the attachment's bytes from its blob backend", e);
            }
        }
        if (Kind.PDF_SLICE.name().equals(meta(file, META_KIND))) {
            // Slices are a least-recently-used cache; nothing else is touched, so an
            // ordinary load stays a read.
            filesCollection.updateOne(Filters.eq("_id", file.getObjectId()), Updates.set("metadata." + META_LAST_ACCESSED_AT, new Date()));
        }
        return out.toByteArray();
    }

    @Override
    public Attachment getMetadata(String storageRef, String requestingConversationId) throws AttachmentStoreException {
        GridFSFile file = findFileByRef(storageRef);
        if (file == null) {
            throw new AttachmentNotFoundException("Attachment not found: " + storageRef);
        }
        authorize(file, storageRef, requestingConversationId);
        return toAttachment(file, storageRef);
    }

    private static Attachment toAttachment(GridFSFile file, String fallbackRef) {
        Document metadata = file.getMetadata();
        String mime = metadata != null ? metadata.getString(META_MIME_TYPE) : null;
        String owner = metadata != null ? metadata.getString(META_CONVERSATION_ID) : null;
        String ref = metadata != null && metadata.getString(META_STORAGE_REF) != null
                ? metadata.getString(META_STORAGE_REF)
                : fallbackRef;
        return new Attachment(ref, file.getFilename(),
                mime != null ? mime : "application/octet-stream", sizeOf(file), owner);
    }

    @Override
    public void grantAccess(String storageRef, String conversationId) throws AttachmentStoreException {
        // From the PRIMARY: a grant follows the upload it shares by milliseconds, and
        // a lagging secondary would answer "not found".
        GridFSFile file = primaryReadBucket.find(refFilter(storageRef)).first();
        if (file == null) {
            throw new AttachmentNotFoundException("Attachment not found: " + storageRef);
        }
        // Always on the root: a derived ref grants its root.
        String rootRef = meta(file, META_ROOT_REF);
        if (rootRef != null) {
            file = primaryReadBucket.find(Filters.and(refFilter(rootRef), ROOTS_ONLY)).first();
            if (file == null) {
                throw new AttachmentNotFoundException("Attachment not found: " + storageRef);
            }
        }
        String canonicalRef = canonicalRef(file);
        upsertGrant(canonicalRef, conversationId);
        // The legacy shape too, for one release: a node of the previous release
        // reads only this array.
        filesCollection.updateOne(Filters.eq("_id", file.getObjectId()), Updates.addToSet("metadata." + META_GRANTS, conversationId));
        // A delete that ran between the lookup and the grant row leaves a row with
        // no blob; take it back, so a grant never outlives its blob.
        GridFSFile current = primaryReadBucket.find(Filters.eq("_id", file.getObjectId())).first();
        if (current == null) {
            grantsCollection.deleteMany(Filters.eq(GRANT_STORAGE_REF, canonicalRef));
            throw new AttachmentNotFoundException("Attachment not found: " + storageRef);
        }
        if (OwnerScope.fromStored(meta(current, META_OWNER_SCOPE)) != OwnerScope.CONVERSATION) {
            // A library document is opened by any number of conversations: past the cap
            // the oldest grant goes, never the one just written. Not serialized: two
            // concurrent grants may leave one too many, until the next.
            evictGrants(current, canonicalRef, conversationId);
        }
        LOGGER.debugf("Granted conversation '%s' access to attachment %s",
                sanitize(conversationId), storageRef);
    }

    /**
     * One grant row, idempotently: an upsert on the pair, and a duplicate key from
     * a concurrent upsert of the same pair counts as success.
     *
     * @return whether a row was added
     */
    private boolean upsertGrant(String canonicalRef, String conversationId) {
        try {
            var result = grantsCollection.updateOne(
                    Filters.and(Filters.eq(GRANT_STORAGE_REF, canonicalRef), Filters.eq(GRANT_CONVERSATION_ID, conversationId)),
                    Updates.setOnInsert(GRANT_CREATED_AT, new Date()), new UpdateOptions().upsert(true));
            return result.getUpsertedId() != null;
        } catch (MongoWriteException e) {
            if (e.getError().getCategory() == ErrorCategory.DUPLICATE_KEY) {
                return false;
            }
            throw e;
        }
    }

    @Override
    public Page readLegacyGrants(String afterCursor, int limit) {
        // "grants.0 exists" = a non-empty array; blobs carry an empty one from birth.
        Bson hasGrants = Filters.exists("metadata." + META_GRANTS + ".0");
        Bson filter = afterCursor == null ? hasGrants : Filters.and(hasGrants, Filters.gt("_id", new ObjectId(afterCursor)));
        List<LegacyGrants> blobs = new ArrayList<>();
        ObjectId last = null;
        for (GridFSFile file : primaryReadBucket.find(filter).sort(Sorts.ascending("_id")).limit(limit)) {
            Document metadata = file.getMetadata();
            List<String> grants = metadata != null ? metadata.getList(META_GRANTS, String.class) : null;
            blobs.add(new LegacyGrants(canonicalRef(file), grants != null ? List.copyOf(grants) : List.of()));
            last = file.getObjectId();
        }
        return new Page(blobs, blobs.size() < limit || last == null ? null : last.toHexString());
    }

    /**
     * Writes the row, then makes sure the blob still exists: a delete that ran in
     * between would otherwise leave a row with no blob.
     */
    @Override
    public boolean copyGrant(String storageRef, String conversationId) {
        boolean added = upsertGrant(storageRef, conversationId);
        if (primaryReadBucket.find(refFilter(storageRef)).first() == null) {
            removeGrant(storageRef, conversationId);
            return false;
        }
        return added;
    }

    @Override
    public Map<String, Set<String>> existingGrants(Collection<String> storageRefs) {
        Map<String, Set<String>> existing = new HashMap<>();
        if (storageRefs.isEmpty()) {
            return existing;
        }
        for (Document grant : grantsCollection.find(Filters.in(GRANT_STORAGE_REF, storageRefs))
                .projection(Projections.include(GRANT_STORAGE_REF, GRANT_CONVERSATION_ID))) {
            String ref = grant.getString(GRANT_STORAGE_REF);
            String conversationId = grant.getString(GRANT_CONVERSATION_ID);
            if (ref != null && conversationId != null) {
                existing.computeIfAbsent(ref, k -> new HashSet<>()).add(conversationId);
            }
        }
        return existing;
    }

    @Override
    public void removeGrant(String storageRef, String conversationId) {
        grantsCollection.deleteMany(Filters.and(Filters.eq(GRANT_STORAGE_REF, storageRef), Filters.eq(GRANT_CONVERSATION_ID, conversationId)));
    }

    /**
     * One aggregation finds the rows whose {@code storageRef} matches no
     * {@code metadata.storageRef}; a row keyed by an ObjectId hex (a blob stored
     * before storageRef existed) is then checked by {@code _id} before it goes.
     */
    @Override
    public long pruneGrantsWithoutBlob() {
        List<Bson> pipeline = List.of(
                Aggregates.lookup(BUCKET_NAME + ".files", GRANT_STORAGE_REF, "metadata." + META_STORAGE_REF, "blob"),
                Aggregates.match(Filters.size("blob", 0)),
                Aggregates.project(Projections.include(GRANT_STORAGE_REF)));
        Set<String> candidates = new LinkedHashSet<>();
        for (Document row : grantsCollection.aggregate(pipeline)) {
            String ref = row.getString(GRANT_STORAGE_REF);
            if (ref != null) {
                candidates.add(ref);
            }
        }
        long removed = 0;
        for (String ref : candidates) {
            if (ObjectId.isValid(ref) && primaryReadBucket.find(Filters.eq("_id", new ObjectId(ref))).first() != null) {
                continue;
            }
            removed += grantsCollection.deleteMany(Filters.eq(GRANT_STORAGE_REF, ref)).getDeletedCount();
        }
        return removed;
    }

    @Override
    public void ensureGrantIndexes() {
        ensureGrantIndexes(grantsCollection, System.nanoTime() + TimeUnit.SECONDS.toNanos(INDEX_TIMEOUT_SECONDS), System::nanoTime);
    }

    @Override
    public String readBackfillCursor() {
        Document cursor = backfillCollection.find(Filters.eq("_id", BACKFILL_CURSOR)).first();
        return cursor != null ? cursor.getString("value") : null;
    }

    @Override
    public void writeBackfillCursor(String cursor) {
        if (cursor == null) {
            backfillCollection.deleteOne(Filters.eq("_id", BACKFILL_CURSOR));
        } else {
            backfillCollection.replaceOne(Filters.eq("_id", BACKFILL_CURSOR), new Document("_id", BACKFILL_CURSOR).append("value", cursor),
                    new ReplaceOptions().upsert(true));
        }
    }

    /**
     * Evicts the grants of a library document beyond
     * {@code eddi.documents.library.max-grants-per-document}, oldest first, in both
     * shapes, keeping {@code keep}'s.
     */
    private void evictGrants(GridFSFile root, String canonicalRef, String keep) {
        if (libraryMaxGrantsPerDocument <= 0) {
            return;
        }
        List<String> rows = new ArrayList<>();
        for (Document grant : grantsCollection.find(Filters.eq(GRANT_STORAGE_REF, canonicalRef))
                .sort(Sorts.ascending(GRANT_CREATED_AT, "_id")).projection(Projections.include(GRANT_CONVERSATION_ID))) {
            if (grant.getString(GRANT_CONVERSATION_ID) != null) {
                rows.add(grant.getString(GRANT_CONVERSATION_ID));
            }
        }
        Document metadata = root.getMetadata();
        List<String> legacy = metadata != null && metadata.getList(META_GRANTS, String.class) != null
                ? metadata.getList(META_GRANTS, String.class)
                : List.of();
        List<String> evict = LibraryStoreSupport.grantsToEvict(legacy, rows, keep, libraryMaxGrantsPerDocument);
        if (evict.isEmpty()) {
            return;
        }
        grantsCollection.deleteMany(Filters.and(Filters.eq(GRANT_STORAGE_REF, canonicalRef), Filters.in(GRANT_CONVERSATION_ID, evict)));
        filesCollection.updateOne(Filters.eq("_id", root.getObjectId()), Updates.pullAll("metadata." + META_GRANTS, evict));
        LOGGER.debugf("Evicted %d grants of library document %s (cap %d)", (Object) evict.size(), canonicalRef, (Object) libraryMaxGrantsPerDocument);
    }

    /**
     * Deletes one blob for its owning conversation. The root's source lock is held
     * while the owner is read again and the files go, so a {@link #promote} — which
     * holds it too — either made the file a library document before (refused) or
     * finds it gone after.
     */
    @Override
    public boolean delete(String storageRef, String requestingConversationId) throws AttachmentStoreException {
        GridFSFile found = findFileByRef(storageRef);
        if (found == null) {
            return false;
        }
        String lockRef = meta(found, META_ROOT_REF) != null ? meta(found, META_ROOT_REF) : canonicalRef(found);
        List<String[]> held = new ArrayList<>();
        try {
            lockAll(List.of(new Lock(AttachmentLockOrder.Scope.SOURCE, lockRef)), held);
            return deleteLocked(storageRef, requestingConversationId);
        } finally {
            releaseAll(held);
        }
    }

    private boolean deleteLocked(String storageRef, String requestingConversationId) throws AttachmentStoreException {
        GridFSFile file = primaryReadBucket.find(refFilter(storageRef)).first();
        if (file == null) {
            return false;
        }
        GridFSFile lineageRoot = rootFileOf(file);
        // Whichever conversation asks — the one it came from included.
        if (OwnerScope.fromStored(meta(file, META_OWNER_SCOPE)) != OwnerScope.CONVERSATION
                || lineageRoot != null && OwnerScope.fromStored(meta(lineageRoot, META_OWNER_SCOPE)) != OwnerScope.CONVERSATION) {
            throw new AttachmentAccessDeniedException(LIBRARY_DELETE_DENIED);
        }
        Document metadata = file.getMetadata();
        String owner = metadata != null ? metadata.getString(META_CONVERSATION_ID) : null;
        // A blob that records no owner (a pre-ownership legacy blob) stays deletable,
        // unlike readable: deleting discloses nothing, and refusing it would leave
        // personal data that no erasure path could remove.
        if (owner != null && !owner.equals(requestingConversationId)) {
            throw new AttachmentAccessDeniedException(
                    "Delete denied: attachment belongs to '%s', requested from '%s'"
                            .formatted(owner, requestingConversationId));
        }
        String canonicalRef = canonicalRef(file);
        List<Map.Entry<BlobLocator, String>> retired = new ArrayList<>();
        deleteFile(file, retired);
        grantsCollection.deleteMany(Filters.eq(GRANT_STORAGE_REF, canonicalRef));
        if (meta(file, META_ROOT_REF) == null) {
            // The root's lineage: every view of it, whatever stored it.
            for (GridFSFile derived : primaryReadBucket.find(Filters.eq("metadata." + META_ROOT_REF, canonicalRef))) {
                deleteFile(derived, retired);
            }
        }
        discardAll(retired);
        if (meta(file, META_ROOT_REF) == null) {
            DerivedBlobs.fireRootDeleted(rootDeletedEvent, List.of(rootDeleted(file, canonicalRef)));
        }
        return true;
    }

    private static AttachmentRootDeleted rootDeleted(GridFSFile root, String canonicalRef) {
        String conversationId = meta(root, META_CONVERSATION_ID);
        String ownerKey = meta(root, META_OWNER_KEY);
        return new AttachmentRootDeleted(canonicalRef, OwnerScope.fromStored(meta(root, META_OWNER_SCOPE)),
                ownerKey != null ? ownerKey : conversationId, meta(root, META_USER_ID));
    }

    /**
     * Deletes the conversation's files — its roots and the lineage stored under
     * them (a derived file carries its root owner's {@code conversationId}) — and
     * the grants on them and held by it, then announces each root.
     *
     * @return the number of roots deleted
     */
    @Override
    public long deleteByConversation(String conversationId) {
        List<String> deletedRefs = new ArrayList<>();
        List<AttachmentRootDeleted> roots = new ArrayList<>();
        List<Map.Entry<BlobLocator, String>> retired = new ArrayList<>();
        // The quota this conversation's writes in flight held, before the blobs:
        // deleted after them, a blob stored after the scan below could still be
        // converted and would survive the erasure. Deleted first, every later convert
        // is refused and its writer deletes the blob, and a blob converted before
        // this line was stored before it, so the scan sees it.
        reservationsCollection.deleteMany(Filters.eq(META_CONVERSATION_ID, conversationId));
        // A library document that came from this conversation keeps its conversation
        // id as origin; it is the library's, and stays.
        for (GridFSFile file : primaryReadBucket.find(Filters.and(Filters.eq("metadata." + META_CONVERSATION_ID, conversationId),
                CONVERSATION_OWNED))) {
            deleteFile(file, retired);
            String ref = canonicalRef(file);
            deletedRefs.add(ref);
            if (meta(file, META_ROOT_REF) == null) {
                roots.add(rootDeleted(file, ref));
            }
        }
        // The lineage once more: a view uploaded while the cursor above ran may not be
        // in it. storeDerived re-checks its root after the upload, so a view is either
        // seen here or deleted by its own writer.
        if (!roots.isEmpty()) {
            for (GridFSFile derived : primaryReadBucket.find(
                    Filters.in("metadata." + META_ROOT_REF, roots.stream().map(AttachmentRootDeleted::rootRef).toList()))) {
                deleteFile(derived, retired);
            }
        }
        discardAll(retired);
        // The grants on the blobs just deleted, and the grants this conversation held
        // on other blobs — in both shapes, or they would grow without bound.
        if (!deletedRefs.isEmpty()) {
            grantsCollection.deleteMany(Filters.in(GRANT_STORAGE_REF, deletedRefs));
        }
        long held = grantsCollection.deleteMany(Filters.eq(GRANT_CONVERSATION_ID, conversationId)).getDeletedCount();
        filesCollection.updateMany(Filters.eq("metadata." + META_GRANTS, conversationId), Updates.pull("metadata." + META_GRANTS, conversationId));
        if (!deletedRefs.isEmpty() || held > 0) {
            LOGGER.debugf("Deleted %d attachments (%d roots) and %d held grants for conversation '%s'", deletedRefs.size(), roots.size(), held,
                    sanitize(conversationId));
        }
        DerivedBlobs.fireRootDeleted(rootDeletedEvent, roots);
        return roots.size();
    }

    @Override
    public List<Attachment> listByConversation(String conversationId) {
        // A library document keeps its origin conversation's id, but is not its own.
        return listMatching(Filters.and(Filters.eq("metadata." + META_CONVERSATION_ID, conversationId), CONVERSATION_OWNED, ROOTS_ONLY));
    }

    @Override
    public List<Attachment> listAccessible(String conversationId) {
        // Two round trips: the refs granted in the grants collection, then ONE files
        // query for owned OR legacy-granted OR granted — so a blob granted in both
        // shapes is listed once, and a grant row whose blob is gone lists nothing.
        // Filters.eq on the array field matches documents whose grants array
        // contains the value (Mongo semantics). Roots only.
        Set<String> grantedRefs = new LinkedHashSet<>();
        for (Document grant : grantsCollection.find(Filters.eq(GRANT_CONVERSATION_ID, conversationId))
                .projection(Projections.include(GRANT_STORAGE_REF))) {
            String ref = grant.getString(GRANT_STORAGE_REF);
            if (ref != null) {
                grantedRefs.add(ref);
            }
        }
        // A library document is listed through a grant only, and not while it is
        // soft-removed.
        List<Bson> clauses = new ArrayList<>(List.of(
                Filters.and(Filters.eq("metadata." + META_CONVERSATION_ID, conversationId), CONVERSATION_OWNED),
                Filters.eq("metadata." + META_GRANTS, conversationId)));
        if (!grantedRefs.isEmpty()) {
            clauses.add(Filters.in("metadata." + META_STORAGE_REF, grantedRefs));
            // A blob stored before storageRef existed is keyed by its ObjectId hex.
            List<ObjectId> legacyIds = grantedRefs.stream().filter(ObjectId::isValid).map(ObjectId::new).toList();
            if (!legacyIds.isEmpty()) {
                clauses.add(Filters.in("_id", legacyIds));
            }
        }
        return listMatching(Filters.and(Filters.or(clauses), ROOTS_ONLY, Filters.eq("metadata." + META_REMOVED_AT, null)));
    }

    private List<Attachment> listMatching(Bson filter) {
        List<Attachment> results = new ArrayList<>();
        for (GridFSFile file : gridFSBucket.find(filter)) {
            Document metadata = file.getMetadata();
            String ref = metadata != null && metadata.getString(META_STORAGE_REF) != null
                    ? metadata.getString(META_STORAGE_REF)
                    : file.getObjectId().toHexString();
            results.add(new Attachment(
                    ref,
                    file.getFilename(),
                    metadata != null && metadata.getString(META_MIME_TYPE) != null
                            ? metadata.getString(META_MIME_TYPE)
                            : "application/octet-stream",
                    sizeOf(file),
                    metadata != null ? metadata.getString(META_CONVERSATION_ID) : null));
        }
        return results;
    }

    /**
     * {count, totalBytes} of a bucket: the files whose {@code metadata.<field>} is
     * {@code value} plus the live reservations whose {@code <field>} is — the two
     * documents name the bucket fields alike. The count is of uploads (roots) only,
     * the bytes are the uploads' plus their media views' — the only derived bytes
     * that count toward an owner's quota. A reservation counts until it expires by
     * the database's clock ({@code $$NOW}).
     * <p>
     * The reservations are read <em>before</em> the files, and both from the
     * primary. A write against a reservation makes its blob visible before it
     * removes the reservation, so in this order a conversion that happens between
     * the two reads is seen at least once; the other order could see neither.
     * <p>
     * In the conversation's bucket the bytes of {@link QuotaMode#LARGE_PER_USER}
     * uploads and reservations do not count — they count in the user's — though
     * their files do.
     *
     * @param field
     *            {@link #META_CONVERSATION_ID} or {@link #META_USER_ID}
     */
    private long[] usage(String field, String value) {
        boolean largeExempt = META_CONVERSATION_ID.equals(field);
        long count = 0;
        long totalBytes = 0;
        Object reservedBytes = largeExempt
                ? new Document("$cond", List.of(new Document("$eq", List.of("$" + RESERVATION_QUOTA_MODE, QuotaMode.LARGE_PER_USER.name())), 0,
                        "$" + RESERVATION_BYTES))
                : "$" + RESERVATION_BYTES;
        for (Document sum : reservationsCollection.aggregate(List.of(
                Aggregates.match(Filters.and(Filters.eq(field, value), LIVE_RESERVATION)),
                Aggregates.group(null, Accumulators.sum(RESERVATION_COUNT, "$" + RESERVATION_COUNT),
                        Accumulators.sum(RESERVATION_BYTES, reservedBytes))))) {
            count += ((Number) sum.get(RESERVATION_COUNT)).longValue();
            totalBytes += ((Number) sum.get(RESERVATION_BYTES)).longValue();
        }
        // A library document and its views count in the library's bucket only.
        for (GridFSFile file : primaryReadBucket.find(Filters.and(Filters.eq("metadata." + field, value), CONVERSATION_OWNED))) {
            boolean bytesCount = !largeExempt || QuotaMode.fromStored(meta(file, META_QUOTA_MODE)) != QuotaMode.LARGE_PER_USER;
            if (meta(file, META_ROOT_REF) == null) {
                count++;
                totalBytes += bytesCount ? sizeOf(file) : 0;
            } else if (DerivedCaps.countsTowardOwnerQuota(kindOf(file))) {
                totalBytes += bytesCount ? sizeOf(file) : 0;
            }
        }
        return new long[]{count, totalBytes};
    }

    // ------------------------------------------------------------------ lineage

    @Override
    public Attachment storeDerived(byte[] bytes, String mimeType, String filename, Derivation derivation,
                                   String requestingConversationId)
            throws AttachmentStoreException {
        if (derivation == null) {
            throw new AttachmentStoreException("A derived blob needs its derivation");
        }
        if (bytes == null || bytes.length == 0) {
            throw new AttachmentStoreException("Attachment data is empty");
        }
        if (bytes.length > maxSizeBytes) {
            throw new AttachmentStoreException(
                    "Attachment exceeds max size: %d bytes (limit: %d)".formatted(bytes.length, maxSizeBytes));
        }
        if (!MimeValidator.isCompatibleContent(mimeType, bytes)) {
            throw new AttachmentStoreException(
                    "MIME type mismatch: declared='%s', detected='%s'".formatted(mimeType, MimeValidator.detectMime(bytes)));
        }
        String resolvedMime = MimeValidator.normalize(mimeType != null ? mimeType : MimeValidator.detectMime(bytes));
        String sha256 = DerivedBlobs.sha256Hex(bytes);
        // The owner is the root's at write time: when it changes between the read and
        // the lock (a promote), the write starts over with the new owner — its buckets
        // and locks — rather than failing.
        for (int attempt = 1;; attempt++) {
            try {
                return storeDerivedOnce(bytes, resolvedMime, filename, derivation, requestingConversationId, sha256);
            } catch (OwnerChangedException e) {
                if (attempt >= OWNER_CHANGE_ATTEMPTS) {
                    throw new AttachmentStoreException("The owner of this file changed while a view of it was being stored. Please retry.");
                }
            }
        }
    }

    /** A root's owner changed between reading it and locking it. */
    private static final class OwnerChangedException extends AttachmentStoreException {
        OwnerChangedException() {
            super("The owner of this file changed while a view of it was being stored");
        }
    }

    private Attachment storeDerivedOnce(byte[] bytes, String resolvedMime, String filename, Derivation derivation, String requestingConversationId,
                                        String sha256)
            throws AttachmentStoreException {
        Kind kind = derivation.kind();
        GridFSFile source = resolveSource(derivation, requestingConversationId);
        GridFSFile root = readableRootOf(source, requestingConversationId,
                derivation.parentRef() != null ? derivation.parentRef() : derivation.rootRef());
        String rootRef = canonicalRef(root);
        String parentRef = meta(source, META_ROOT_REF) != null ? canonicalRef(source) : null;
        String conversationId = meta(root, META_CONVERSATION_ID);
        String userId = meta(root, META_USER_ID);
        String ownerKey = meta(root, META_OWNER_KEY) != null ? meta(root, META_OWNER_KEY) : conversationId;
        OwnerScope scope = OwnerScope.fromStored(meta(root, META_OWNER_SCOPE));
        boolean library = scope != OwnerScope.CONVERSATION;

        boolean media = DerivedCaps.countsTowardOwnerQuota(kind);
        boolean conversationQuota = media && !library && maxTotalBytesPerConversation > 0;
        boolean userQuota = media && !library && userId != null && maxTotalBytesPerUser > 0;
        boolean libraryQuota = media && library && libraryMaxTotalBytesPerUser > 0;
        // Lock order: conversation → user → team → source, as every writer takes them;
        // a library's bucket locks in its owner's place.
        List<Lock> locks = bucketLocks(conversationQuota ? conversationId : null, userQuota ? userId : null);
        if (libraryQuota) {
            locks.add(LibraryStoreSupport.bucketLock(scope, ownerKey));
        }
        locks.add(new Lock(AttachmentLockOrder.Scope.SOURCE, rootRef));
        List<String[]> held = new ArrayList<>();
        ObjectId uploaded;
        try {
            lockAll(locks, held);
            // The root again, under the lock and from the primary: it may have been
            // deleted, or have changed owner, since it was read.
            GridFSFile current = primaryReadBucket.find(Filters.eq("_id", root.getObjectId())).first();
            if (current == null) {
                throw new AttachmentNotFoundException("Attachment not found: " + rootRef);
            }
            String currentKey = meta(current, META_OWNER_KEY) != null ? meta(current, META_OWNER_KEY) : meta(current, META_CONVERSATION_ID);
            if (!ownerKey.equals(currentKey) || !conversationId.equals(meta(current, META_CONVERSATION_ID))
                    || OwnerScope.fromStored(meta(current, META_OWNER_SCOPE)) != scope) {
                throw new OwnerChangedException();
            }
            GridFSFile duplicate = primaryReadBucket.find(Filters.and(Filters.eq("metadata." + META_OWNER_KEY, ownerKey),
                    Filters.eq("metadata." + META_ROOT_REF, rootRef), Filters.eq("metadata." + META_SHA256, sha256)))
                    .sort(Sorts.ascending("uploadDate", "_id")).first();
            if (duplicate != null) {
                return toAttachment(duplicate, canonicalRef(duplicate));
            }
            List<DerivedCaps.Existing> lineage = new ArrayList<>();
            for (GridFSFile existing : primaryReadBucket.find(Filters.eq("metadata." + META_ROOT_REF, rootRef))) {
                Document metadata = existing.getMetadata();
                Date accessed = metadata != null ? metadata.getDate(META_LAST_ACCESSED_AT) : null;
                lineage.add(new DerivedCaps.Existing(canonicalRef(existing), kindOf(existing), existing.getLength(),
                        accessed != null ? accessed.toInstant() : null));
            }
            List<String> evict = DerivedCaps.check(kind, bytes.length, lineage, derivedLimits());
            if (conversationQuota) {
                AttachmentQuotas.check(AttachmentQuotaExceededException.SCOPE_CONVERSATION, 0, usage(META_CONVERSATION_ID, conversationId)[1],
                        bytes.length, 0, maxTotalBytesPerConversation);
            }
            if (userQuota) {
                AttachmentQuotas.check(AttachmentQuotaExceededException.SCOPE_USER, 0, usage(META_USER_ID, userId)[1], bytes.length, 0,
                        maxTotalBytesPerUser);
            }
            if (libraryQuota) {
                LibraryStoreSupport.checkQuota(0, libraryUsage(scope, ownerKey)[1], 0, bytes.length, libraryLimits());
            }
            for (String ref : evict) {
                for (GridFSFile slice : primaryReadBucket.find(Filters.and(Filters.eq("metadata." + META_STORAGE_REF, ref),
                        Filters.eq("metadata." + META_ROOT_REF, rootRef)))) {
                    gridFSBucket.delete(slice.getObjectId());
                }
            }
            String storageRef = UUID.randomUUID().toString();
            Document metadata = new Document()
                    .append(META_CONVERSATION_ID, conversationId)
                    .append(META_TENANT_ID, meta(root, META_TENANT_ID))
                    .append(META_MIME_TYPE, resolvedMime)
                    .append("sizeBytes", (long) bytes.length)
                    .append(META_STORAGE_REF, storageRef)
                    .append(META_OWNER_SCOPE, OwnerScope.fromStored(meta(root, META_OWNER_SCOPE)).name())
                    .append(META_OWNER_KEY, ownerKey)
                    .append(META_ROOT_REF, rootRef)
                    .append(META_PARENT_REF, parentRef)
                    .append(META_KIND, kind.name())
                    .append(META_UNIT_ID, derivation.unitId())
                    .append(META_PAGE, derivation.page())
                    .append(META_SHA256, sha256)
                    .append(META_LAST_ACCESSED_AT, new Date());
            if (userId != null) {
                metadata.append(META_USER_ID, userId);
            }
            uploaded = gridFSBucket.uploadFromStream(filename != null ? filename : "unnamed", new ByteArrayInputStream(bytes),
                    new GridFSUploadOptions().metadata(metadata));
            // A root deleted while this upload ran (only a conversation's own delete takes
            // the source lock) must not leave an orphan view: take it back.
            if (primaryReadBucket.find(Filters.eq("_id", root.getObjectId())).first() == null) {
                gridFSBucket.delete(uploaded);
                throw new AttachmentNotFoundException("Attachment not found: " + rootRef);
            }
            DerivedBlobs.recordStored(meterRegistry, kind, bytes.length);
            LOGGER.debugf("Stored derived %s (%d bytes) of attachment %s", kind, bytes.length, rootRef);
            return new Attachment(storageRef, filename, resolvedMime, bytes.length, conversationId);
        } finally {
            releaseAll(held);
        }
    }

    /**
     * What the new view is taken from: its parent when one is named, else the named
     * root; either may itself be derived. A root and a parent of different lineages
     * are refused.
     */
    private GridFSFile resolveSource(Derivation derivation, String requester) throws AttachmentStoreException {
        String named = derivation.parentRef() != null ? derivation.parentRef() : derivation.rootRef();
        if (named == null) {
            throw new AttachmentStoreException("A derived blob needs its root");
        }
        GridFSFile source = primaryReadBucket.find(refFilter(named)).first();
        if (source == null) {
            throw new AttachmentNotFoundException("Attachment not found: " + named);
        }
        if (derivation.parentRef() != null && derivation.rootRef() != null) {
            GridFSFile parentRoot = readableRootOf(source, requester, named);
            GridFSFile named2 = primaryReadBucket.find(refFilter(derivation.rootRef())).first();
            if (named2 == null) {
                throw new AttachmentNotFoundException("Attachment not found: " + derivation.rootRef());
            }
            if (!canonicalRef(parentRoot).equals(canonicalRef(readableRootOf(named2, requester, derivation.rootRef())))) {
                throw new AttachmentStoreException("The parent of a derived blob belongs to another root");
            }
        }
        return source;
    }

    /**
     * The root of {@code file}'s lineage (the file itself for a root), if the
     * requester may read it; "not found" otherwise — a missing root, a missing blob
     * and a forbidden one are the same answer, so it is no oracle.
     */
    private GridFSFile readableRootOf(GridFSFile file, String requester, String askedFor) throws AttachmentStoreException {
        GridFSFile root = rootFileOf(file);
        if (root == null || !mayRead(root, requester)) {
            throw new AttachmentNotFoundException("Attachment not found: " + askedFor);
        }
        return root;
    }

    /** The root file of {@code file}'s lineage, or {@code null} if it is gone. */
    private GridFSFile rootFileOf(GridFSFile file) {
        String rootRef = meta(file, META_ROOT_REF);
        if (rootRef == null) {
            return file;
        }
        return primaryReadBucket.find(Filters.and(refFilter(rootRef), ROOTS_ONLY)).first();
    }

    private GridFSFile readable(String storageRef, String requester) throws AttachmentStoreException {
        GridFSFile file = findFileByRef(storageRef);
        if (file == null) {
            throw new AttachmentNotFoundException("Attachment not found: " + storageRef);
        }
        readableRootOf(file, requester, storageRef);
        return file;
    }

    /** The readable root of {@code storageRef}'s lineage. */
    private GridFSFile readableRoot(String storageRef, String requester) throws AttachmentStoreException {
        GridFSFile file = findFileByRef(storageRef);
        if (file == null) {
            throw new AttachmentNotFoundException("Attachment not found: " + storageRef);
        }
        return readableRootOf(file, requester, storageRef);
    }

    private DerivedCaps.Limits derivedLimits() {
        return new DerivedCaps.Limits(derivedMaxPerSource, derivedMaxTotalBytesPerSource, derivedMaxSlicesPerSource, derivedMaxSliceBytesPerSource,
                derivedMaxTextBytesPerSource);
    }

    @Override
    public Optional<Derivation> getDerivation(String storageRef, String requestingConversationId) throws AttachmentStoreException {
        GridFSFile file = readable(storageRef, requestingConversationId);
        String rootRef = meta(file, META_ROOT_REF);
        if (rootRef == null) {
            return Optional.empty();
        }
        Document metadata = file.getMetadata();
        return Optional.of(new Derivation(rootRef, metadata.getString(META_PARENT_REF), kindOf(file), metadata.getString(META_UNIT_ID),
                metadata.getInteger(META_PAGE), metadata.getString(META_SHA256)));
    }

    @Override
    public List<Attachment> listDerived(String rootRef, Kind kind, String requestingConversationId) throws AttachmentStoreException {
        GridFSFile root = readableRoot(rootRef, requestingConversationId);
        Bson filter = Filters.eq("metadata." + META_ROOT_REF, canonicalRef(root));
        if (kind != null) {
            filter = Filters.and(filter, Filters.eq("metadata." + META_KIND, kind.name()));
        }
        List<Attachment> results = new ArrayList<>();
        for (GridFSFile file : primaryReadBucket.find(filter).sort(Sorts.ascending("uploadDate", "_id"))) {
            results.add(toAttachment(file, canonicalRef(file)));
        }
        return results;
    }

    @Override
    public Optional<Attachment> findDerived(String rootRef, Kind kind, String unitId, String requestingConversationId)
            throws AttachmentStoreException {
        GridFSFile root = readableRoot(rootRef, requestingConversationId);
        GridFSFile match = primaryReadBucket.find(Filters.and(Filters.eq("metadata." + META_ROOT_REF, canonicalRef(root)),
                Filters.eq("metadata." + META_KIND, kind.name()), Filters.eq("metadata." + META_UNIT_ID, unitId)))
                .sort(Sorts.descending("uploadDate", "_id")).first();
        return match == null ? Optional.empty() : Optional.of(toAttachment(match, canonicalRef(match)));
    }

    @Override
    public String rootOf(String storageRef, String requestingConversationId) throws AttachmentStoreException {
        GridFSFile file = findFileByRef(storageRef);
        if (file == null) {
            throw new AttachmentNotFoundException("Attachment not found: " + storageRef);
        }
        return canonicalRef(readableRootOf(file, requestingConversationId, storageRef));
    }

    @Override
    public OwnerRef ownerOf(String storageRef, String requestingConversationId) throws AttachmentStoreException {
        GridFSFile root = readableRoot(storageRef, requestingConversationId);
        String conversationId = meta(root, META_CONVERSATION_ID);
        String ownerKey = meta(root, META_OWNER_KEY);
        return new OwnerRef(OwnerScope.fromStored(meta(root, META_OWNER_SCOPE)), ownerKey != null ? ownerKey : conversationId,
                meta(root, META_USER_ID));
    }

    @Override
    public Integer pageCount(String rootRef, String requestingConversationId) throws AttachmentStoreException {
        GridFSFile root = readableRoot(rootRef, requestingConversationId);
        Document metadata = root.getMetadata();
        return metadata != null ? metadata.getInteger(META_PAGE_COUNT) : null;
    }

    @Override
    public void setPageCount(String rootRef, int pages) {
        filesCollection.updateOne(Filters.and(refFilter(rootRef), ROOTS_ONLY), Updates.set("metadata." + META_PAGE_COUNT, pages));
    }

    private static String meta(GridFSFile file, String field) {
        Document metadata = file.getMetadata();
        return metadata != null ? metadata.getString(field) : null;
    }

    private static Kind kindOf(GridFSFile file) {
        return Kind.fromStored(meta(file, META_KIND));
    }

    // ------------------------------------------------------------------ library

    private LibraryStoreSupport.Limits libraryLimits() {
        return new LibraryStoreSupport.Limits(libraryMaxPerUser, libraryMaxTotalBytesPerUser, libraryRetentionDays, libraryMaxGrantsPerDocument);
    }

    /** The roots a library owns, soft-removed ones included. */
    private static Bson libraryRoots(String ownerKey) {
        return Filters.and(Filters.eq("metadata." + META_OWNER_KEY, ownerKey), LIBRARY_OWNED, ROOTS_ONLY);
    }

    /**
     * {count, totalBytes} of a library's bucket: its documents (soft-removed ones
     * included) and the bytes of them and their media views — two queries, each on
     * an index: the roots by owner key, their views by root.
     */
    private long[] libraryUsage(OwnerScope scope, String ownerKey) {
        long count = 0;
        long totalBytes = 0;
        List<String> refs = new ArrayList<>();
        for (GridFSFile root : primaryReadBucket
                .find(Filters.and(libraryRoots(ownerKey), Filters.eq("metadata." + META_OWNER_SCOPE, scope.name())))) {
            count++;
            totalBytes += sizeOf(root);
            refs.add(canonicalRef(root));
        }
        if (!refs.isEmpty()) {
            for (GridFSFile view : primaryReadBucket.find(Filters.and(Filters.in("metadata." + META_ROOT_REF, refs), MEDIA_KINDS))) {
                totalBytes += sizeOf(view);
            }
        }
        return new long[]{count, totalBytes};
    }

    /** The derived kinds whose bytes count toward the owner's byte quota. */
    private static final Bson MEDIA_KINDS = Filters.in("metadata." + META_KIND,
            Arrays.stream(Kind.values()).filter(DerivedCaps::countsTowardOwnerQuota).map(Kind::name).toList());

    /** The bytes of a root's media views, which move with it into a library. */
    private long mediaBytesOfLineage(String rootRef) {
        long bytes = 0;
        for (GridFSFile view : primaryReadBucket.find(Filters.and(Filters.eq("metadata." + META_ROOT_REF, rootRef), MEDIA_KINDS))) {
            bytes += sizeOf(view);
        }
        return bytes;
    }

    private boolean slugTaken(String ownerKey, String slug) {
        return primaryReadBucket.find(Filters.and(libraryRoots(ownerKey), Filters.eq("metadata." + META_LIBRARY_SLUG, slug))).first() != null;
    }

    private void rekeyLineage(String rootRef, OwnerRef newOwner) {
        filesCollection.updateMany(Filters.eq("metadata." + META_ROOT_REF, rootRef), Updates.combine(
                Updates.set("metadata." + META_OWNER_SCOPE, newOwner.scope().name()),
                Updates.set("metadata." + META_OWNER_KEY, newOwner.ownerKey())));
    }

    /**
     * Under the library's bucket lock and the root's source lock: no view of the
     * old owner can be stored once they are held, and a conversation's own
     * {@link #delete} waits for them. The lineage is re-keyed first and the root
     * last, so a node that stops in between leaves a root its conversation still
     * owns, whose cascade still reaches every view; promoting it again finishes the
     * move.
     */
    @Override
    public LibraryEntry promote(String storageRef, String owningConversationId, OwnerRef newOwner, LibraryMeta meta)
            throws AttachmentStoreException {
        LibraryStoreSupport.requireLibraryOwner(newOwner);
        if (meta == null) {
            throw new IllegalArgumentException("A library document needs its title and slug");
        }
        GridFSFile found = primaryReadBucket.find(refFilter(storageRef)).first();
        if (found == null) {
            throw new AttachmentNotFoundException("Attachment not found: " + storageRef);
        }
        if (meta(found, META_ROOT_REF) != null) {
            throw new AttachmentStoreException("Only an uploaded file can be put into a library, not a view of one");
        }
        String rootRef = canonicalRef(found);
        String oldOwnerKey;
        LibraryEntry entry;
        List<String[]> held = new ArrayList<>();
        try {
            lockAll(List.of(LibraryStoreSupport.bucketLock(newOwner.scope(), newOwner.ownerKey()),
                    new Lock(AttachmentLockOrder.Scope.SOURCE, rootRef)), held);
            GridFSFile root = primaryReadBucket.find(Filters.eq("_id", found.getObjectId())).first();
            if (root == null) {
                throw new AttachmentNotFoundException("Attachment not found: " + storageRef);
            }
            if (OwnerScope.fromStored(meta(root, META_OWNER_SCOPE)) != OwnerScope.CONVERSATION) {
                throw new AttachmentAccessDeniedException("This file is in a library already");
            }
            String conversationId = meta(root, META_CONVERSATION_ID);
            if (conversationId == null || !conversationId.equals(owningConversationId)) {
                throw new AttachmentAccessDeniedException("Only the conversation a file was uploaded to can put it into a library");
            }
            oldOwnerKey = meta(root, META_OWNER_KEY) != null ? meta(root, META_OWNER_KEY) : conversationId;
            long[] usage = libraryUsage(newOwner.scope(), newOwner.ownerKey());
            LibraryStoreSupport.checkQuota(usage[0], usage[1], 1, sizeOf(root) + mediaBytesOfLineage(rootRef), libraryLimits());
            String slug = LibraryStoreSupport.uniqueSlug(meta.slug(), candidate -> slugTaken(newOwner.ownerKey(), candidate));
            rekeyLineage(rootRef, newOwner);
            Date now = new Date();
            var moved = filesCollection.updateOne(Filters.and(Filters.eq("_id", root.getObjectId()), CONVERSATION_OWNED), Updates.combine(
                    Updates.set("metadata." + META_OWNER_SCOPE, newOwner.scope().name()),
                    Updates.set("metadata." + META_OWNER_KEY, newOwner.ownerKey()),
                    Updates.set("metadata." + META_LIBRARY_TITLE, meta.title()),
                    Updates.set("metadata." + META_LIBRARY_SLUG, slug),
                    Updates.set("metadata." + META_AGENT_SCOPE, meta.agentScope()),
                    Updates.set("metadata." + META_CONTRIBUTED_BY, newOwner.ownerUserId()),
                    Updates.set("metadata." + META_RETENTION_DAYS, meta.retentionDays()),
                    Updates.set("metadata." + META_SAVED_AT, now),
                    Updates.set("metadata." + META_LAST_ACCESSED_AT, now),
                    Updates.unset("metadata." + META_REMOVED_AT),
                    // The promoting conversation reads it through a grant from now on, in
                    // both shapes, like any grantee.
                    Updates.addToSet("metadata." + META_GRANTS, owningConversationId)));
            if (moved.getMatchedCount() == 0) {
                throw new AttachmentNotFoundException("Attachment not found: " + storageRef);
            }
            upsertGrant(rootRef, owningConversationId);
            GridFSFile promoted = primaryReadBucket.find(Filters.eq("_id", root.getObjectId())).first();
            if (promoted == null) {
                grantsCollection.deleteMany(Filters.eq(GRANT_STORAGE_REF, rootRef));
                throw new AttachmentNotFoundException("Attachment not found: " + storageRef);
            }
            evictGrants(promoted, rootRef, owningConversationId);
            entry = readEntry(newOwner.ownerKey(), rootRef)
                    .orElseThrow(() -> new AttachmentStoreException("A promoted library document could not be read back"));
        } finally {
            releaseAll(held);
        }
        DerivedBlobs.fireOwnerChanged(ownerChangedEvent, new AttachmentOwnerChanged(rootRef, oldOwnerKey, newOwner.ownerKey(), newOwner.scope()));
        LOGGER.debugf("Put attachment %s into a %s library", rootRef, newOwner.scope());
        return entry;
    }

    @Override
    public LibraryEntry moveOwner(String storageRef, String oldOwnerKey, OwnerRef newOwner) throws AttachmentStoreException {
        LibraryStoreSupport.requireLibraryOwner(newOwner);
        if (newOwner.ownerKey().equals(oldOwnerKey)) {
            throw new IllegalArgumentException("A document moves to another owner, not to its own");
        }
        GridFSFile found = primaryReadBucket.find(Filters.and(refFilter(storageRef), libraryRoots(oldOwnerKey))).first();
        if (found == null) {
            throw new AttachmentNotFoundException("Library document not found: " + storageRef);
        }
        String rootRef = canonicalRef(found);
        LibraryEntry entry;
        List<String[]> held = new ArrayList<>();
        try {
            lockAll(List.of(LibraryStoreSupport.bucketLock(newOwner.scope(), newOwner.ownerKey()),
                    new Lock(AttachmentLockOrder.Scope.SOURCE, rootRef)), held);
            GridFSFile root = primaryReadBucket.find(Filters.and(Filters.eq("_id", found.getObjectId()), libraryRoots(oldOwnerKey))).first();
            if (root == null) {
                throw new AttachmentNotFoundException("Library document not found: " + storageRef);
            }
            long[] usage = libraryUsage(newOwner.scope(), newOwner.ownerKey());
            LibraryStoreSupport.checkQuota(usage[0], usage[1], 1, sizeOf(root) + mediaBytesOfLineage(rootRef), libraryLimits());
            String slug = LibraryStoreSupport.uniqueSlug(meta(root, META_LIBRARY_SLUG), candidate -> slugTaken(newOwner.ownerKey(), candidate));
            rekeyLineage(rootRef, newOwner);
            var moved = filesCollection.updateOne(Filters.and(Filters.eq("_id", root.getObjectId()), libraryRoots(oldOwnerKey)), Updates.combine(
                    Updates.set("metadata." + META_OWNER_SCOPE, newOwner.scope().name()),
                    Updates.set("metadata." + META_OWNER_KEY, newOwner.ownerKey()),
                    Updates.set("metadata." + META_LIBRARY_SLUG, slug),
                    Updates.set("metadata." + META_SAVED_AT, new Date())));
            if (moved.getMatchedCount() == 0) {
                throw new AttachmentNotFoundException("Library document not found: " + storageRef);
            }
            entry = readEntry(newOwner.ownerKey(), rootRef)
                    .orElseThrow(() -> new AttachmentStoreException("A moved library document could not be read back"));
        } finally {
            releaseAll(held);
        }
        DerivedBlobs.fireOwnerChanged(ownerChangedEvent, new AttachmentOwnerChanged(rootRef, oldOwnerKey, newOwner.ownerKey(), newOwner.scope()));
        return entry;
    }

    private Optional<LibraryEntry> readEntry(String ownerKey, String storageRef) {
        GridFSFile file = primaryReadBucket.find(Filters.and(refFilter(storageRef), libraryRoots(ownerKey))).first();
        return file != null ? Optional.of(entry(file)) : Optional.empty();
    }

    private static LibraryEntry entry(GridFSFile file) {
        Document metadata = file.getMetadata() != null ? file.getMetadata() : new Document();
        return new LibraryEntry(canonicalRef(file), metadata.getString(META_LIBRARY_TITLE), metadata.getString(META_LIBRARY_SLUG),
                OwnerScope.fromStored(metadata.getString(META_OWNER_SCOPE)), metadata.getString(META_OWNER_KEY), metadata.getString(META_AGENT_SCOPE),
                metadata.getString(META_CONTRIBUTED_BY), file.getFilename(),
                metadata.getString(META_MIME_TYPE) != null ? metadata.getString(META_MIME_TYPE) : "application/octet-stream", sizeOf(file),
                metadata.getInteger(META_PAGE_COUNT), instant(metadata, META_SAVED_AT), instant(metadata, META_LAST_ACCESSED_AT),
                instant(metadata, META_REMOVED_AT), metadata.getInteger(META_RETENTION_DAYS));
    }

    private static Instant instant(Document metadata, String field) {
        Date value = metadata.getDate(field);
        return value != null ? value.toInstant() : null;
    }

    private List<LibraryEntry> entries(Bson filter, Bson sort, int offset, int limit) {
        List<LibraryEntry> entries = new ArrayList<>();
        for (GridFSFile file : primaryReadBucket.find(filter).sort(sort).skip(LibraryStoreSupport.pageOffset(offset))
                .limit(LibraryStoreSupport.pageLimit(limit))) {
            entries.add(entry(file));
        }
        return entries;
    }

    @Override
    public List<LibraryEntry> listByOwner(String ownerKey, String agentScopeOrNull, boolean includeRemoved, int offset, int limit) {
        if (ownerKey == null) {
            return List.of();
        }
        List<Bson> filters = new ArrayList<>(List.of(libraryRoots(ownerKey)));
        if (agentScopeOrNull != null) {
            filters.add(Filters.or(Filters.eq("metadata." + META_AGENT_SCOPE, agentScopeOrNull), Filters.eq("metadata." + META_AGENT_SCOPE, null)));
        }
        if (!includeRemoved) {
            filters.add(Filters.eq("metadata." + META_REMOVED_AT, null));
        }
        return entries(Filters.and(filters), Sorts.orderBy(Sorts.descending("metadata." + META_LAST_ACCESSED_AT),
                Sorts.ascending("metadata." + META_STORAGE_REF)), offset, limit);
    }

    @Override
    public Optional<LibraryEntry> getLibraryEntry(String ownerKey, String storageRef) {
        if (ownerKey == null || storageRef == null) {
            return Optional.empty();
        }
        return readEntry(ownerKey, storageRef);
    }

    @Override
    public void touchAccess(String storageRef) {
        filesCollection.updateOne(Filters.and(refFilter(storageRef), LIBRARY_OWNED, ROOTS_ONLY),
                Updates.set("metadata." + META_LAST_ACCESSED_AT, new Date()));
    }

    @Override
    public boolean softRemove(String ownerKey, String storageRef, Instant when) {
        if (when == null) {
            throw new IllegalArgumentException("A soft removal needs its time");
        }
        if (ownerKey == null) {
            return false;
        }
        return filesCollection.updateOne(Filters.and(refFilter(storageRef), libraryRoots(ownerKey), Filters.eq("metadata." + META_REMOVED_AT, null)),
                Updates.set("metadata." + META_REMOVED_AT, Date.from(when))).getModifiedCount() > 0;
    }

    @Override
    public boolean restore(String ownerKey, String storageRef) {
        if (ownerKey == null) {
            return false;
        }
        return filesCollection.updateOne(Filters.and(refFilter(storageRef), libraryRoots(ownerKey), Filters.ne("metadata." + META_REMOVED_AT, null)),
                Updates.unset("metadata." + META_REMOVED_AT)).getModifiedCount() > 0;
    }

    @Override
    public long deleteByOwner(String ownerKey) {
        return deleteLibraryRoots(ownerKey, null);
    }

    @Override
    public boolean deleteLibraryDocument(String ownerKey, String storageRef) {
        return storageRef != null && deleteLibraryRoots(ownerKey, storageRef) > 0;
    }

    /**
     * Deletes the library roots of {@code ownerKey} — one, or all of them with
     * {@code storageRef} {@code null} (and then the owner's reservations too) —
     * with their lineage and the grants on them, then their located bytes and one
     * announcement per root. A view stored while this runs is taken back by its
     * writer, which checks its root after the upload.
     */
    private long deleteLibraryRoots(String ownerKey, String storageRef) {
        if (ownerKey == null) {
            return 0;
        }
        if (storageRef == null) {
            // The library's own reservations; a conversation's (whose owner key is its
            // id) are never the library's, whatever key was passed.
            reservationsCollection.deleteMany(Filters.and(Filters.eq(RESERVATION_OWNER_KEY, ownerKey), Filters.ne(META_CONVERSATION_ID, ownerKey)));
        }
        Bson filter = storageRef != null ? Filters.and(refFilter(storageRef), libraryRoots(ownerKey)) : libraryRoots(ownerKey);
        List<AttachmentRootDeleted> roots = new ArrayList<>();
        List<String> refs = new ArrayList<>();
        List<Map.Entry<BlobLocator, String>> retired = new ArrayList<>();
        for (GridFSFile root : primaryReadBucket.find(filter)) {
            String ref = canonicalRef(root);
            deleteFile(root, retired);
            refs.add(ref);
            roots.add(rootDeleted(root, ref));
        }
        if (!refs.isEmpty()) {
            for (GridFSFile derived : primaryReadBucket.find(Filters.in("metadata." + META_ROOT_REF, refs))) {
                deleteFile(derived, retired);
            }
            grantsCollection.deleteMany(Filters.in(GRANT_STORAGE_REF, refs));
            LOGGER.debugf("Deleted %d library documents", refs.size());
        }
        discardAll(retired);
        DerivedBlobs.fireRootDeleted(rootDeletedEvent, roots);
        return roots.size();
    }

    @Override
    public List<LibraryEntry> expired(Instant now, int limit) {
        if (now == null) {
            throw new IllegalArgumentException("Expiry is decided at a given time");
        }
        // Retention counts from the last access; a document without its own retention
        // has the deployment's, and zero (its own or the default) never runs out. The
        // arithmetic runs on the server, so no document is read to decide.
        Document retention = new Document("$ifNull", List.of("$metadata." + META_RETENTION_DAYS, Math.max(0, libraryRetentionDays)));
        Document expiresAt = new Document("$add",
                List.of("$metadata." + META_LAST_ACCESSED_AT, new Document("$multiply", List.of(retention, 86_400_000L))));
        Bson due = Filters.expr(new Document("$and", List.of(new Document("$gt", List.of(retention, 0)),
                new Document("$lte", List.of(expiresAt, Date.from(now))))));
        return entries(Filters.and(LIBRARY_OWNED, ROOTS_ONLY, Filters.exists("metadata." + META_SAVED_AT), due),
                Sorts.orderBy(Sorts.ascending("metadata." + META_LAST_ACCESSED_AT), Sorts.ascending("metadata." + META_STORAGE_REF)), 0, limit);
    }

    @Override
    public List<LibraryEntry> removedBefore(Instant cutoff, int limit) {
        if (cutoff == null) {
            throw new IllegalArgumentException("A purge needs its cutoff");
        }
        return entries(Filters.and(LIBRARY_OWNED, ROOTS_ONLY, Filters.lt("metadata." + META_REMOVED_AT, Date.from(cutoff))),
                Sorts.orderBy(Sorts.ascending("metadata." + META_REMOVED_AT), Sorts.ascending("metadata." + META_STORAGE_REF)), 0, limit);
    }

    // ------------------------------------------------------- quota reservations

    @Override
    public QuotaReservation reserve(
                                    OwnerRef owner, String conversationId, String userId, long bytes, int count, Duration ttl,
                                    QuotaMode mode)
            throws AttachmentStoreException {
        QuotaReservation.validateRequest(owner, conversationId, userId, bytes, count, ttl, mode);
        // A large upload's bytes are the user's to carry, not the conversation's.
        long conversationBytes = mode == QuotaMode.LARGE_PER_USER ? 0 : bytes;
        boolean conversationQuota = maxPerConversation > 0 || maxTotalBytesPerConversation > 0;
        boolean userQuota = userId != null && (maxPerUser > 0 || maxTotalBytesPerUser > 0);
        String id = UUID.randomUUID().toString();
        List<String[]> held = new ArrayList<>();
        try {
            lockAll(bucketLocks(conversationQuota ? conversationId : null, userQuota ? userId : null), held);
            // Every reserve sweeps, whether quotas are on or not; the TTL index is only
            // the backstop, as its monitor runs once a minute. Expired reservations no
            // longer count, so this keeps the collection small, not the quota right.
            reservationsCollection.deleteMany(EXPIRED_RESERVATION);
            if (conversationQuota) {
                long[] usage = usage(META_CONVERSATION_ID, conversationId);
                AttachmentQuotas.check(AttachmentQuotaExceededException.SCOPE_CONVERSATION, usage[0], usage[1], count, conversationBytes,
                        maxPerConversation, maxTotalBytesPerConversation);
            }
            if (userQuota) {
                long[] usage = usage(META_USER_ID, userId);
                AttachmentQuotas.check(AttachmentQuotaExceededException.SCOPE_USER, usage[0], usage[1], count, bytes, maxPerUser,
                        maxTotalBytesPerUser);
            }
            // The expiry from the server's clock ($$NOW), in a pipeline update: no node's
            // clock decides how long a reservation counts. Values go in as $literal, so
            // nothing a caller passes is read as a field path.
            Document fields = new Document(RESERVATION_OWNER_KEY, literal(owner.ownerKey()))
                    .append(META_CONVERSATION_ID, literal(conversationId))
                    .append(META_USER_ID, literal(userId))
                    .append(RESERVATION_BYTES, literal(bytes))
                    .append(RESERVATION_COUNT, literal(count))
                    .append(RESERVATION_QUOTA_MODE, literal(mode.stored()))
                    .append(RESERVATION_CREATED_AT, "$$NOW")
                    .append(RESERVATION_EXPIRES_AT, new Document("$add", List.of("$$NOW", ttl.toMillis())));
            reservationsCollection.updateOne(Filters.eq("_id", id), List.of(new Document("$set", fields)), new UpdateOptions().upsert(true));
            Document stored = reservationsCollection.find(Filters.eq("_id", id)).first();
            if (stored == null) {
                throw new AttachmentStoreException("Failed to reserve attachment quota");
            }
            return new QuotaReservation(id, owner.ownerKey(), bytes, count, stored.getDate(RESERVATION_EXPIRES_AT).toInstant());
        } finally {
            releaseAll(held);
        }
    }

    private static Document literal(Object value) {
        return new Document("$literal", value);
    }

    @Override
    public void release(String reservationId) {
        reservationsCollection.deleteOne(Filters.eq("_id", reservationId));
    }

    /**
     * Under the reservation's bucket locks (conversation → user): the blob must be
     * visible in its buckets and fit, then the reservation goes. The blob is never
     * removed here; a refused conversion leaves it to its writer.
     */
    @Override
    public void convert(String reservationId, String storageRef) throws AttachmentStoreException {
        Document reservation = reservationsCollection.find(Filters.eq("_id", reservationId)).first();
        if (reservation == null) {
            throw reservationGone();
        }
        String conversationId = reservation.getString(META_CONVERSATION_ID);
        String userId = reservation.getString(META_USER_ID);
        List<String[]> held = new ArrayList<>();
        try {
            lockAll(bucketLocks(conversationId, userId), held);
            Document live = reservationsCollection.find(Filters.and(Filters.eq("_id", reservationId), LIVE_RESERVATION)).first();
            if (live == null) {
                throw reservationGone();
            }
            GridFSFile blob = primaryReadBucket.find(Filters.and(refFilter(storageRef), ROOTS_ONLY,
                    Filters.eq("metadata." + META_CONVERSATION_ID, conversationId), Filters.eq("metadata." + META_USER_ID, userId))).first();
            if (blob == null) {
                throw new AttachmentNotFoundException("No upload " + storageRef + " in the buckets of this quota reservation");
            }
            long reserved = ((Number) live.get(RESERVATION_BYTES)).longValue();
            if (sizeOf(blob) > reserved) {
                throw new AttachmentQuotaExceededException(AttachmentQuotaExceededException.SCOPE_RESERVATION,
                        "The upload (%d bytes) is larger than the %d bytes reserved for it".formatted(sizeOf(blob), reserved));
            }
            reservationsCollection.deleteOne(Filters.eq("_id", reservationId));
        } finally {
            releaseAll(held);
        }
    }

    private static AttachmentQuotaExceededException reservationGone() {
        return new AttachmentQuotaExceededException(AttachmentQuotaExceededException.SCOPE_RESERVATION,
                "The quota reservation for this upload expired or was released before the upload completed. Please retry.");
    }

    /** The bucket locks of a write; a {@code null} id is a bucket not locked. */
    private static List<Lock> bucketLocks(String conversationId, String userId) {
        List<Lock> locks = new ArrayList<>();
        locks.add(new Lock(AttachmentLockOrder.Scope.CONVERSATION, conversationId));
        locks.add(new Lock(AttachmentLockOrder.Scope.USER, userId));
        return locks;
    }

    /**
     * Takes the lease locks {@code wanted} needs, in the one order every writer
     * uses ({@link AttachmentLockOrder}), recording each in {@code held} for
     * {@link #releaseAll}. The keys are the ones this store has always used, so a
     * node of an older release serializes on the same locks.
     */
    private void lockAll(List<Lock> wanted, List<String[]> held) throws AttachmentStoreException {
        for (Lock lock : AttachmentLockOrder.ordered(wanted)) {
            String key = lockKey(lock);
            held.add(new String[]{key, acquireLock(key)});
        }
    }

    static String lockKey(Lock lock) {
        return lock.scope() == AttachmentLockOrder.Scope.SOURCE
                ? SOURCE_LOCK_KEY_PREFIX + lock.id()
                : lock.scope().name().toLowerCase(Locale.ROOT) + ":" + lock.id();
    }

    /**
     * Take the quota lock {@code key}, waiting up to {@link #LOCK_WAIT_MILLIS}.
     *
     * @return the holder token that releases it
     */
    String acquireLock(String key) throws AttachmentStoreException {
        String token = UUID.randomUUID().toString();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(LOCK_WAIT_MILLIS);
        while (true) {
            long now = System.currentTimeMillis();
            try {
                locksCollection.insertOne(new Document("_id", key).append("owner", token)
                        .append("expiresAt", new Date(now + LOCK_LEASE_MILLIS)));
                return token;
            } catch (MongoWriteException e) {
                if (e.getError().getCategory() != ErrorCategory.DUPLICATE_KEY) {
                    throw new AttachmentStoreException("Could not take the attachment quota lock", e);
                }
            }
            // Held. Take it over only if its lease has run out — its holder died.
            Document stale = locksCollection.findOneAndUpdate(
                    Filters.and(Filters.eq("_id", key), Filters.lt("expiresAt", new Date(now))),
                    Updates.combine(Updates.set("owner", token), Updates.set("expiresAt", new Date(now + LOCK_LEASE_MILLIS))));
            if (stale != null) {
                LOGGER.warnf("Took over an expired attachment quota lock for %s", sanitize(key));
                return token;
            }
            if (System.nanoTime() > deadline) {
                throw new AttachmentStoreException("Too many concurrent uploads for this " + key.substring(0, key.indexOf(':'))
                        + ". Please retry.");
            }
            try {
                Thread.sleep(LOCK_POLL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AttachmentStoreException("Interrupted while waiting for the attachment quota lock", e);
            }
        }
    }

    /**
     * Release a lock this holder still owns; a lease taken over by another is left
     * alone.
     */
    void releaseLock(String key, String token) {
        try {
            locksCollection.deleteOne(Filters.and(Filters.eq("_id", key), Filters.eq("owner", token)));
        } catch (RuntimeException e) {
            // Its lease expires on its own; the next upload takes it over then.
            LOGGER.warnf("Could not release the attachment quota lock for %s: %s", sanitize(key), sanitize(e.getMessage()));
        }
    }

    private GridFSFile findFileByRef(String storageRef) {
        return gridFSBucket.find(refFilter(storageRef)).first();
    }

    private Bson refFilter(String storageRef) {
        if (ObjectId.isValid(storageRef)) {
            // Match the modern UUID metadata ref or a legacy plain-ObjectId ref.
            return Filters.or(
                    Filters.eq("metadata." + META_STORAGE_REF, storageRef),
                    Filters.eq("_id", new ObjectId(storageRef)));
        }
        return Filters.eq("metadata." + META_STORAGE_REF, storageRef);
    }

    /**
     * The reference grant rows are keyed by: {@code metadata.storageRef}, or the
     * ObjectId hex of a blob stored before it existed.
     */
    private static String canonicalRef(GridFSFile file) {
        Document metadata = file.getMetadata();
        String ref = metadata != null ? metadata.getString(META_STORAGE_REF) : null;
        if (ref != null) {
            return ref;
        }
        return file.getObjectId() != null ? file.getObjectId().toHexString() : null;
    }

    /**
     * Owner-or-grant on the root. A stranger asking for a root is denied (the
     * established answer); one asking for a derived blob — or for a blob whose root
     * is gone — is told it does not exist, so a view's reference is no oracle.
     */
    private void authorize(GridFSFile file, String storageRef, String requester) throws AttachmentStoreException {
        if (meta(file, META_ROOT_REF) != null) {
            readableRootOf(file, requester, storageRef);
            return;
        }
        if (mayRead(file, requester)) {
            return;
        }
        if (OwnerScope.fromStored(meta(file, META_OWNER_SCOPE)) != OwnerScope.CONVERSATION) {
            throw new AttachmentAccessDeniedException("Access denied: this library document has not been opened in this conversation");
        }
        throw new AttachmentAccessDeniedException(

                "Cross-conversation access denied: attachment belongs to '%s', requested from '%s'"
                        .formatted(meta(file, META_CONVERSATION_ID), requester));
    }

    /** Owner or grantee of a root file, in either grant shape. */
    private boolean mayRead(GridFSFile root, String requester) {
        Document metadata = root.getMetadata();
        // Fails closed, as the PostgreSQL store does: a blob with no recorded owner
        // (a pre-ownership legacy blob) used to be readable by any conversation that
        // knew its reference. Ownership that cannot be established is not ownership.
        // And only a conversation-owned root is its conversation's own: a library
        // document is read through a grant, even by the conversation it came from.
        String owner = metadata != null ? metadata.getString(META_CONVERSATION_ID) : null;
        if (owner != null && owner.equals(requester) && OwnerScope.fromStored(meta(root, META_OWNER_SCOPE)) == OwnerScope.CONVERSATION) {
            return true;
        }
        List<String> grants = metadata != null ? metadata.getList(META_GRANTS, String.class) : null;
        if (grants != null && grants.contains(requester)) {
            return true;
        }
        String ref = canonicalRef(root);
        return requester != null && ref != null && grantsCollection.countDocuments(
                Filters.and(Filters.eq(GRANT_STORAGE_REF, ref), Filters.eq(GRANT_CONVERSATION_ID, requester))) > 0;
    }
}
