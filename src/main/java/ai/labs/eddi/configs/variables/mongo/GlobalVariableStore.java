/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.variables.mongo;

import ai.labs.eddi.configs.variables.IGlobalVariableStore;
import ai.labs.eddi.configs.variables.model.GlobalVariable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.ReplaceOptions;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;
import org.jboss.logging.Logger;

import java.util.*;

/**
 * MongoDB implementation of {@link IGlobalVariableStore}.
 * <p>
 * Uses a dedicated {@code globalvariables} collection with {@code _id} as the
 * variable key. Documents are flat:
 *
 * <pre>
 * { "_id": "default-model", "value": "gpt-4.1", "description": "...", "exportable": true }
 * </pre>
 *
 * No versioning — this is operational deployment config, not agent definitions.
 *
 * @author ginccc
 * @since 6.0.0
 */
@ApplicationScoped
@DefaultBean
public class GlobalVariableStore implements IGlobalVariableStore {

    private static final Logger LOGGER = Logger.getLogger(GlobalVariableStore.class);
    private static final String COLLECTION_NAME = "globalvariables";
    private static final String FIELD_VALUE = "value";
    private static final String FIELD_DESCRIPTION = "description";
    private static final String FIELD_EXPORTABLE = "exportable";

    private final MongoCollection<Document> collection;

    @Inject
    public GlobalVariableStore(MongoDatabase database) {
        this.collection = database.getCollection(COLLECTION_NAME);
        LOGGER.infof("GlobalVariableStore initialized (collection=%s)", COLLECTION_NAME);
    }

    @Override
    public Map<String, String> getAll() {
        Map<String, String> result = new LinkedHashMap<>();
        collection.find().forEach(doc -> result.put(doc.getString("_id"), doc.getString(FIELD_VALUE)));
        return result;
    }

    @Override
    public GlobalVariable get(String key) {
        Document doc = collection.find(new Document("_id", key)).first();
        if (doc == null) {
            return null;
        }
        return toGlobalVariable(doc);
    }

    @Override
    public void upsert(GlobalVariable variable) {
        Document doc = new Document("_id", variable.key())
                .append(FIELD_VALUE, variable.value())
                .append(FIELD_DESCRIPTION, variable.description())
                .append(FIELD_EXPORTABLE, variable.exportable());

        collection.replaceOne(
                new Document("_id", variable.key()),
                doc,
                new ReplaceOptions().upsert(true));
    }

    @Override
    public void delete(String key) {
        collection.deleteOne(new Document("_id", key));
    }

    @Override
    public List<GlobalVariable> listAll() {
        List<GlobalVariable> result = new ArrayList<>();
        collection.find().forEach(doc -> result.add(toGlobalVariable(doc)));
        return result;
    }

    private static GlobalVariable toGlobalVariable(Document doc) {
        return new GlobalVariable(
                doc.getString("_id"),
                doc.getString(FIELD_VALUE),
                doc.getString(FIELD_DESCRIPTION),
                doc.getBoolean(FIELD_EXPORTABLE, true));
    }
}
