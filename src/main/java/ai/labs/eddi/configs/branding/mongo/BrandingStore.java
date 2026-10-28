/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.branding.mongo;

import ai.labs.eddi.configs.branding.BrandingJson;
import ai.labs.eddi.configs.branding.IBrandingStore;
import ai.labs.eddi.configs.branding.model.BrandAsset;
import ai.labs.eddi.configs.branding.model.BrandAssetSlot;
import ai.labs.eddi.configs.branding.model.BrandDocument;
import com.mongodb.ErrorCategory;
import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.ReplaceOptions;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;
import org.bson.types.Binary;

import java.util.Optional;

/**
 * MongoDB implementation of {@link IBrandingStore}.
 * <p>
 * The brand is a single document in {@code branding}; the images live in
 * {@code brandingassets}, one document per slot, so reading the brand never
 * loads image bytes.
 *
 * <pre>
 * branding:       { "_id": "brand", "revision": 7, "productName": "Acme Assist", ... }
 * brandingassets: { "_id": "logo", "hash": "sha256:…", "contentType": "image/svg+xml", "data": BinData }
 * </pre>
 */
@ApplicationScoped
@DefaultBean
public class BrandingStore implements IBrandingStore {

    static final String COLLECTION = "branding";
    static final String ASSET_COLLECTION = "brandingassets";
    private static final String ID = "_id";
    private static final String BRAND_ID = "brand";
    private static final String REVISION = "revision";

    private final MongoCollection<Document> brands;
    private final MongoCollection<Document> assets;

    @Inject
    public BrandingStore(MongoDatabase database) {
        this.brands = database.getCollection(COLLECTION);
        this.assets = database.getCollection(ASSET_COLLECTION);
    }

    @Override
    public Optional<BrandDocument> read() {
        Document doc = brands.find(Filters.eq(ID, BRAND_ID)).first();
        if (doc == null) {
            return Optional.empty();
        }
        doc.remove(ID);
        return Optional.of(BrandingJson.read(doc.toJson()));
    }

    @Override
    public boolean compareAndSet(long expectedRevision, BrandDocument next) {
        Document doc = Document.parse(BrandingJson.write(next));
        doc.put(ID, BRAND_ID);
        doc.put(REVISION, next.revision());
        if (expectedRevision == 0) {
            try {
                brands.insertOne(doc);
                return true;
            } catch (MongoWriteException e) {
                if (e.getError().getCategory() == ErrorCategory.DUPLICATE_KEY) {
                    return false;
                }
                throw e;
            }
        }
        return brands.replaceOne(Filters.and(Filters.eq(ID, BRAND_ID), Filters.eq(REVISION, expectedRevision)), doc)
                .getMatchedCount() == 1;
    }

    @Override
    public Optional<BrandAsset> readAsset(BrandAssetSlot slot) {
        Document doc = assets.find(Filters.eq(ID, slot.id())).first();
        if (doc == null) {
            return Optional.empty();
        }
        Binary data = doc.get("data", Binary.class);
        return Optional.of(new BrandAsset(slot, doc.getString("hash"), doc.getString("contentType"), data.getData()));
    }

    @Override
    public void writeAsset(BrandAsset asset) {
        Document doc = new Document(ID, asset.slot().id())
                .append("hash", asset.hash())
                .append("contentType", asset.contentType())
                .append("data", new Binary(asset.data()));
        assets.replaceOne(Filters.eq(ID, asset.slot().id()), doc, new ReplaceOptions().upsert(true));
    }

    @Override
    public void deleteAsset(BrandAssetSlot slot) {
        assets.deleteOne(Filters.eq(ID, slot.id()));
    }
}
