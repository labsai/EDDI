/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.variables.mongo;

import ai.labs.eddi.configs.variables.IGlobalVariableStore;
import ai.labs.eddi.configs.variables.model.GlobalVariable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
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
 * Uses a dedicated {@code globalvariables} collection with a compound identity
 * of {@code (tenantId, key)}. The MongoDB {@code _id} is formed as
 * {@code tenantId/key} for uniqueness.
 *
 * <pre>
 * { "_id": "default/default-model", "tenantId": "default", "key": "default-model",
 *   "value": "gpt-4.1", "description": "...", "exportable": true }
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
    private static final String FIELD_TENANT_ID = "tenantId";
    private static final String FIELD_KEY = "key";
    private static final String FIELD_VALUE = "value";
    private static final String FIELD_DESCRIPTION = "description";
    private static final String FIELD_EXPORTABLE = "exportable";

    private final MongoCollection<Document> collection;

    @Inject
    public GlobalVariableStore(MongoDatabase database) {
        this.collection = database.getCollection(COLLECTION_NAME);
        LOGGER.infof("GlobalVariableStore initialized (collection=%s)", COLLECTION_NAME);
    }

    /** Composite _id used as the unique document key: {@code tenantId/key}. */
    private static String compositeId(String tenantId, String key) {
        return tenantId + "/" + key;
    }

    @Override
    public Map<String, String> getAll(String tenantId) {
        Map<String, String> result = new LinkedHashMap<>();
        collection.find(Filters.eq(FIELD_TENANT_ID, tenantId))
                .forEach(doc -> result.put(doc.getString(FIELD_KEY), doc.getString(FIELD_VALUE)));
        return result;
    }

    @Override
    public GlobalVariable get(String tenantId, String key) {
        Document doc = collection.find(Filters.eq("_id", compositeId(tenantId, key))).first();
        if (doc == null) {
            return null;
        }
        return toGlobalVariable(doc);
    }

    @Override
    public void upsert(GlobalVariable variable) {
        String id = compositeId(variable.tenantId(), variable.key());
        Document doc = new Document("_id", id)
                .append(FIELD_TENANT_ID, variable.tenantId())
                .append(FIELD_KEY, variable.key())
                .append(FIELD_VALUE, variable.value())
                .append(FIELD_DESCRIPTION, variable.description())
                .append(FIELD_EXPORTABLE, variable.exportable());

        collection.replaceOne(
                new Document("_id", id),
                doc,
                new ReplaceOptions().upsert(true));
    }

    @Override
    public void delete(String tenantId, String key) {
        collection.deleteOne(Filters.eq("_id", compositeId(tenantId, key)));
    }

    @Override
    public List<GlobalVariable> listAll(String tenantId) {
        List<GlobalVariable> result = new ArrayList<>();
        collection.find(Filters.eq(FIELD_TENANT_ID, tenantId))
                .forEach(doc -> result.add(toGlobalVariable(doc)));
        return result;
    }

    private static GlobalVariable toGlobalVariable(Document doc) {
        return new GlobalVariable(
                doc.getString(FIELD_TENANT_ID),
                doc.getString(FIELD_KEY),
                doc.getString(FIELD_VALUE),
                doc.getString(FIELD_DESCRIPTION),
                doc.getBoolean(FIELD_EXPORTABLE, true));
    }
}
