package io.sls.persistence.impl.mongo;

import com.mongodb.*;
import com.mongodb.util.JSON;
import io.sls.persistence.IResourceStorage;
import io.sls.serialization.IDocumentBuilder;
import io.sls.serialization.JSONSerialization;
import io.sls.utilities.RuntimeUtilities;
import org.bson.types.ObjectId;

import java.io.IOException;

/**
 * @author ginccc
 */
public class MongoResourceStorage<T> implements IResourceStorage<T> {
    private static final String VERSION_FIELD = "_version";
    private static final String ID_FIELD = "_id";
    private static final String DELETED_FIELD = "_deleted";

    private static final String HISTORY_POSTFIX = ".history";

    private DBCollection currentCollection;
    private DBCollection historyCollection;
    private IDocumentBuilder<T> documentBuilder;

    public MongoResourceStorage(DB database, String collectionName, IDocumentBuilder<T> documentBuilder) {
        RuntimeUtilities.checkNotNull(database, "database");

        this.currentCollection = database.getCollection(collectionName);
        this.historyCollection = database.getCollection(collectionName + HISTORY_POSTFIX);

        this.documentBuilder = documentBuilder;
    }

    @Override
    public IResource<T> newResource(T content) throws IOException {
        BasicDBObject doc = (BasicDBObject) JSON.parse(JSONSerialization.serialize(content));
        doc.put(VERSION_FIELD, 1);
        return new Resource(doc);
    }

    @Override
    public IResource<T> newResource(String id, Integer version, T content) throws IOException {
        BasicDBObject doc = (BasicDBObject) JSON.parse(JSONSerialization.serialize(content));

        Resource resource = new Resource(doc);
        resource.setVersion(version);
        resource.setId(id);

        return resource;
    }

    @Override
    public void store(IResource currentResource) {
        Resource resource = checkInternalResource(currentResource);
        currentCollection.save(resource.getDBObject());
    }

    @Override
    public IResource<T> read(String id, Integer version) {
        BasicDBObject query = new BasicDBObject(ID_FIELD, new ObjectId(id));
        query.put(VERSION_FIELD, version);

        DBObject object = currentCollection.findOne(query);

        if (object == null) {
            return null;
        }

        return new Resource(new BasicDBObject(object.toMap()));
    }

    @Override
    public void remove(String id) {
        currentCollection.remove(new BasicDBObject(ID_FIELD, new ObjectId(id)));
    }

    @Override
    public void removeAllPermanently(String id) {
        remove(id);

        DBObject beginId = new BasicDBObject();
        beginId.put(ID_FIELD, new ObjectId(id));
        beginId.put(VERSION_FIELD, 0);

        DBObject endId = new BasicDBObject();
        endId.put(ID_FIELD, new ObjectId(id));
        endId.put(VERSION_FIELD, Integer.MAX_VALUE);

        DBObject query = new BasicDBObject();
        query.put("$gt", beginId);
        query.put("$lt", endId);
        DBObject object = new BasicDBObject();
        object.put(ID_FIELD, query);
        historyCollection.remove(object);
    }

    @Override
    public IHistoryResource<T> readHistory(String id, Integer version) {
        BasicDBObject objectId = new BasicDBObject(ID_FIELD, new ObjectId(id));
        objectId.put(VERSION_FIELD, version);
        BasicDBObject query = new BasicDBObject(ID_FIELD, objectId);

        DBObject doc = historyCollection.findOne(query);

        if (doc == null) {
            return null;
        }

        return new HistoryResource(doc);
    }

    @Override
    public IHistoryResource<T> readHistoryLatest(String id) {
        DBObject beginId = new BasicDBObject();
        beginId.put(ID_FIELD, new ObjectId(id));
        beginId.put(VERSION_FIELD, 0);

        DBObject endId = new BasicDBObject();
        endId.put(ID_FIELD, new ObjectId(id));
        endId.put(VERSION_FIELD, Integer.MAX_VALUE);

        DBObject query = new BasicDBObject();
        query.put("$gt", beginId);
        query.put("$lt", endId);
        DBObject object = new BasicDBObject();
        object.put(ID_FIELD, query);

        DBCursor objects = historyCollection.find(object).sort(new BasicDBObject(ID_FIELD, -1)).limit(1);

        if (objects.size() == 0) {
            return null;
        }

        return new HistoryResource(objects.next());
    }

    @Override
    public IHistoryResource<T> newHistoryResourceFor(IResource resource, boolean deleted) {
        Resource mongoResource = checkInternalResource(resource);
        BasicDBObject historyObject = (BasicDBObject) mongoResource.getDBObject().copy();

        BasicDBObject idObject = new BasicDBObject();
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
        BasicDBObject query = new BasicDBObject(ID_FIELD, new ObjectId(id));
        DBObject one = currentCollection.findOne(query);
        if (one != null) {
            return (Integer) one.get(VERSION_FIELD);
        } else {
            return -1;
        }
    }

    @Override
    public void store(IHistoryResource resource) {
        HistoryResource historyResource = checkInternalHistoryResource(resource);
        historyCollection.save(historyResource.getDBObject());
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
        private BasicDBObject doc;

        Resource(BasicDBObject doc) {
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
            return documentBuilder.build(doc.toString());
        }

        @Override
        public String getId() {
            return doc.get("_id").toString();
        }

        public void setId(String id) {
            doc.put("_id", new ObjectId(id));
        }

        BasicDBObject getDBObject() {
            return doc;
        }

    }

    private class HistoryResource implements IHistoryResource<T> {
        private BasicDBObject doc;

        HistoryResource(BasicDBObject doc) {
            this.doc = doc;
        }

        HistoryResource(DBObject doc) {
            this.doc = new BasicDBObject(doc.toMap());
        }

        @Override
        public T getData() throws IOException {
            return documentBuilder.build(doc.toString());
        }

        @Override
        public String getId() {
            DBObject idObject = (DBObject) doc.get(ID_FIELD);
            ObjectId id = (ObjectId) idObject.get(ID_FIELD);
            return id.toString();
        }

        @Override
        public Integer getVersion() {
            DBObject idObject = (DBObject) doc.get(ID_FIELD);
            return (Integer) idObject.get(VERSION_FIELD);
        }

        @Override
        public boolean isDeleted() {
            Boolean deleted = (Boolean) doc.get(DELETED_FIELD);

            return deleted != null && deleted;
        }

        BasicDBObject getDBObject() {
            return doc;
        }
    }
}
