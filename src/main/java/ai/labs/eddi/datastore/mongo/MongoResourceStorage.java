/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.mongo;

import ai.labs.eddi.datastore.IResourceFilter;
import ai.labs.eddi.datastore.IResourceStorage;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.serialization.IDocumentBuilder;
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
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import static ai.labs.eddi.utils.RuntimeUtilities.checkNotNull;

/**
 * @author ginccc
 */
public class MongoResourceStorage<T> implements IResourceStorage<T> {
    private static final Logger LOGGER = Logger.getLogger(MongoResourceStorage.class);
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

        ensureIndex(currentCollection, Indexes.ascending(ID_FIELD, VERSION_FIELD), true);
        // History rows are addressed by the NESTED id, not by a range over the
        // composite _id (see historyRowsOf). MongoDB's built-in _id index covers the
        // whole embedded subdocument and cannot serve a dotted path into it, so
        // without this index removeAllPermanently and readHistoryLatest are a
        // COLLSCAN of the history collection. That is not academic: descriptors.history
        // holds one row per conversation, and GdprComplianceService's erasure loop
        // calls deleteAllDescriptor once per conversation.
        ensureIndex(historyCollection, Indexes.ascending(HISTORY_NESTED_ID_FIELD, HISTORY_NESTED_VERSION_FIELD), false);

        Arrays.stream(indexes).forEach(index -> {
            ensureIndex(currentCollection, Indexes.ascending(index), false);
            ensureIndex(historyCollection, Indexes.ascending(index), false);
        });
    }

    private void ensureIndex(MongoCollection<Document> mongoCollection, Bson indexKey, boolean unique) {
        mongoCollection.createIndex(indexKey, new IndexOptions().unique(unique));
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
        Resource resource = checkInternalResource(newResource);
        var result = currentCollection.replaceOne(
                Filters.and(
                        Filters.eq(ID_FIELD, new ObjectId(resource.getId())),
                        Filters.eq(fieldName, expectedValue)),
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
            LOGGER.warnf("Could not clear a stale deleted flag on history row %s: %s", historyRowId, e.getMessage());
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
        durableHistory.updateOne(historyRow, Updates.unset(DELETED_FIELD));
        throw new IResourceStore.ResourceModifiedException(
                String.format("Resource was modified concurrently (id=%s, expected version=%d)", id, expectedCurrentVersion));
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
            LOGGER.warnf("Delete of %s v%d failed and its tombstone could not be checked: %s", id, version, e.getMessage());
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
                if (queryFilter.getFilter() instanceof String) {
                    filters.add(Filters.regex(queryFilter.getField(), queryFilter.getFilter().toString()));
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
