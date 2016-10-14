package io.sls.faces.html.impl;

import com.mongodb.BasicDBObject;
import com.mongodb.DB;
import com.mongodb.DBCollection;
import com.mongodb.DBObject;
import com.mongodb.util.JSON;
import io.sls.faces.html.IHtmlFaceStore;
import io.sls.faces.html.model.HtmlFace;
import io.sls.persistence.IResourceStore;
import io.sls.serialization.JSONSerialization;
import org.bson.types.ObjectId;
import org.codehaus.jackson.type.TypeReference;

import javax.inject.Inject;
import java.io.IOException;

/**
 * Copyright by Spoken Language System. All rights reserved.
 * User: jarisch
 * Date: 18.01.13
 * Time: 21:50
 */
public class HtmlFaceStore implements IHtmlFaceStore {
    public static final String COLLECTION_FACES = "faces";
    private final DBCollection collection;

    @Inject
    public HtmlFaceStore(DB database) {
        collection = database.getCollection(COLLECTION_FACES);
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
            return JSONSerialization.serialize(face);
        } catch (IOException e) {
            throw new IResourceStore.ResourceStoreException("Cannot serialize HtmlFace entity into json.");
        }
    }

    @Override
    public void deleteFace(String faceId) {
        collection.remove(new BasicDBObject("_id", new ObjectId(faceId)));
    }

    private HtmlFace convert(DBObject userDocument) throws IResourceStore.ResourceStoreException {
        try {
            return JSONSerialization.deserialize(userDocument.toString(), new TypeReference<HtmlFace>() {
            });
        } catch (IOException e) {
            throw new IResourceStore.ResourceStoreException("Cannot parse json structure into HtmlFace entity.");
        }
    }
}
