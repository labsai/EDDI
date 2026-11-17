/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.spendcontrol.mongo;

import ai.labs.eddi.engine.spendcontrol.ISpendControlChangeStore;
import ai.labs.eddi.engine.spendcontrol.SpendControlChangeJson;
import ai.labs.eddi.engine.spendcontrol.model.SpendControlChange;
import ai.labs.eddi.engine.spendcontrol.model.SpendControlChange.Status;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;
import org.bson.conversions.Bson;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * MongoDB implementation of {@link ISpendControlChangeStore}.
 *
 * <pre>
 * spendcontrolchanges: { "_id": "…", "status": "PENDING", "proposedAt": 1731150000000, "doc": "{…change JSON…}" }
 * </pre>
 */
@ApplicationScoped
@DefaultBean
public class MongoSpendControlChangeStore implements ISpendControlChangeStore {

    public static final String COLLECTION = "spendcontrolchanges";
    private static final String ID = "_id";
    private static final String STATUS = "status";
    private static final String PROPOSED_AT = "proposedAt";
    private static final String DOC = "doc";

    private final MongoCollection<Document> collection;

    @Inject
    public MongoSpendControlChangeStore(MongoDatabase database) {
        this.collection = database.getCollection(COLLECTION);
    }

    private static Document toDocument(SpendControlChange change) {
        return new Document(ID, change.id()).append(STATUS, change.status().name()).append(PROPOSED_AT, change.proposedAtMillis()).append(DOC,
                SpendControlChangeJson.write(change));
    }

    @Override
    public void create(SpendControlChange change) {
        collection.insertOne(toDocument(change));
    }

    @Override
    public Optional<SpendControlChange> read(String id) {
        Document doc = collection.find(Filters.eq(ID, id)).first();
        return doc == null || doc.getString(DOC) == null ? Optional.empty() : Optional.of(SpendControlChangeJson.read(doc.getString(DOC)));
    }

    @Override
    public List<SpendControlChange> list(Status status, int limit) {
        Bson filter = status == null ? new Document() : Filters.eq(STATUS, status.name());
        List<SpendControlChange> result = new ArrayList<>();
        for (Document doc : collection.find(filter).sort(Sorts.descending(PROPOSED_AT)).limit(limit)) {
            if (doc.getString(DOC) != null) {
                result.add(SpendControlChangeJson.read(doc.getString(DOC)));
            }
        }
        return result;
    }

    @Override
    public long count(Status status) {
        return collection.countDocuments(Filters.eq(STATUS, status.name()));
    }

    @Override
    public boolean compareAndSetStatus(String id, Status expected, SpendControlChange next) {
        return collection.replaceOne(Filters.and(Filters.eq(ID, id), Filters.eq(STATUS, expected.name())), toDocument(next)).getMatchedCount() == 1;
    }

    @Override
    public long deleteDecidedBefore(long proposedBeforeMillis) {
        return collection.deleteMany(Filters.and(Filters.nin(STATUS, Status.PENDING.name(), Status.APPLYING.name()),
                Filters.lt(PROPOSED_AT, proposedBeforeMillis))).getDeletedCount();
    }
}
