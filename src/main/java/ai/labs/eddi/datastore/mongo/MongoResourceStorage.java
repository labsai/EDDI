package ai.labs.eddi.datastore.mongo;

import ai.labs.eddi.datastore.IResourceFilter;
import ai.labs.eddi.datastore.IResourceStorage;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.serialization.IDocumentBuilder;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.UpdateOptions;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import static ai.labs.eddi.utils.RuntimeUtilities.checkNotNull;

/**
 * @author ginccc
 */
public class MongoResourceStorage<T> implements IResourceStorage<T> {
    public static final String VERSION_FIELD = "_version";
    public static final String ID_FIELD = "_id";
    private static final String DELETED_FIELD = "_deleted";

    private static final String HISTORY_POSTFIX = ".history";
    private final Class<T> documentType;

    protected MongoCollection<Document> currentCollection;
    protected MongoCollection<Document> historyCollection;
    protected IDocumentBuilder documentBuilder;

    public MongoResourceStorage(MongoDatabase database, String collectionName,
                                IDocumentBuilder documentBuilder,
                                Class<T> documentType) {
        this(database, collectionName, documentBuilder, documentType, new String[0]);
    }

    public MongoResourceStorage(MongoDatabase database, String collectionName,
                                IDocumentBuilder documentBuilder,
                                Class<T> documentType, String... indexes) {
        checkNotNull(database, "database");

        this.documentType = documentType;
        this.currentCollection = database.getCollection(collectionName);
        this.historyCollection = database.getCollection(collectionName + HISTORY_POSTFIX);
        this.documentBuilder = documentBuilder;

        ensureIndex(currentCollection, Indexes.ascending(ID_FIELD, VERSION_FIELD), true);

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

    @Override
    public void store(IResource currentResource) {
        Resource resource = checkInternalResource(currentResource);
        if (resource.getId() == null) {
            currentCollection.insertOne(resource.getMongoDocument());
        } else {
            currentCollection.updateOne(
                    Filters.eq("_id", new ObjectId(resource.getId())),
                    new Document("$set", resource.getMongoDocument()),
                    new UpdateOptions().upsert(true));
        }
    }

    @Override
    public void createNew(IResource currentResource) {
        Resource resource = checkInternalResource(currentResource);
        currentCollection.insertOne(resource.getMongoDocument());
    }


    @Override
    public IResource<T> read(String id, Integer version) {
        Document query = new Document(ID_FIELD, new ObjectId(id));
        query.put(VERSION_FIELD, version);

        Document document = currentCollection.find(query).first();
        if (document == null) {
            return null;
        }
        return new Resource(document);
    }

    @Override
    public void remove(String id) {
        currentCollection.deleteOne(new Document(ID_FIELD, new ObjectId(id)));
    }

    @Override
    public void removeAllPermanently(String id) {
        remove(id);

        Document beginId = new Document();
        beginId.put(ID_FIELD, new ObjectId(id));
        beginId.put(VERSION_FIELD, 0);

        Document endId = new Document();
        endId.put(ID_FIELD, new ObjectId(id));
        endId.put(VERSION_FIELD, Integer.MAX_VALUE);

        Document query = new Document();
        query.put("$gt", beginId);
        query.put("$lt", endId);
        Document idQuery = new Document();
        idQuery.put(ID_FIELD, query);
        historyCollection.deleteMany(idQuery);
    }

    @Override
    public IHistoryResource<T> readHistory(String id, Integer version) {
        Document objectId = new Document(ID_FIELD, new ObjectId(id));
        objectId.put(VERSION_FIELD, version);

        Document doc = historyCollection.find(Filters.eq(ID_FIELD, objectId)).first();
        if (doc == null) {
            return null;
        }
        return new HistoryResource(doc);
    }

    @Override
    public IHistoryResource<T> readHistoryLatest(String id) {
        Document beginId = new Document();
        beginId.put(ID_FIELD, new ObjectId(id));
        beginId.put(VERSION_FIELD, 0);

        Document endId = new Document();
        endId.put(ID_FIELD, new ObjectId(id));
        endId.put(VERSION_FIELD, Integer.MAX_VALUE);

        Document query = new Document();
        query.put("$gt", beginId);
        query.put("$lt", endId);
        Document object = new Document();
        object.put(ID_FIELD, query);

        if (historyCollection.countDocuments(object) == 0) {
            return null;
        }

        Document doc = historyCollection.find(object).sort(new Document(ID_FIELD, -1)).limit(1).first();
        if (doc == null) {
            return null;
        }
        return new HistoryResource(doc);
    }

    @Override
    public IHistoryResource<T> newHistoryResourceFor(IResource resource, boolean deleted) {
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
        Document query = new Document(ID_FIELD, new ObjectId(id));
        Document one = currentCollection.find(query).first();
        if (one == null) {
            return -1;
        }
        return (Integer) one.get(VERSION_FIELD);
    }

    @Override
    public void store(IHistoryResource resource) {
        HistoryResource historyResource = checkInternalHistoryResource(resource);
        historyCollection.insertOne(historyResource.getMongoDocument());
    }

    @Override
    public List<IResourceStore.IResourceId> findResourceIdsContaining(String jsonPath, String value) {
        Document filter = new Document(jsonPath,
                new Document("$in", java.util.Collections.singletonList(value)));

        List<IResourceStore.IResourceId> results = new java.util.LinkedList<>();
        currentCollection.find(filter).forEach(doc -> {
            String docId = doc.getObjectId(ID_FIELD).toString();
            Integer version = doc.getInteger(VERSION_FIELD);
            results.add(createResourceId(docId, version));
        });
        return results;
    }

    @Override
    public List<IResourceStore.IResourceId> findHistoryResourceIdsContaining(String jsonPath, String value) {
        Document filter = new Document(jsonPath,
                new Document("$in", java.util.Collections.singletonList(value)));

        List<IResourceStore.IResourceId> results = new java.util.LinkedList<>();
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
    public List<IResourceStore.IResourceId> findResources(
            IResourceFilter.QueryFilters[] allQueryFilters, String sortField, int skip, int limit) {

        List<Bson> connectedFilters = new java.util.ArrayList<>();
        for (IResourceFilter.QueryFilters queryFilters : allQueryFilters) {
            List<Bson> filters = new java.util.ArrayList<>();
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
        int effectiveLimit = limit < 1 ? 20 : limit;

        var iterable = currentCollection.find(query.toBsonDocument()).sort(sort)
                .limit(effectiveLimit).skip(skip > 0 ? skip : 0);

        List<IResourceStore.IResourceId> results = new java.util.LinkedList<>();
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
            public String getId() { return id; }
            @Override
            public Integer getVersion() { return version; }
        };
    }

    private Resource checkInternalResource(IResource currentResource) {
        if (!(currentResource instanceof MongoResourceStorage.Resource)) {
            throw new IllegalArgumentException("Resource must not be implemented externally.");
        }
        return (Resource) currentResource;
    }

    private HistoryResource checkInternalHistoryResource(IHistoryResource resource) {
        if (!(resource instanceof MongoResourceStorage.HistoryResource)) {
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
