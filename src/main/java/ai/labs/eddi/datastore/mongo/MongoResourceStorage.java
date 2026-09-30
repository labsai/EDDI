/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.mongo;

import ai.labs.eddi.datastore.IResourceFilter;
import ai.labs.eddi.datastore.IResourceStorage;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.serialization.IDocumentBuilder;
import com.mongodb.MongoCommandException;
import com.mongodb.MongoException;
import com.mongodb.MongoWriteException;
import com.mongodb.WriteConcern;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.Updates;
import org.bson.BsonDocument;
import org.bson.BsonValue;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;
import static ai.labs.eddi.utils.RuntimeUtilities.checkNotNull;

/**
 * @author ginccc
 */
public class MongoResourceStorage<T> implements IResourceStorage<T> {
    private static final Logger LOGGER = Logger.getLogger(MongoResourceStorage.class);

    /** MongoDB {@code IndexOptionsConflict}. */
    static final int INDEX_OPTIONS_CONFLICT_ERROR_CODE = 85;
    /** MongoDB {@code IndexKeySpecsConflict}. */
    static final int INDEX_KEY_SPECS_CONFLICT_ERROR_CODE = 86;

    public static final String VERSION_FIELD = "_version";
    public static final String ID_FIELD = "_id";
    private static final String DELETED_FIELD = "_deleted";

    private static final String HISTORY_POSTFIX = ".history";

    /**
     * A history row's {@code _id} is the embedded document
     * <code>{_id: ObjectId, _version: int}</code>, so its parts are addressed by
     * these dotted paths. Named once because both the filter and the index that has
     * to serve it must agree.
     */
    static final String HISTORY_NESTED_ID_FIELD = ID_FIELD + "." + ID_FIELD;
    static final String HISTORY_NESTED_VERSION_FIELD = ID_FIELD + "." + VERSION_FIELD;
    private final Class<T> documentType;

    protected MongoCollection<Document> currentCollection;
    protected MongoCollection<Document> historyCollection;
    protected IDocumentBuilder documentBuilder;

    public MongoResourceStorage(MongoDatabase database, String collectionName, IDocumentBuilder documentBuilder, Class<T> documentType) {
        this(database, collectionName, documentBuilder, documentType, new String[0]);
    }

    public MongoResourceStorage(MongoDatabase database, String collectionName, IDocumentBuilder documentBuilder, Class<T> documentType,
            String... indexes) {
        checkNotNull(database, "database");

        this.documentType = documentType;
        this.currentCollection = database.getCollection(collectionName);
        this.historyCollection = database.getCollection(collectionName + HISTORY_POSTFIX);
        this.documentBuilder = documentBuilder;

        ensureIndex(database, currentCollection, Indexes.ascending(ID_FIELD, VERSION_FIELD), true);
        // History rows are addressed by the NESTED id, not by a range over the
        // composite _id (see historyRowsOf). MongoDB's built-in _id index covers the
        // whole embedded subdocument and cannot serve a dotted path into it, so
        // without this index removeAllPermanently and readHistoryLatest are a
        // COLLSCAN of the history collection. That is not academic: descriptors.history
        // holds one row per conversation, and GdprComplianceService's erasure loop
        // calls deleteAllDescriptor once per conversation.
        ensureIndex(database, historyCollection, Indexes.ascending(HISTORY_NESTED_ID_FIELD, HISTORY_NESTED_VERSION_FIELD), false);

        Arrays.stream(indexes).forEach(index -> {
            ensureIndex(database, currentCollection, Indexes.ascending(index), false);
            ensureIndex(database, historyCollection, Indexes.ascending(index), false);
        });
    }

    /**
     * Creates an index, and brings any index an earlier EDDI left on the same key
     * into line with the specification asked for here.
     *
     * <p>
     * The case that made this necessary: databases created before 6.3 hold
     * {@code descriptors.resource_1} with {@code unique: true}, and this store asks
     * for it non-unique. MongoDB refuses that with {@code IndexKeySpecsConflict}
     * (86) — same name, different options — and the exception used to escape this
     * constructor. The descriptor store could then never be built, so every
     * deployed agent ended in ERROR and every descriptor listing answered 500.
     * </p>
     *
     * <p>
     * The index asked for here wins. For {@code resource_1} that is deliberate:
     * since 6.3 neither backend enforces uniqueness on the field (PostgreSQL's
     * expression index is not unique, and a MongoDB created by 6.3 or later never
     * had it), so no 6.x write path relies on it, and keeping it would make one
     * class of installation refuse writes the others accept. For the same reason a
     * unique index on the same key under <em>another</em> name is replaced too: the
     * server accepts a second, non-unique index beside it without complaint, and
     * the stricter one would go on refusing writes.
     * </p>
     *
     * <p>
     * As in {@code MongoDeploymentStorage}, the error code alone decides nothing:
     * both 85 (an equivalent index under another name) and 86 (same name, other
     * options — or the same name on a <em>different</em> key) are answered by
     * reading the collection's own indexes. An index that holds the generated name
     * on another key is someone else's and is never dropped. A conflict that cannot
     * be resolved is logged at ERROR and the store is built anyway: it works
     * without the index, only more slowly, whereas a store that cannot be
     * constructed takes the application down with it.
     * </p>
     */
    private static void ensureIndex(MongoDatabase database, MongoCollection<Document> mongoCollection, Bson indexKey, boolean unique) {
        MongoCommandException conflict = null;
        try {
            mongoCollection.createIndex(indexKey, new IndexOptions().unique(unique));
        } catch (MongoCommandException e) {
            if (e.getErrorCode() != INDEX_OPTIONS_CONFLICT_ERROR_CODE && e.getErrorCode() != INDEX_KEY_SPECS_CONFLICT_ERROR_CODE) {
                throw e;
            }
            conflict = e;
        }
        try {
            reconcileIndexesOnKey(database, mongoCollection, indexKey.toBsonDocument(), unique, conflict);
        } catch (RuntimeException e) {
            if (conflict != null) {
                LOGGER.errorf("Cannot create index %s on '%s' (%s), and the conflicting index could not be reconciled: %s. "
                        + "Queries on this field scan.", indexKey.toBsonDocument().toJson(),
                        String.valueOf(mongoCollection.getNamespace()), conflict.getErrorMessage(), e.toString());
            } else {
                LOGGER.warnf("Could not check '%s' for indexes an earlier EDDI left on %s: %s",
                        String.valueOf(mongoCollection.getNamespace()), indexKey.toBsonDocument().toJson(), e.toString());
            }
        }
    }

    /**
     * Replaces every index on {@code keyPattern} that does not have the
     * specification asked for (see {@link #hasSpecification}), and builds the
     * requested one if nothing equivalent is left.
     *
     * @param conflict
     *            the error {@code createIndex} raised, or {@code null} when it
     *            succeeded
     */
    private static void reconcileIndexesOnKey(MongoDatabase database, MongoCollection<Document> mongoCollection, BsonDocument keyPattern,
                                              boolean unique,
                                              MongoCommandException conflict) {
        String collectionName = mongoCollection.getNamespace().getCollectionName();
        Map<String, Document> indexes = new LinkedHashMap<>();
        for (Document index : mongoCollection.listIndexes()) {
            indexes.put(index.getString("name"), index);
        }

        String generatedName = generatedIndexName(keyPattern);
        Document nameHolder = indexes.get(generatedName);
        boolean nameHeldByForeignIndex = nameHolder != null && !sameKeyPattern(nameHolder, keyPattern);

        List<Document> onKey = indexes.values().stream().filter(index -> sameKeyPattern(index, keyPattern)).toList();
        // Read only when an index on the key carries a collation, which is rare.
        Document defaultCollation = onKey.stream().anyMatch(index -> index.containsKey(COLLATION))
                ? defaultCollation(database, collectionName)
                : null;
        List<Document> mismatched = onKey.stream().filter(index -> !hasSpecification(index, unique, defaultCollation)).toList();
        if (conflict == null && mismatched.isEmpty()) {
            return;
        }

        Document equivalent = onKey.stream().filter(index -> !mismatched.contains(index)).findFirst().orElse(null);
        if (equivalent == null) {
            // A hidden index that is otherwise right is made visible in place: the
            // server refuses a second index identical to it but for the name.
            Document hiddenButRight = mismatched.stream()
                    .filter(index -> index.getBoolean("hidden", false) && hasSpecification(visible(index), unique, defaultCollation))
                    .findFirst().orElse(null);
            if (hiddenButRight != null && unhide(database, collectionName, hiddenButRight)) {
                equivalent = hiddenButRight;
            }
        }
        if (equivalent == null) {
            // The replacement is built BEFORE anything is dropped, so the key is never
            // without an index — not if the build fails, and not while another instance
            // starting at the same moment is doing the same. The server accepts a
            // second index on one key when the specifications differ; it takes the
            // generated name if that is free, the alternate one otherwise.
            // The generated name is free only if nothing holds it: a stale index of ours
            // still exists at this point, and a foreign one is never dropped.
            IndexOptions options = new IndexOptions().unique(unique);
            if (nameHolder != null) {
                options.name(generatedName + ALTERNATE_INDEX_NAME_SUFFIX);
            }
            if (nameHeldByForeignIndex) {
                LOGGER.errorf("The index name '%s' on '%s' is held by an index on a different key (%s); it was left alone, and "
                        + "%s is built as '%s' instead. Rename or remove that index by hand.", generatedName, collectionName,
                        nameHolder.get("key"), keyPattern.toJson(), generatedName + ALTERNATE_INDEX_NAME_SUFFIX);
            }
            try {
                mongoCollection.createIndex(keyPattern, options);
            } catch (RuntimeException e) {
                LOGGER.errorf("Could not build %s on '%s': %s. Any existing index on that key is kept as it is.",
                        keyPattern.toJson(), collectionName, e.getMessage());
                return;
            }
        } else if (conflict != null && mismatched.isEmpty()) {
            LOGGER.infof("Index %s on '%s' already exists as '%s' with the same specification; kept.", keyPattern.toJson(),
                    collectionName, equivalent.getString("name"));
        }

        for (Document index : mismatched) {
            if (index == equivalent) {
                continue;
            }
            LOGGER.warnf("Index '%s' on '%s' was built by an earlier EDDI with another specification (%s); replaced by one "
                    + "with the current specification (unique=%s).", index.getString("name"), collectionName, index.toJson(), unique);
            try {
                mongoCollection.dropIndex(index.getString("name"));
            } catch (MongoCommandException e) {
                if (e.getErrorCode() != INDEX_NOT_FOUND_ERROR_CODE) {
                    throw e;
                }
                // Another instance starting at the same time dropped it first.
            }
        }
    }

    /** MongoDB {@code IndexNotFound}. */
    static final int INDEX_NOT_FOUND_ERROR_CODE = 27;

    /**
     * {@code index} without its {@code hidden} flag, for comparing the rest of it.
     */
    private static Document visible(Document index) {
        Document copy = new Document(index);
        copy.remove("hidden");
        return copy;
    }

    /** Makes a hidden index visible; whether that worked. */
    private static boolean unhide(MongoDatabase database, String collectionName, Document index) {
        try {
            database.runCommand(new Document("collMod", collectionName).append("index",
                    new Document("name", index.getString("name")).append("hidden", false)));
            LOGGER.warnf("Index '%s' on '%s' was hidden, so no query used it; made it visible.", index.getString("name"),
                    collectionName);
            return true;
        } catch (RuntimeException e) {
            LOGGER.warnf("Could not make the hidden index '%s' on '%s' visible (%s); building a replacement.",
                    index.getString("name"), collectionName, e.getMessage());
            return false;
        }
    }

    /**
     * Appended to the generated name when an index on another key already holds it.
     */
    static final String ALTERNATE_INDEX_NAME_SUFFIX = "_eddi";

    private static final String COLLATION = "collation";

    /**
     * Whether an index on the right key also has the specification asked for: the
     * same uniqueness, and nothing that keeps it from serving this store's queries.
     * A partial or sparse index serves only queries that carry its filter, and a
     * hidden one serves none; this store's queries carry no filter. A collation
     * other than the collection's default is not used by those queries either — but
     * the collection's default is, since MongoDB applies it to an index created
     * without one, and rejecting that would rebuild a correct index on every start.
     *
     * @param defaultCollation
     *            the collection's default collation, or {@code null} when it has
     *            none
     */
    static boolean hasSpecification(Document index, boolean unique, Document defaultCollation) {
        return index.getBoolean("unique", false) == unique && !index.containsKey("partialFilterExpression")
                && !index.getBoolean("sparse", false) && !index.getBoolean("hidden", false)
                && Objects.equals(index.get(COLLATION), defaultCollation);
    }

    /** The collection's default collation, or {@code null} when it has none. */
    private static Document defaultCollation(MongoDatabase database, String collectionName) {
        for (Document collection : database.listCollections().filter(Filters.eq("name", collectionName))) {
            if (collection.get("options") instanceof Document options && options.get(COLLATION) instanceof Document collation) {
                return collation;
            }
        }
        return null;
    }

    /**
     * The name MongoDB gives an index when none is supplied: each field and its
     * direction, joined by underscores.
     */
    static String generatedIndexName(BsonDocument keyPattern) {
        var name = new StringBuilder();
        keyPattern.forEach((field, direction) -> {
            if (!name.isEmpty()) {
                name.append('_');
            }
            name.append(field).append('_').append(direction.isNumber() ? String.valueOf(direction.asNumber().intValue()) : direction);
        });
        return name.toString();
    }

    /**
     * Whether an index sits on exactly this key: the same fields in the same order
     * with the same directions. Compared numerically, since the server may report
     * {@code 1} as an int, a long or a double.
     */
    static boolean sameKeyPattern(Document index, BsonDocument keyPattern) {
        if (!(index.get("key") instanceof Document actual)) {
            return false;
        }
        if (!new ArrayList<>(actual.keySet()).equals(new ArrayList<>(keyPattern.keySet()))) {
            return false;
        }
        for (String field : keyPattern.keySet()) {
            BsonValue expected = keyPattern.get(field);
            if (!expected.isNumber() || !(actual.get(field) instanceof Number direction)
                    || direction.doubleValue() != expected.asNumber().doubleValue()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public IResource<T> newResource(T content) throws IOException {
        Document doc = Document.parse(documentBuilder.toString(content));
        doc.put(VERSION_FIELD, 1);
        return new Resource(doc);
    }

    @Override
    public IResource<T> newResource(String id, Integer version, T content) throws IOException {
        Document doc = Document.parse(documentBuilder.toString(content));

        Resource resource = new Resource(doc);
        resource.setVersion(version);
        resource.setId(id);

        return resource;
    }

    /**
     * A write REPLACES the stored document — it does not merge into it.
     * <p>
     * This used to be an {@code $set} of the whole document, which merges: because
     * the mapper serializes with {@code NON_NULL} inclusion, clearing a config
     * field produced JSON without that key, so {@code $set} left the OLD value in
     * place and the API still answered 200 with a fresh version. The PostgreSQL
     * backend has always replaced ({@code data = EXCLUDED.data}), so the same edit
     * behaved differently per backend. Replace is the semantics both backends now
     * share.
     */
    @Override
    public void store(IResource<T> currentResource) {
        Resource resource = checkInternalResource(currentResource);
        if (resource.getId() == null) {
            currentCollection.insertOne(resource.getMongoDocument());
        } else {
            currentCollection.replaceOne(Filters.eq(ID_FIELD, new ObjectId(resource.getId())), resource.getMongoDocument(),
                    new ReplaceOptions().upsert(true));
        }
    }

    @Override
    public void storeIfCurrentVersion(IResource<T> newResource, int expectedCurrentVersion)
            throws IResourceStore.ResourceModifiedException {
        Resource resource = checkInternalResource(newResource);
        var result = currentCollection.replaceOne(
                Filters.and(
                        Filters.eq(ID_FIELD, new ObjectId(resource.getId())),
                        Filters.eq(VERSION_FIELD, expectedCurrentVersion)),
                resource.getMongoDocument());
        if (result.getMatchedCount() == 0) {
            throw new IResourceStore.ResourceModifiedException(
                    String.format("Resource was modified concurrently (id=%s, expected version=%d)",
                            resource.getId(), expectedCurrentVersion));
        }
    }

    @Override
    public void storeIfFieldEquals(IResource<T> newResource, String fieldName, String expectedValue)
            throws IResourceStore.ResourceModifiedException, IResourceStore.ResourceNotFoundException {
        replaceIfMatches(newResource, fieldName, expectedValue, Filters.eq(fieldName, expectedValue));
    }

    @Override
    public void storeIfFieldEqualsOrMissing(IResource<T> newResource, String fieldName, String expectedValue)
            throws IResourceStore.ResourceModifiedException, IResourceStore.ResourceNotFoundException {
        // {field: null} matches both an absent field and an explicit null — one
        // filter, so the "equals or missing" decision stays inside the single
        // atomic replaceOne.
        replaceIfMatches(newResource, fieldName, expectedValue,
                Filters.or(Filters.eq(fieldName, expectedValue), Filters.eq(fieldName, null)));
    }

    private void replaceIfMatches(IResource<T> newResource, String fieldName, String expectedValue, Bson fieldFilter)
            throws IResourceStore.ResourceModifiedException, IResourceStore.ResourceNotFoundException {
        Resource resource = checkInternalResource(newResource);
        var result = currentCollection.replaceOne(
                Filters.and(
                        Filters.eq(ID_FIELD, new ObjectId(resource.getId())),
                        fieldFilter),
                resource.getMongoDocument());
        if (result.getMatchedCount() == 0) {
            // Distinguish "deleted" (404) from "field mismatch" (409) — a bare
            // matchedCount==0 conflates them and misleads callers/operators.
            long exists = currentCollection.countDocuments(Filters.eq(ID_FIELD, new ObjectId(resource.getId())));
            if (exists == 0) {
                throw new IResourceStore.ResourceNotFoundException(
                        String.format("Resource no longer exists (id=%s)", resource.getId()));
            }
            throw new IResourceStore.ResourceModifiedException(
                    String.format("Resource field '%s' was not '%s' (id=%s)", fieldName, expectedValue, resource.getId()));
        }
    }

    @Override
    public void storeIfFieldEquals(IResource<T> newResource, String fieldName, long expectedValue)
            throws IResourceStore.ResourceModifiedException, IResourceStore.ResourceNotFoundException {
        Resource resource = checkInternalResource(newResource);
        // Typed BSON equality — the String overload's Filters.eq(field, "3") never
        // matches an int64 3, which is exactly why this overload exists.
        var result = currentCollection.replaceOne(
                Filters.and(
                        Filters.eq(ID_FIELD, new ObjectId(resource.getId())),
                        Filters.eq(fieldName, expectedValue)),
                resource.getMongoDocument());
        if (result.getMatchedCount() == 0) {
            long exists = currentCollection.countDocuments(Filters.eq(ID_FIELD, new ObjectId(resource.getId())));
            if (exists == 0) {
                throw new IResourceStore.ResourceNotFoundException(
                        String.format("Resource no longer exists (id=%s)", resource.getId()));
            }
            throw new IResourceStore.ResourceModifiedException(
                    String.format("Resource field '%s' was not %d (id=%s)", fieldName, expectedValue, resource.getId()));
        }
    }

    @Override
    public void createNew(IResource<T> currentResource) {
        Resource resource = checkInternalResource(currentResource);
        currentCollection.insertOne(resource.getMongoDocument());
    }

    /**
     * Whether {@code id} can name a document in this backend at all.
     * <p>
     * Ids arrive from outside too - an archive exported by a PostgreSQL deployment
     * carries UUIDs - and {@code new ObjectId(uuid)} throws
     * {@link IllegalArgumentException}, which turned "is there a local copy of
     * this?" during an import preview or merge into a 500 or a 400. An id that
     * cannot be an ObjectId names nothing here, so every lookup answers "not found"
     * for it, as the PostgreSQL backend already does for a non-UUID.
     */
    private static boolean isStorableId(String id) {
        return id != null && ObjectId.isValid(id);
    }

    @Override
    public IResource<T> read(String id, Integer version) {
        if (!isStorableId(id)) {
            return null;
        }
        Document query = new Document(ID_FIELD, new ObjectId(id));
        query.put(VERSION_FIELD, version);

        Document document = currentCollection.find(query).first();
        if (document == null) {
            return null;
        }
        return new Resource(document);
    }

    @Override
    public List<IResource<T>> readMany(List<IResourceStore.IResourceId> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }

        // $in on _id rather than an $or of (id, version) pairs: a page can be as
        // long as MAX_RESULT_LIMIT, and a 10_000-clause $or is a query planner
        // hazard. The version is re-checked below instead.
        List<ObjectId> objectIds = new ArrayList<>(ids.size());
        for (IResourceStore.IResourceId id : ids) {
            objectIds.add(new ObjectId(id.getId()));
        }

        Map<String, Resource> byId = new HashMap<>();
        currentCollection.find(Filters.in(ID_FIELD, objectIds)).forEach(doc -> byId.put(doc.get(ID_FIELD).toString(), new Resource(doc)));

        // Request order is the caller's sort order — a Mongo cursor is not.
        List<IResource<T>> resources = new ArrayList<>(ids.size());
        for (IResourceStore.IResourceId id : ids) {
            Resource resource = byId.get(id.getId());
            if (resource != null && id.getVersion().equals(resource.getVersion())) {
                resources.add(resource);
            }
        }
        return resources;
    }

    @Override
    public void remove(String id) {
        currentCollection.deleteOne(new Document(ID_FIELD, new ObjectId(id)));
    }

    /**
     * MongoDB multi-document transactions require a replica set, and EDDI supports
     * standalone deployments (the documented local setup is a bare {@code mongo:7}
     * container), so a session transaction here would break the default install.
     * Instead both writes are acknowledged by a majority of nodes before the next
     * one is issued, which removes the "acknowledged then lost on failover" half of
     * the problem; the ordering (history first) bounds the rest — a crash in
     * between leaves a redundant history row, never a missing one.
     */
    @Override
    public void storeHistoryAndUpdate(IHistoryResource<T> history, IResource<T> newResource, int expectedCurrentVersion)
            throws IResourceStore.ResourceModifiedException {
        HistoryResource historyResource = checkInternalHistoryResource(history);
        Resource resource = checkInternalResource(newResource);

        durableInsertHistory(historyResource);

        var result = currentCollection.withWriteConcern(WriteConcern.MAJORITY).replaceOne(
                Filters.and(
                        Filters.eq(ID_FIELD, new ObjectId(resource.getId())),
                        Filters.eq(VERSION_FIELD, expectedCurrentVersion)),
                resource.getMongoDocument());
        if (result.getMatchedCount() == 0) {
            throw new IResourceStore.ResourceModifiedException(
                    String.format("Resource was modified concurrently (id=%s, expected version=%d)",
                            resource.getId(), expectedCurrentVersion));
        }
        clearStaleTombstone(historyResource.getMongoDocument().get(ID_FIELD));
    }

    /**
     * Takes a {@code deleted} flag off a history row whose version was, a moment
     * ago, the live one.
     * <p>
     * The archive above is insert-if-absent, so a tombstone a failed delete left on
     * this version (see {@link #storeHistoryAndRemove}) would otherwise survive the
     * update for good: every later read of that version answers 404, and a
     * deployment pinned to it stops loading. Having just replaced the live row at
     * exactly this version proves the resource was not deleted there. Best-effort —
     * the update itself has already succeeded.
     */
    private void clearStaleTombstone(Object historyRowId) {
        try {
            historyCollection.updateOne(
                    Filters.and(Filters.eq(ID_FIELD, historyRowId), Filters.eq(DELETED_FIELD, true)),
                    Updates.unset(DELETED_FIELD));
        } catch (MongoException e) {
            LOGGER.warnf("Could not clear a stale deleted flag on history row %s: %s", sanitize(String.valueOf(historyRowId)),
                    sanitize(e.getMessage()));
        }
    }

    /**
     * Tombstone first, then a delete conditioned on the version.
     * <p>
     * The tombstone is an upsert, not the insert-if-absent archiving uses: an
     * update racing this delete may already have archived the same version
     * <em>without</em> the deleted flag, and a delete that then succeeds must still
     * leave the flag behind. If the conditional delete matches nothing because the
     * resource moved on, the flag is taken off again — that version is ordinary
     * history of a live resource — and the caller gets a conflict.
     *
     * @see #storeHistoryAndUpdate(IHistoryResource, IResource, int) for why this is
     *      not a session transaction
     */
    @Override
    public void storeHistoryAndRemove(IHistoryResource<T> history, String id, int expectedCurrentVersion)
            throws IResourceStore.ResourceModifiedException {
        HistoryResource historyResource = checkInternalHistoryResource(history);
        Bson historyRow = Filters.eq(ID_FIELD, historyResource.getMongoDocument().get(ID_FIELD));
        var durableHistory = historyCollection.withWriteConcern(WriteConcern.MAJORITY);
        durableHistory.replaceOne(historyRow, historyResource.getMongoDocument(), new ReplaceOptions().upsert(true));

        try {
            var result = currentCollection.withWriteConcern(WriteConcern.MAJORITY).deleteOne(
                    Filters.and(
                            Filters.eq(ID_FIELD, new ObjectId(id)),
                            Filters.eq(VERSION_FIELD, expectedCurrentVersion)));
            if (result.getDeletedCount() > 0) {
                return;
            }
            if (currentCollection.countDocuments(Filters.eq(ID_FIELD, new ObjectId(id))) == 0) {
                // Already gone — a concurrent delete of the same version won. Deleted, as
                // asked.
                return;
            }
        } catch (MongoException e) {
            // A write-concern timeout, a step-down or a network error: whether the delete
            // happened is unknown. If the resource is still live at this version, the
            // tombstone is a lie that would make the version unreadable for good.
            untombstoneIfStillLive(historyRow, id, expectedCurrentVersion);
            throw e;
        }
        untombstoneMovedOnVersion(durableHistory, historyRow, id, expectedCurrentVersion);
        throw new IResourceStore.ResourceModifiedException(
                String.format("Resource was modified concurrently (id=%s, expected version=%d)", id, expectedCurrentVersion));
    }

    /**
     * Takes the tombstone back off a version the resource has already moved past,
     * once more if the first attempt fails.
     * <p>
     * Nothing else would ever clear it: {@link #clearStaleTombstone} runs only when
     * this exact version is replaced as the live one, and the resource is already
     * live at a newer version. A flag left here answers 404 for that version for
     * good. The caller still gets the conflict it is owed rather than a raw driver
     * exception — the delete was refused either way.
     */
    private void untombstoneMovedOnVersion(MongoCollection<Document> durableHistory, Bson historyRow, String id, int version) {
        for (int attempt = 1;; attempt++) {
            try {
                durableHistory.updateOne(historyRow, Updates.unset(DELETED_FIELD));
                return;
            } catch (MongoException e) {
                if (attempt >= 2) {
                    LOGGER.warnf("Refused delete of %s v%d could not take its tombstone back; that version reads as deleted: %s",
                            sanitize(id), version, sanitize(e.getMessage()));
                    return;
                }
            }
        }
    }

    private void untombstoneIfStillLive(Bson historyRow, String id, int version) {
        try {
            long live = currentCollection.countDocuments(
                    Filters.and(Filters.eq(ID_FIELD, new ObjectId(id)), Filters.eq(VERSION_FIELD, version)));
            if (live > 0) {
                historyCollection.withWriteConcern(WriteConcern.MAJORITY).updateOne(historyRow, Updates.unset(DELETED_FIELD));
            }
        } catch (MongoException e) {
            // Still unreachable. The next successful update of this version clears the
            // flag (see clearStaleTombstone).
            LOGGER.warnf("Delete of %s v%d failed and its tombstone could not be checked: %s", sanitize(id), version,
                    sanitize(e.getMessage()));
        }
    }

    @Override
    public boolean replaceHistory(IHistoryResource<T> history) {
        HistoryResource historyResource = checkInternalHistoryResource(history);
        Document row = historyResource.getMongoDocument();
        return historyCollection.replaceOne(Filters.eq(ID_FIELD, row.get(ID_FIELD)), row).getMatchedCount() > 0;
    }

    private void durableInsertHistory(HistoryResource historyResource) {
        try {
            historyCollection.withWriteConcern(WriteConcern.MAJORITY).insertOne(historyResource.getMongoDocument());
        } catch (MongoWriteException e) {
            if (e.getError().getCode() != 11000) {
                throw e;
            }
            // Duplicate key — another thread already archived this exact version.
        }
    }

    /**
     * Every history row of a resource, addressed by the nested id rather than by a
     * range over the composite {@code _id}.
     * <p>
     * A history row's {@code _id} is the embedded document {@code {_id: ObjectId,
     * _version: int}} (see {@link #newHistoryResourceFor(IResource, boolean)}), and
     * BSON compares embedded documents field by field. The previous {@code $gt
     * {_id, _version: 0}} bound therefore compared EQUAL to — and so excluded —
     * every row archived at version 0, which is exactly the version conversation
     * descriptors are created at. Those tombstones (carrying {@code userId})
     * survived both permanent delete and GDPR erasure, and nothing else in the
     * codebase deletes history rows. Matching the nested field covers every
     * version, including 0 and negative ones, and matches
     * {@code PostgresResourceStorage}, which deletes by id alone.
     * <p>
     * The dotted path needs its own index — the built-in {@code _id} index is on
     * the whole subdocument and cannot serve it — which the constructor creates.
     */
    private static Document historyRowsOf(String id) {
        return new Document(HISTORY_NESTED_ID_FIELD, new ObjectId(id));
    }

    @Override
    public void removeAllPermanently(String id) {
        remove(id);
        historyCollection.deleteMany(historyRowsOf(id));
    }

    @Override
    public IHistoryResource<T> readHistory(String id, Integer version) {
        if (!isStorableId(id)) {
            return null;
        }
        Document objectId = new Document(ID_FIELD, new ObjectId(id));
        objectId.put(VERSION_FIELD, version);

        Document doc = historyCollection.find(Filters.eq(ID_FIELD, objectId)).first();
        if (doc == null) {
            return null;
        }
        return new HistoryResource(doc);
    }

    /** @see #historyRowsOf(String) for why this is a nested-field match. */
    @Override
    public IHistoryResource<T> readHistoryLatest(String id) {
        if (!isStorableId(id)) {
            return null;
        }
        Document query = historyRowsOf(id);

        if (historyCollection.countDocuments(query) == 0) {
            return null;
        }

        Document doc = historyCollection.find(query).sort(new Document(HISTORY_NESTED_VERSION_FIELD, -1)).limit(1).first();
        if (doc == null) {
            return null;
        }
        return new HistoryResource(doc);
    }

    @Override
    public IHistoryResource<T> newHistoryResourceFor(IResource<T> resource, boolean deleted) {
        Resource mongoResource = checkInternalResource(resource);
        Document historyObject = new Document(mongoResource.getMongoDocument());

        Document idObject = new Document();
        idObject.put(ID_FIELD, new ObjectId(resource.getId()));
        idObject.put(VERSION_FIELD, resource.getVersion());
        historyObject.put(ID_FIELD, idObject);
        if (deleted) {
            historyObject.put(DELETED_FIELD, true);
        }

        return new HistoryResource(historyObject);
    }

    @Override
    public Integer getCurrentVersion(String id) {
        if (!isStorableId(id)) {
            return -1;
        }
        Document query = new Document(ID_FIELD, new ObjectId(id));
        Document one = currentCollection.find(query).first();
        if (one == null) {
            return -1;
        }
        return (Integer) one.get(VERSION_FIELD);
    }

    @Override
    public void store(IHistoryResource<T> resource) {
        HistoryResource historyResource = checkInternalHistoryResource(resource);
        try {
            historyCollection.insertOne(historyResource.getMongoDocument());
        } catch (MongoWriteException e) {
            if (e.getError().getCode() == 11000) {
                // Duplicate key — another thread already archived this version.
                // Safe to ignore: the history row is identical (same id + version).
                return;
            }
            throw e;
        }
    }

    @Override
    public List<IResourceStore.IResourceId> findResourceIdsContaining(String jsonPath, String value) {
        Document filter = new Document(jsonPath, new Document("$in", Collections.singletonList(value)));

        List<IResourceStore.IResourceId> results = new LinkedList<>();
        currentCollection.find(filter).forEach(doc -> {
            String docId = doc.getObjectId(ID_FIELD).toString();
            Integer version = doc.getInteger(VERSION_FIELD);
            results.add(createResourceId(docId, version));
        });
        return results;
    }

    @Override
    public List<IResourceStore.IResourceId> findHistoryResourceIdsContaining(String jsonPath, String value) {
        Document filter = new Document(jsonPath, new Document("$in", Collections.singletonList(value)));

        List<IResourceStore.IResourceId> results = new LinkedList<>();
        historyCollection.find(filter).forEach(doc -> {
            Object idObject = doc.get(ID_FIELD);
            if (idObject instanceof Document idDoc) {
                String docId = idDoc.getObjectId(ID_FIELD).toString();
                Integer version = idDoc.getInteger(VERSION_FIELD);
                results.add(createResourceId(docId, version));
            }
        });
        return results;
    }

    @Override
    public List<IResourceStore.IResourceId> findResources(IResourceFilter.QueryFilters[] allQueryFilters, String sortField, int skip, int limit) {

        List<Bson> connectedFilters = new ArrayList<>();
        for (IResourceFilter.QueryFilters queryFilters : allQueryFilters) {
            List<Bson> filters = new ArrayList<>();
            for (IResourceFilter.QueryFilter queryFilter : queryFilters.getQueryFilters()) {
                if (queryFilter.isExact()) {
                    filters.add(Filters.eq(queryFilter.getField(), queryFilter.getFilter()));
                } else if (queryFilter.getFilter() instanceof String) {
                    filters.add(Filters.regex(queryFilter.getField(), queryFilter.getFilter().toString()));
                } else if (queryFilter.getFilter() instanceof IResourceFilter.NotMatching notMatching) {
                    // $not also matches documents lacking the field, which is the
                    // contract NotMatching documents.
                    filters.add(Filters.not(Filters.regex(queryFilter.getField(), notMatching.pattern())));
                } else {
                    filters.add(Filters.eq(queryFilter.getField(), queryFilter.getFilter()));
                }
            }
            if (queryFilters.getConnectingType() == IResourceFilter.QueryFilters.ConnectingType.AND) {
                connectedFilters.add(Filters.and(filters));
            } else {
                connectedFilters.add(Filters.or(filters));
            }
        }

        Bson query = Filters.and(connectedFilters);
        Document sort = sortField != null ? new Document(sortField, -1) : new Document();
        int effectiveLimit = IResourceStorage.resolveLimit(limit);

        var iterable = currentCollection.find(query.toBsonDocument()).sort(sort).limit(effectiveLimit).skip(skip > 0 ? skip : 0);

        List<IResourceStore.IResourceId> results = new LinkedList<>();
        iterable.forEach(doc -> {
            String docId = doc.get(ID_FIELD).toString();
            Object versionField = doc.get(VERSION_FIELD);
            Integer version = Integer.parseInt(versionField.toString());
            results.add(createResourceId(docId, version));
        });

        return results;
    }

    private static IResourceStore.IResourceId createResourceId(String id, Integer version) {
        return new IResourceStore.IResourceId() {
            @Override
            public String getId() {
                return id;
            }

            @Override
            public Integer getVersion() {
                return version;
            }
        };
    }

    private Resource checkInternalResource(IResource<T> currentResource) {
        if (!(currentResource instanceof MongoResourceStorage<?>.Resource)) {
            throw new IllegalArgumentException("Resource must not be implemented externally.");
        }
        return (Resource) currentResource;
    }

    private HistoryResource checkInternalHistoryResource(IHistoryResource<T> resource) {
        if (!(resource instanceof MongoResourceStorage<?>.HistoryResource)) {
            throw new IllegalArgumentException("HistoryResource must not be implemented externally.");
        }
        return (HistoryResource) resource;

    }

    private class Resource implements IResource<T> {
        private Document doc;

        Resource(Document doc) {
            this.doc = doc;
        }

        public void setVersion(int version) {
            doc.put(VERSION_FIELD, version);
        }

        @Override
        public Integer getVersion() {
            return (Integer) doc.get(VERSION_FIELD);
        }

        @Override
        public T getData() throws IOException {
            return documentBuilder.build(doc, documentType);
        }

        @Override
        public String getId() {
            Object id = doc.get("_id");
            return id != null ? id.toString() : null;
        }

        public void setId(String id) {
            doc.put("_id", new ObjectId(id));
        }

        Document getMongoDocument() {
            return doc;
        }

    }

    private class HistoryResource implements IHistoryResource<T> {
        private Document doc;

        HistoryResource(Document doc) {
            this.doc = doc;
        }

        @Override
        public T getData() throws IOException {
            return documentBuilder.build(doc, documentType);
        }

        @Override
        public String getId() {
            Document idObject = (Document) doc.get(ID_FIELD);
            ObjectId id = (ObjectId) idObject.get(ID_FIELD);
            return id.toString();
        }

        @Override
        public Integer getVersion() {
            Document idObject = (Document) doc.get(ID_FIELD);
            return (Integer) idObject.get(VERSION_FIELD);
        }

        @Override
        public boolean isDeleted() {
            Boolean deleted = (Boolean) doc.get(DELETED_FIELD);

            return deleted != null && deleted;
        }

        Document getMongoDocument() {
            return doc;
        }
    }
}
