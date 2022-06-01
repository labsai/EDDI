package ai.labs.eddi.datastore.mongo;

import ai.labs.eddi.datastore.IResourceStorage;
import ai.labs.eddi.datastore.serialization.IDocumentBuilder;
import ai.labs.eddi.utils.RuntimeUtilities;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.reactivestreams.client.MongoCollection;
import com.mongodb.reactivestreams.client.MongoDatabase;
import io.reactivex.rxjava3.core.Observable;
import org.bson.Document;
import org.bson.types.ObjectId;

import java.io.IOException;
import java.util.NoSuchElementException;

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
        this.documentType = documentType;
        RuntimeUtilities.checkNotNull(database, "database");

        this.currentCollection = database.getCollection(collectionName);
        this.historyCollection = database.getCollection(collectionName + HISTORY_POSTFIX);
        //this.historyCollection.createIndex(Indexes.ascending(ID_FIELD, VERSION_FIELD));
        this.documentBuilder = documentBuilder;
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
            Observable.fromPublisher(currentCollection.insertOne(resource.getMongoDocument())).blockingFirst();
        } else {
            Observable.fromPublisher(currentCollection.updateOne(
                    Filters.eq("_id", new ObjectId(resource.getId())),
                    new Document("$set", resource.getMongoDocument()),
                    new UpdateOptions().upsert(true))).blockingFirst();
        }
    }

    @Override
    public void createNew(IResource currentResource) {
        Resource resource = checkInternalResource(currentResource);
        Observable.fromPublisher(currentCollection.insertOne(resource.getMongoDocument())).blockingFirst();
    }


    @Override
    public IResource<T> read(String id, Integer version) {
        Document query = new Document(ID_FIELD, new ObjectId(id));
        query.put(VERSION_FIELD, version);

        try {
            Observable<Document> observable = Observable.fromPublisher(currentCollection.find(query).first());
            Document document = observable.blockingFirst();
            return new Resource(document);
        } catch (NoSuchElementException ne) {
            return null;
        }
    }

    @Override
    public void remove(String id) {
        Observable.fromPublisher(currentCollection.deleteOne(new Document(ID_FIELD, new ObjectId(id)))).blockingFirst();
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
        Document object = new Document();
        object.put(ID_FIELD, query);
        Observable.fromPublisher(historyCollection.deleteOne(object)).blockingFirst();
    }

    @Override
    public IHistoryResource<T> readHistory(String id, Integer version) {
        Document objectId = new Document(ID_FIELD, new ObjectId(id));
        objectId.put(VERSION_FIELD, version);

        try {
            Observable<Document> observable = Observable.fromPublisher(historyCollection.find(Filters.eq(ID_FIELD, objectId)).first());
            Document doc = observable.blockingFirst();
            return new HistoryResource(doc);
        } catch (NoSuchElementException ne) {
            return null;
        }
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

        if (Observable.fromPublisher(historyCollection.countDocuments(object)).blockingFirst() == 0) {
            return null;
        }


        Document doc = Observable.fromPublisher(historyCollection.find(object).sort(new Document(ID_FIELD, -1)).limit(1)).blockingFirst();
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
        try {
            Document one = Observable.fromPublisher(currentCollection.find(query).first()).blockingFirst();
            return (Integer) one.get(VERSION_FIELD);
        } catch (NoSuchElementException ne) {
            return -1;
        }
    }

    @Override
    public void store(IHistoryResource resource) {
        HistoryResource historyResource = checkInternalHistoryResource(resource);
        Observable.fromPublisher(historyCollection.insertOne(historyResource.getMongoDocument())).blockingFirst();
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
