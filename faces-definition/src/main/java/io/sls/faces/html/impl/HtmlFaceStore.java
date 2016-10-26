package io.sls.faces.html.impl;

import com.mongodb.BasicDBObject;
import com.mongodb.DB;
import com.mongodb.DBCollection;
import com.mongodb.DBObject;
import com.mongodb.util.JSON;
import io.sls.faces.html.IHtmlFaceStore;
import io.sls.faces.html.model.HtmlFace;
import io.sls.persistence.IResourceStore;
import io.sls.serialization.IJsonSerialization;
import lombok.extern.slf4j.Slf4j;
import org.bson.types.ObjectId;

import javax.inject.Inject;
import java.io.IOException;

/**
 * @author ginccc
 */
@Slf4j
public class HtmlFaceStore implements IHtmlFaceStore {
    private static final String COLLECTION_FACES = "faces";
    private final DBCollection collection;
    private final IJsonSerialization jsonSerialization;

    @Inject
    public HtmlFaceStore(DB database, IJsonSerialization jsonSerialization) {
        collection = database.getCollection(COLLECTION_FACES);
        this.jsonSerialization = jsonSerialization;
    }

    @Override
    public HtmlFace searchFaceByHost(String host) throws IResourceStore.ResourceNotFoundException, IResourceStore.ResourceStoreException {
        DBObject faceDocument = collection.findOne(new BasicDBObject("host", host));

        if (faceDocument == null) {
            String message = "Resource 'HtmlFace' not found. (host=%s)";
            message = String.format(message, host);
            throw new IResourceStore.ResourceNotFoundException(message);
        }

        return convert(faceDocument);
    }

    @Override
    public HtmlFace searchFaceByBotId(String botId) throws IResourceStore.ResourceNotFoundException, IResourceStore.ResourceStoreException {
        DBObject faceDocument = collection.findOne(new BasicDBObject("botId", botId));

        if (faceDocument == null) {
            String message = "Resource 'HtmlFace' not found. (botId=%s)";
            message = String.format(message, botId);
            throw new IResourceStore.ResourceNotFoundException(message);
        }

        return convert(faceDocument);
    }

    @Override
    public HtmlFace readFace(String faceId) throws IResourceStore.ResourceNotFoundException, IResourceStore.ResourceStoreException {
        DBObject faceDocument = collection.findOne(new BasicDBObject("_id", new ObjectId(faceId)));

        if (faceDocument == null) {
            String message = "Resource 'HtmlFace' not found. (faceId=%s)";
            message = String.format(message, faceId);
            throw new IResourceStore.ResourceNotFoundException(message);
        }

        return convert(faceDocument);
    }

    @Override
    public void updateFace(String faceId, HtmlFace face) throws IResourceStore.ResourceStoreException {
        String jsonFace = serialize(face);
        DBObject document = (DBObject) JSON.parse(jsonFace);

        document.put("_id", new ObjectId(faceId));

        collection.save(document);
    }

    @Override
    public String createFace(HtmlFace face) throws IResourceStore.ResourceStoreException {
        String jsonFace = serialize(face);
        DBObject document = (DBObject) JSON.parse(jsonFace);

        collection.insert(document);

        return document.get("_id").toString();
    }

    private String serialize(HtmlFace face) throws IResourceStore.ResourceStoreException {
        try {
            return jsonSerialization.serialize(face);
        } catch (IOException e) {
            log.debug(e.getLocalizedMessage(), e);
            throw new IResourceStore.ResourceStoreException("Cannot serialize HtmlFace entity into json.", e);
        }
    }

    @Override
    public void deleteFace(String faceId) {
        collection.remove(new BasicDBObject("_id", new ObjectId(faceId)));
    }

    private HtmlFace convert(DBObject userDocument) throws IResourceStore.ResourceStoreException {
        try {
            return jsonSerialization.deserialize(userDocument.toString(), HtmlFace.class);
        } catch (IOException e) {
            log.debug(e.getLocalizedMessage(), e);
            throw new IResourceStore.ResourceStoreException("Cannot parse json structure into HtmlFace entity.", e);
        }
    }
}
