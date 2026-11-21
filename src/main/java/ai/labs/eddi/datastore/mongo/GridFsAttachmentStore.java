/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.mongo;

import ai.labs.eddi.engine.attachments.AttachmentQuotas;
import ai.labs.eddi.engine.attachments.AttachmentRootDeleted;
import ai.labs.eddi.engine.attachments.Derivation;
import ai.labs.eddi.engine.attachments.Derivation.Kind;
import ai.labs.eddi.engine.attachments.DerivedBlobs;
import ai.labs.eddi.engine.attachments.DerivedCaps;
import ai.labs.eddi.engine.attachments.IAttachmentGrantMigration;
import ai.labs.eddi.engine.attachments.IAttachmentStore;
import ai.labs.eddi.engine.attachments.MimeValidator;
import ai.labs.eddi.engine.attachments.OwnerScope;
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
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
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
    static final String LOCKS_COLLECTION = BUCKET_NAME + ".quota_locks";
    static final String GRANTS_COLLECTION = "attachment_grants";
    static final String GRANT_STORAGE_REF = "storageRef";
    static final String GRANT_CONVERSATION_ID = "conversationId";
    static final String GRANT_CREATED_AT = "createdAt";
    static final String BACKFILL_COLLECTION = "attachment_grants_backfill";
    private static final String BACKFILL_CURSOR = "cursor";
    /**
     * How long a quota lock is held at most before another upload may take it over.
     */
    static final long LOCK_LEASE_MILLIS = 60_000;
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

    /** Announces deleted roots; {@code null} outside CDI (tests). */
    @Inject
    Event<AttachmentRootDeleted> rootDeletedEvent;

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
        List<String> lockKeys = new ArrayList<>();
        if (conversationQuota) {
            lockKeys.add("conversation:" + conversationId);
        }
        if (userQuota) {
            lockKeys.add("user:" + userId);
        }
        List<String[]> held = new ArrayList<>();
        try {
            for (String key : lockKeys) {
                held.add(new String[]{key, acquireLock(key)});
            }
            if (conversationQuota) {
                AttachmentQuotas.checkUsage(AttachmentQuotaExceededException.SCOPE_CONVERSATION,
                        usage(Filters.eq("metadata." + META_CONVERSATION_ID, conversationId)), bytes.length,
                        maxPerConversation, maxTotalBytesPerConversation);
            }
            if (userQuota) {
                AttachmentQuotas.checkUsage(AttachmentQuotaExceededException.SCOPE_USER, usage(Filters.eq("metadata." + META_USER_ID, userId)),
                        bytes.length, maxPerUser, maxTotalBytesPerUser);
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

    @Override
    public byte[] load(String storageRef, String requestingConversationId) throws AttachmentStoreException {
        GridFSFile file = findFileByRef(storageRef);
        if (file == null) {
            throw new AttachmentNotFoundException("Attachment not found: " + storageRef);
        }
        authorize(file, storageRef, requestingConversationId);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        gridFSBucket.downloadToStream(file.getObjectId(), out);
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
                mime != null ? mime : "application/octet-stream", file.getLength(), owner);
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
        if (primaryReadBucket.find(Filters.eq("_id", file.getObjectId())).first() == null) {
            grantsCollection.deleteMany(Filters.eq(GRANT_STORAGE_REF, canonicalRef));
            throw new AttachmentNotFoundException("Attachment not found: " + storageRef);
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

    @Override
    public boolean delete(String storageRef, String requestingConversationId) throws AttachmentStoreException {
        GridFSFile file = findFileByRef(storageRef);
        if (file == null) {
            return false;
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
        gridFSBucket.delete(file.getObjectId());
        grantsCollection.deleteMany(Filters.eq(GRANT_STORAGE_REF, canonicalRef));
        if (meta(file, META_ROOT_REF) == null) {
            // The root's lineage: every view of it, whatever stored it.
            for (GridFSFile derived : primaryReadBucket.find(Filters.eq("metadata." + META_ROOT_REF, canonicalRef))) {
                gridFSBucket.delete(derived.getObjectId());
            }
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
        for (GridFSFile file : primaryReadBucket.find(Filters.eq("metadata." + META_CONVERSATION_ID, conversationId))) {
            gridFSBucket.delete(file.getObjectId());
            String ref = canonicalRef(file);
            deletedRefs.add(ref);
            if (meta(file, META_ROOT_REF) == null) {
                roots.add(rootDeleted(file, ref));
            }
        }
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
        return listMatching(Filters.and(Filters.eq("metadata." + META_CONVERSATION_ID, conversationId), ROOTS_ONLY));
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
        List<Bson> clauses = new ArrayList<>(List.of(
                Filters.eq("metadata." + META_CONVERSATION_ID, conversationId),
                Filters.eq("metadata." + META_GRANTS, conversationId)));
        if (!grantedRefs.isEmpty()) {
            clauses.add(Filters.in("metadata." + META_STORAGE_REF, grantedRefs));
            // A blob stored before storageRef existed is keyed by its ObjectId hex.
            List<ObjectId> legacyIds = grantedRefs.stream().filter(ObjectId::isValid).map(ObjectId::new).toList();
            if (!legacyIds.isEmpty()) {
                clauses.add(Filters.in("_id", legacyIds));
            }
        }
        return listMatching(Filters.and(Filters.or(clauses), ROOTS_ONLY));
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
                    file.getLength(),
                    metadata != null ? metadata.getString(META_CONVERSATION_ID) : null));
        }
        return results;
    }

    /**
     * {count, totalBytes} of the files matching {@code filter}: the count is of
     * uploads (roots) only, the bytes are the uploads' plus their media views' —
     * the only derived bytes that count toward an owner's quota.
     */
    private long[] usage(Bson filter) {
        long count = 0;
        long totalBytes = 0;
        for (GridFSFile file : primaryReadBucket.find(filter)) {
            if (meta(file, META_ROOT_REF) == null) {
                count++;
                totalBytes += file.getLength();
            } else if (DerivedCaps.countsTowardOwnerQuota(kindOf(file))) {
                totalBytes += file.getLength();
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
        Kind kind = derivation.kind();

        GridFSFile source = resolveSource(derivation, requestingConversationId);
        GridFSFile root = readableRootOf(source, requestingConversationId,
                derivation.parentRef() != null ? derivation.parentRef() : derivation.rootRef());
        String rootRef = canonicalRef(root);
        String parentRef = meta(source, META_ROOT_REF) != null ? canonicalRef(source) : null;
        String conversationId = meta(root, META_CONVERSATION_ID);
        String userId = meta(root, META_USER_ID);
        String ownerKey = meta(root, META_OWNER_KEY) != null ? meta(root, META_OWNER_KEY) : conversationId;

        boolean media = DerivedCaps.countsTowardOwnerQuota(kind);
        boolean conversationQuota = media && maxTotalBytesPerConversation > 0;
        boolean userQuota = media && userId != null && maxTotalBytesPerUser > 0;
        // Lock order: conversation → user → source, as every writer takes them.
        List<String> lockKeys = new ArrayList<>();
        if (conversationQuota) {
            lockKeys.add("conversation:" + conversationId);
        }
        if (userQuota) {
            lockKeys.add("user:" + userId);
        }
        lockKeys.add("source:" + rootRef);
        List<String[]> held = new ArrayList<>();
        ObjectId uploaded;
        try {
            for (String key : lockKeys) {
                held.add(new String[]{key, acquireLock(key)});
            }
            // The root again, under the lock and from the primary: it may have been
            // deleted, or have changed owner, since it was read.
            GridFSFile current = primaryReadBucket.find(Filters.eq("_id", root.getObjectId())).first();
            if (current == null) {
                throw new AttachmentNotFoundException("Attachment not found: " + rootRef);
            }
            String currentKey = meta(current, META_OWNER_KEY) != null ? meta(current, META_OWNER_KEY) : meta(current, META_CONVERSATION_ID);
            if (!ownerKey.equals(currentKey) || !conversationId.equals(meta(current, META_CONVERSATION_ID))) {
                throw new AttachmentStoreException("The owner of this file changed while a view of it was being stored. Please retry.");
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
                AttachmentQuotas.check(AttachmentQuotaExceededException.SCOPE_CONVERSATION, 0,
                        usage(Filters.eq("metadata." + META_CONVERSATION_ID, conversationId))[1], bytes.length, 0, maxTotalBytesPerConversation);
            }
            if (userQuota) {
                AttachmentQuotas.check(AttachmentQuotaExceededException.SCOPE_USER, 0, usage(Filters.eq("metadata." + META_USER_ID, userId))[1],
                        bytes.length, 0, maxTotalBytesPerUser);
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
            // A root deleted while this upload ran (a delete takes no source lock)
            // must not leave an orphan view: take it back.
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
        String owner = metadata != null ? metadata.getString(META_CONVERSATION_ID) : null;
        if (owner != null && owner.equals(requester)) {
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
