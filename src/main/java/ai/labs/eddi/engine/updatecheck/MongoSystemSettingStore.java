/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.updatecheck;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.ReplaceOptions;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;

import java.util.Date;
import java.util.Optional;

/**
 * MongoDB implementation of {@link ISystemSettingStore}: one document per key
 * in {@code system_settings}, keyed by {@code _id = key}.
 */
@ApplicationScoped
@DefaultBean
public class MongoSystemSettingStore implements ISystemSettingStore {

    static final String COLLECTION = "system_settings";

    private static final String ID = "_id";
    private static final String VALUE = "value";
    private static final String UPDATED_AT = "updatedAt";
    private static final String UPDATED_BY = "updatedBy";

    private final MongoCollection<Document> collection;

    @Inject
    public MongoSystemSettingStore(MongoDatabase database) {
        this.collection = database.getCollection(COLLECTION);
    }

    @Override
    public Optional<String> read(String key) {
        Document document = collection.find(Filters.eq(ID, key)).first();
        return document == null ? Optional.empty() : Optional.ofNullable(document.getString(VALUE));
    }

    @Override
    public void write(String key, String value, String updatedBy) {
        Document document = new Document(ID, key).append(VALUE, value).append(UPDATED_AT, new Date());
        if (updatedBy != null) {
            document.put(UPDATED_BY, updatedBy);
        }
        collection.replaceOne(Filters.eq(ID, key), document, new ReplaceOptions().upsert(true));
    }
}
