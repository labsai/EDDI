/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.email.ledger;

import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.Sorts;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * MongoDB implementation of {@link IEmailSuppressionStore}: collection
 * {@code email_suppressions}, one document per {@code (integration, address
 * hash)} with that pair as its {@code _id}, so suppressing twice is a duplicate
 * key that changes nothing.
 */
@ApplicationScoped
@DefaultBean
public class MongoEmailSuppressionStore implements IEmailSuppressionStore {

    static final String COLLECTION = "email_suppressions";

    private static final int DUPLICATE_KEY_ERROR_CODE = 11000;

    static final String F_ID = "_id";
    static final String F_INTEGRATION = "integrationId";
    static final String F_HASH = "addressHash";
    static final String F_DOMAIN = "domain";
    static final String F_REASON = "reason";
    static final String F_CREATED = "createdAt";

    private final MongoCollection<Document> entries;

    @Inject
    public MongoEmailSuppressionStore(MongoDatabase database) {
        this.entries = database.getCollection(COLLECTION);
        entries.createIndex(Indexes.compoundIndex(Indexes.ascending(F_INTEGRATION), Indexes.descending(F_CREATED)),
                new IndexOptions().name("idx_email_suppressions_integration"));
        entries.createIndex(Indexes.ascending(F_HASH), new IndexOptions().name("idx_email_suppressions_hash"));
    }

    private static String id(String integrationId, String hash) {
        return integrationId + "|" + hash;
    }

    @Override
    public boolean suppress(String integrationId, String normalizedAddress, String reason, Instant now) {
        String hash = IEmailSuppressionStore.hash(normalizedAddress);
        try {
            entries.insertOne(new Document(F_ID, id(integrationId, hash)).append(F_INTEGRATION, integrationId).append(F_HASH, hash)
                    .append(F_DOMAIN, IEmailSuppressionStore.domainOf(normalizedAddress)).append(F_REASON, reason).append(F_CREATED, Date.from(now)));
            return true;
        } catch (MongoWriteException e) {
            if (e.getError().getCode() == DUPLICATE_KEY_ERROR_CODE) {
                return false;
            }
            throw e;
        }
    }

    @Override
    public boolean isSuppressed(String integrationId, String normalizedAddress) {
        return entries.countDocuments(Filters.eq(F_ID, id(integrationId, IEmailSuppressionStore.hash(normalizedAddress)))) > 0;
    }

    @Override
    public List<Suppression> list(String integrationId, int limit) {
        List<Suppression> result = new ArrayList<>();
        for (Document document : entries.find(Filters.eq(F_INTEGRATION, integrationId)).sort(Sorts.descending(F_CREATED)).limit(limit)) {
            result.add(toSuppression(document));
        }
        return result;
    }

    @Override
    public boolean remove(String integrationId, String normalizedAddress) {
        return entries.deleteOne(Filters.eq(F_ID, id(integrationId, IEmailSuppressionStore.hash(normalizedAddress)))).getDeletedCount() == 1;
    }

    @Override
    public long removeAll(String integrationId) {
        return entries.deleteMany(Filters.eq(F_INTEGRATION, integrationId)).getDeletedCount();
    }

    @Override
    public List<Suppression> findByAddress(String normalizedAddress) {
        List<Suppression> result = new ArrayList<>();
        for (Document document : entries.find(Filters.eq(F_HASH, IEmailSuppressionStore.hash(normalizedAddress)))) {
            result.add(toSuppression(document));
        }
        return result;
    }

    @Override
    public long deleteByAddress(String normalizedAddress) {
        return entries.deleteMany(Filters.eq(F_HASH, IEmailSuppressionStore.hash(normalizedAddress))).getDeletedCount();
    }

    private static Suppression toSuppression(Document document) {
        Date created = document.getDate(F_CREATED);
        return new Suppression(document.getString(F_INTEGRATION), document.getString(F_HASH), document.getString(F_DOMAIN),
                document.getString(F_REASON), created == null ? null : created.toInstant());
    }
}
