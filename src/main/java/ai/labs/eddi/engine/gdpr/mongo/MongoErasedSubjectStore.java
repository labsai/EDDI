/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.gdpr.mongo;

import ai.labs.eddi.engine.gdpr.IErasedSubjectStore;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.UpdateOptions;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;

import java.time.Instant;
import java.util.Collection;
import java.util.Optional;

import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Filters.in;
import static com.mongodb.client.model.Updates.max;

/**
 * MongoDB implementation of {@link IErasedSubjectStore}: collection
 * {@code gdpr_erased_subjects}, {@code _id} = the token, {@code erasedAt} in
 * epoch milliseconds. The write is an upsert with {@code $max}, so it is
 * idempotent and two concurrent erasures of one person keep the later time.
 *
 * @since 6.7.0
 */
@ApplicationScoped
@DefaultBean
public class MongoErasedSubjectStore implements IErasedSubjectStore {

    static final String COLLECTION = "gdpr_erased_subjects";
    static final String ID = "_id";
    static final String ERASED_AT = "erasedAt";

    private final MongoCollection<Document> collection;

    @Inject
    public MongoErasedSubjectStore(MongoDatabase database) {
        this.collection = database.getCollection(COLLECTION);
    }

    @Override
    public void record(String token, Instant erasedAt) {
        collection.updateOne(eq(ID, token), max(ERASED_AT, erasedAt.toEpochMilli()), new UpdateOptions().upsert(true));
    }

    @Override
    public Optional<Instant> erasedAt(Collection<String> tokens) {
        if (tokens == null || tokens.isEmpty()) {
            return Optional.empty();
        }
        Long latest = null;
        for (Document doc : collection.find(in(ID, tokens))) {
            if (doc.get(ERASED_AT) instanceof Number n && (latest == null || n.longValue() > latest)) {
                latest = n.longValue();
            }
        }
        return latest == null ? Optional.empty() : Optional.of(Instant.ofEpochMilli(latest));
    }
}
