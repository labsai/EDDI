/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.mongo;

import ai.labs.eddi.engine.attachments.AttachmentQuotas;
import ai.labs.eddi.engine.attachments.IAttachmentGrantMigration;
import ai.labs.eddi.engine.attachments.IAttachmentStore;
import ai.labs.eddi.engine.attachments.MimeValidator;
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
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
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
 * <b>Quotas.</b> A GridFS upload is not one atomic write, so the quota check
 * and the upload run under a lease lock per quota scope — a document in
 * {@code attachments.quota_locks} keyed by conversation or user, inserted to
 * take the lock (a duplicate key means it is held) and deleted to release it. A
 * lock whose holder died is taken over once its lease has expired. The usage
 * itself is always counted from the blobs, so nothing can drift.
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
                .append("tenantId", tenantId)
                .append(META_MIME_TYPE, resolvedMime)
                .append("sizeBytes", (long) bytes.length)
                .append(META_STORAGE_REF, storageRef)
                .append(META_GRANTS, new ArrayList<String>());
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
            for (int i = held.size() - 1; i >= 0; i--) {
                releaseLock(held.get(i)[0], held.get(i)[1]);
            }
        }

        LOGGER.debugf("Stored attachment '%s' (%s, %d bytes) for conversation '%s' → GridFS %s",
                sanitize(filename), resolvedMime, bytes.length, sanitize(conversationId), storageRef);

        return new Attachment(storageRef, filename, resolvedMime, bytes.length, conversationId);
    }

    @Override
    public byte[] load(String storageRef, String requestingConversationId) throws AttachmentStoreException {
        GridFSFile file = findFileByRef(storageRef);
        if (file == null) {
            throw new AttachmentNotFoundException("Attachment not found: " + storageRef);
        }
        authorize(file, requestingConversationId);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        gridFSBucket.downloadToStream(file.getObjectId(), out);
        return out.toByteArray();
    }

    @Override
    public Attachment getMetadata(String storageRef, String requestingConversationId) throws AttachmentStoreException {
        GridFSFile file = findFileByRef(storageRef);
        if (file == null) {
            throw new AttachmentNotFoundException("Attachment not found: " + storageRef);
        }
        authorize(file, requestingConversationId);

        Document metadata = file.getMetadata();
        String mime = metadata != null ? metadata.getString(META_MIME_TYPE) : null;
        String owner = metadata != null ? metadata.getString(META_CONVERSATION_ID) : null;
        String ref = metadata != null && metadata.getString(META_STORAGE_REF) != null
                ? metadata.getString(META_STORAGE_REF)
                : storageRef;
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
        gridFSBucket.delete(file.getObjectId());
        grantsCollection.deleteMany(Filters.eq(GRANT_STORAGE_REF, canonicalRef(file)));
        return true;
    }

    @Override
    public long deleteByConversation(String conversationId) {
        long count = 0;
        List<String> deletedRefs = new ArrayList<>();
        for (GridFSFile file : primaryReadBucket.find(Filters.eq("metadata." + META_CONVERSATION_ID, conversationId))) {
            gridFSBucket.delete(file.getObjectId());
            deletedRefs.add(canonicalRef(file));
            count++;
        }
        // The grants on the blobs just deleted, and the grants this conversation held
        // on other blobs — in both shapes, or they would grow without bound.
        if (!deletedRefs.isEmpty()) {
            grantsCollection.deleteMany(Filters.in(GRANT_STORAGE_REF, deletedRefs));
        }
        long held = grantsCollection.deleteMany(Filters.eq(GRANT_CONVERSATION_ID, conversationId)).getDeletedCount();
        filesCollection.updateMany(Filters.eq("metadata." + META_GRANTS, conversationId), Updates.pull("metadata." + META_GRANTS, conversationId));
        if (count > 0 || held > 0) {
            LOGGER.debugf("Deleted %d attachments and %d held grants for conversation '%s'", count, held, sanitize(conversationId));
        }
        return count;
    }

    @Override
    public List<Attachment> listByConversation(String conversationId) {
        return listMatching(Filters.eq("metadata." + META_CONVERSATION_ID, conversationId));
    }

    @Override
    public List<Attachment> listAccessible(String conversationId) {
        // Two round trips: the refs granted in the grants collection, then ONE files
        // query for owned OR legacy-granted OR granted — so a blob granted in both
        // shapes is listed once, and a grant row whose blob is gone lists nothing.
        // Filters.eq on the array field matches documents whose grants array
        // contains the value (Mongo semantics).
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
        return listMatching(Filters.or(clauses));
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

    /** {count, totalBytes} of the blobs matching {@code filter}. */
    private long[] usage(Bson filter) {
        long count = 0;
        long totalBytes = 0;
        for (GridFSFile file : primaryReadBucket.find(filter)) {
            count++;
            totalBytes += file.getLength();
        }
        return new long[]{count, totalBytes};
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

    private void authorize(GridFSFile file, String requester) throws AttachmentStoreException {
        Document metadata = file.getMetadata();
        // Fails closed, as the PostgreSQL store does: a blob with no recorded owner
        // (a pre-ownership legacy blob) used to be readable by any conversation that
        // knew its reference. Ownership that cannot be established is not ownership.
        String owner = metadata != null ? metadata.getString(META_CONVERSATION_ID) : null;
        if (owner != null && owner.equals(requester)) {
            return;
        }
        List<String> grants = metadata != null ? metadata.getList(META_GRANTS, String.class) : null;
        if (grants != null && grants.contains(requester)) {
            return;
        }
        String ref = canonicalRef(file);
        if (requester != null && ref != null && grantsCollection.countDocuments(
                Filters.and(Filters.eq(GRANT_STORAGE_REF, ref), Filters.eq(GRANT_CONVERSATION_ID, requester))) > 0) {
            return;
        }
        throw new AttachmentAccessDeniedException(
                "Cross-conversation access denied: attachment belongs to '%s', requested from '%s'"
                        .formatted(owner, requester));
    }
}
