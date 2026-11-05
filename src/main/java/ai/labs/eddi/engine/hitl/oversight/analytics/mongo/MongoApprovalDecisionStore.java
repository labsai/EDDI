/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl.oversight.analytics.mongo;

import ai.labs.eddi.engine.hitl.oversight.OversightJson;
import ai.labs.eddi.engine.hitl.oversight.analytics.ApprovalDecision;
import ai.labs.eddi.engine.hitl.oversight.analytics.DecisionFilter;
import ai.labs.eddi.engine.hitl.oversight.analytics.IApprovalDecisionStore;
import com.mongodb.MongoException;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.Sorts;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;
import org.bson.conversions.Bson;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Filters.exists;
import static com.mongodb.client.model.Filters.gte;
import static com.mongodb.client.model.Filters.lt;
import static com.mongodb.client.model.Filters.or;
import static com.mongodb.client.model.Updates.set;

/**
 * MongoDB implementation of {@link IApprovalDecisionStore}: collection
 * {@code approval_decisions}. The indexed fields are top-level; the rest of the
 * row is the JSON in {@code data} (without {@code userId} and
 * {@code decidedBy}, which live only in their own fields so an erasure is a
 * plain field update). Times are epoch milliseconds, as in the PostgreSQL
 * table.
 *
 * @since 6.7.0
 */
@ApplicationScoped
@DefaultBean
public class MongoApprovalDecisionStore implements IApprovalDecisionStore {

    static final String COLLECTION = "approval_decisions";
    private static final int DUPLICATE_KEY = 11000;

    static final String ID = "_id";
    static final String AGENT_ID = "agentId";
    static final String GROUP_ID = "groupId";
    static final String CATEGORY = "category";
    static final String DECIDED_AT = "decidedAt";
    static final String SURFACE = "surface";
    static final String PAUSE_TYPE = "pauseType";
    static final String CHANNEL = "channel";
    static final String AGENT_VERSION = "agentVersion";
    static final String USER_ID = "userId";
    static final String DECIDED_BY = "decidedBy";
    static final String DATA = "data";

    private final MongoCollection<Document> collection;

    @Inject
    public MongoApprovalDecisionStore(MongoDatabase database) {
        this.collection = database.getCollection(COLLECTION);
        collection.createIndex(Indexes.compoundIndex(Indexes.ascending(AGENT_ID), Indexes.ascending(CATEGORY), Indexes.descending(DECIDED_AT)),
                new IndexOptions().name("idx_decision_agent_category"));
        collection.createIndex(Indexes.compoundIndex(Indexes.ascending(GROUP_ID), Indexes.ascending(CATEGORY), Indexes.descending(DECIDED_AT)),
                new IndexOptions().name("idx_decision_group_category").partialFilterExpression(exists(GROUP_ID, true)));
        collection.createIndex(Indexes.descending(DECIDED_AT), new IndexOptions().name("idx_decision_decided"));
        collection.createIndex(Indexes.ascending(USER_ID), new IndexOptions().name("idx_decision_user").sparse(true));
        collection.createIndex(Indexes.ascending(DECIDED_BY), new IndexOptions().name("idx_decision_decider"));
    }

    @Override
    public boolean insertIfAbsent(ApprovalDecision decision) {
        try {
            collection.insertOne(toDocument(decision));
            return true;
        } catch (MongoException e) {
            if (e.getCode() == DUPLICATE_KEY) {
                return false;
            }
            throw e;
        }
    }

    @Override
    public List<ApprovalDecision> query(DecisionFilter filter, int skip, int limit) {
        List<ApprovalDecision> rows = new ArrayList<>();
        for (Document doc : collection.find(toBson(filter)).sort(Sorts.orderBy(Sorts.descending(DECIDED_AT), Sorts.descending(ID)))
                .skip(Math.max(0, skip)).limit(Math.max(0, limit)).batchSize(1000)) {
            rows.add(fromDocument(doc));
        }
        return rows;
    }

    @Override
    public long count(DecisionFilter filter) {
        return collection.countDocuments(toBson(filter));
    }

    @Override
    public long deleteDecidedBefore(Instant cutoff) {
        return collection.deleteMany(lt(DECIDED_AT, cutoff.toEpochMilli())).getDeletedCount();
    }

    @Override
    public List<ApprovalDecision> readByUser(String userId, int limit) {
        List<ApprovalDecision> rows = new ArrayList<>();
        for (Document doc : collection.find(or(eq(USER_ID, userId), eq(DECIDED_BY, userId))).limit(Math.max(0, limit))) {
            rows.add(fromDocument(doc));
        }
        return rows;
    }

    @Override
    public long eraseUser(String userId, String pseudonym) {
        long changed = collection.updateMany(eq(USER_ID, userId), set(USER_ID, null)).getModifiedCount();
        changed += collection.updateMany(eq(DECIDED_BY, userId), set(DECIDED_BY, pseudonym)).getModifiedCount();
        return changed;
    }

    public static Document toDocument(ApprovalDecision d) {
        Document doc = new Document(ID, d.id());
        doc.put(AGENT_ID, d.agentId());
        if (d.groupId() != null) {
            doc.put(GROUP_ID, d.groupId());
        }
        doc.put(CATEGORY, d.category());
        doc.put(DECIDED_AT, d.decidedAt() != null ? d.decidedAt().toEpochMilli() : 0L);
        doc.put(SURFACE, d.surface());
        doc.put(PAUSE_TYPE, d.pauseType());
        doc.put(CHANNEL, d.channel());
        doc.put(AGENT_VERSION, d.agentVersion());
        doc.put(USER_ID, d.userId());
        doc.put(DECIDED_BY, d.decidedBy());
        doc.put(DATA, OversightJson.write(d.withoutPersonalFields()));
        return doc;
    }

    public static ApprovalDecision fromDocument(Document doc) {
        ApprovalDecision stored = OversightJson.read(doc.getString(DATA), ApprovalDecision.class);
        return stored.withPersonalFields(doc.getString(USER_ID), doc.getString(DECIDED_BY));
    }

    static Bson toBson(DecisionFilter f) {
        List<Bson> parts = new ArrayList<>();
        if (f.agentId() != null) {
            parts.add(eq(AGENT_ID, f.agentId()));
        }
        if (f.groupId() != null) {
            parts.add(eq(GROUP_ID, f.groupId()));
        }
        if (f.category() != null) {
            parts.add(eq(CATEGORY, f.category()));
        }
        if (f.from() != null) {
            parts.add(gte(DECIDED_AT, f.from().toEpochMilli()));
        }
        if (f.to() != null) {
            parts.add(lt(DECIDED_AT, f.to().toEpochMilli()));
        }
        if (f.surface() != null) {
            parts.add(eq(SURFACE, f.surface()));
        }
        if (f.pauseType() != null) {
            parts.add(eq(PAUSE_TYPE, f.pauseType()));
        }
        if (f.channel() != null) {
            parts.add(eq(CHANNEL, f.channel()));
        }
        if (f.agentVersion() != null) {
            parts.add(eq(AGENT_VERSION, f.agentVersion()));
        }
        return parts.isEmpty() ? new Document() : and(parts);
    }
}
