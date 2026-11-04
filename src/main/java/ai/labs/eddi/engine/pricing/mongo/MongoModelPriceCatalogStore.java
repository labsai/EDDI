/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.pricing.mongo;

import ai.labs.eddi.engine.pricing.IModelPriceCatalogStore;
import ai.labs.eddi.engine.pricing.ModelPriceCatalogJson;
import ai.labs.eddi.engine.pricing.model.ModelPriceCatalog;
import com.mongodb.ErrorCategory;
import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;

import java.util.Optional;

/**
 * MongoDB implementation of {@link IModelPriceCatalogStore}.
 *
 * <pre>
 * modelpricecatalog: { "_id": "default", "revision": 7, "doc": "{...catalog JSON...}" }
 * </pre>
 *
 * The catalog is kept as the same JSON string the PostgreSQL store holds, so
 * both backends read back an identical document and a price never round-trips
 * through BSON number types.
 */
@ApplicationScoped
@DefaultBean
public class MongoModelPriceCatalogStore implements IModelPriceCatalogStore {

    public static final String COLLECTION = "modelpricecatalog";
    private static final String ID = "_id";
    private static final String DEFAULT_ID = "default";
    private static final String REVISION = "revision";
    private static final String DOC = "doc";

    private final MongoCollection<Document> collection;

    @Inject
    public MongoModelPriceCatalogStore(MongoDatabase database) {
        this.collection = database.getCollection(COLLECTION);
    }

    @Override
    public Optional<ModelPriceCatalog> read() {
        Document doc = collection.find(Filters.eq(ID, DEFAULT_ID)).first();
        if (doc == null || doc.getString(DOC) == null) {
            return Optional.empty();
        }
        return Optional.of(ModelPriceCatalogJson.read(doc.getString(DOC)));
    }

    @Override
    public boolean compareAndSet(long expectedRevision, ModelPriceCatalog next) {
        Document doc = new Document(ID, DEFAULT_ID).append(REVISION, next.revision()).append(DOC, ModelPriceCatalogJson.write(next));
        if (expectedRevision == 0) {
            try {
                collection.insertOne(doc);
                return true;
            } catch (MongoWriteException e) {
                if (e.getError().getCategory() == ErrorCategory.DUPLICATE_KEY) {
                    return false;
                }
                throw e;
            }
        }
        return collection.replaceOne(Filters.and(Filters.eq(ID, DEFAULT_ID), Filters.eq(REVISION, expectedRevision)), doc)
                .getMatchedCount() == 1;
    }
}
