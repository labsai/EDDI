/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.audit;

import ai.labs.eddi.engine.audit.model.AuditEntry;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Indexes;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;

import java.util.*;

/**
 * MongoDB implementation of {@link IAuditStore}.
 * <p>
 * Uses a dedicated {@code audit_ledger} collection with insert-only semantics.
 * No {@code updateOne()}, {@code replaceOne()}, or {@code deleteOne()}
 * operations are ever called — this enforces the write-once contract.
 * <p>
 * Annotated {@code @DefaultBean} so PostgreSQL can provide an alternative.
 *
 * @author ginccc
 * @since 6.0.0
 */
@ApplicationScoped
@DefaultBean
public class AuditStore implements IAuditStore {

    private static final String COLLECTION_NAME = "audit_ledger";

    // Document field names
    private static final String F_ID = "_id";
    private static final String F_CONVERSATION_ID = "conversationId";
    private static final String F_AGENT_ID = "agentId";
    private static final String F_AGENT_VERSION = "agentVersion";
    private static final String F_USER_ID = "userId";
    private static final String F_ENVIRONMENT = "environment";
    private static final String F_STEP_INDEX = "stepIndex";
    private static final String F_TASK_ID = "taskId";
    private static final String F_TASK_TYPE = "taskType";
    private static final String F_TASK_INDEX = "taskIndex";
    private static final String F_DURATION_MS = "durationMs";
    private static final String F_INPUT = "input";
    private static final String F_OUTPUT = "output";
    private static final String F_LLM_DETAIL = "llmDetail";
    private static final String F_TOOL_CALLS = "toolCalls";
    private static final String F_ACTIONS = "actions";
    private static final String F_COST = "cost";
    private static final String F_TIMESTAMP = "timestamp";
    private static final String F_HMAC = "hmac";
    private static final String F_AGENT_SIGNATURE = "agentSignature";

    private final MongoCollection<Document> collection;

    @Inject
    public AuditStore(MongoDatabase database) {
        this.collection = database.getCollection(COLLECTION_NAME);

        // Create indexes for efficient querying
        collection.createIndex(Indexes.ascending(F_CONVERSATION_ID));
        collection.createIndex(Indexes.ascending(F_AGENT_ID, F_AGENT_VERSION));
        collection.createIndex(Indexes.descending(F_TIMESTAMP));
    }

    @Override
    public void appendEntry(AuditEntry entry) {
        collection.insertOne(toDocument(entry));
    }

    @Override
    public void appendBatch(List<AuditEntry> entries) {
        if (entries == null || entries.isEmpty())
            return;

        List<Document> documents = new ArrayList<>(entries.size());
        for (AuditEntry entry : entries) {
            documents.add(toDocument(entry));
        }

        collection.insertMany(documents);
    }

    @Override
    public List<AuditEntry> getEntries(String conversationId, int skip, int limit) {
        Document filter = new Document(F_CONVERSATION_ID, conversationId);
        return query(filter, skip, limit);
    }

    @Override
    public List<AuditEntry> getEntriesByAgent(String agentId, Integer agentVersion, int skip, int limit) {
        Document filter = new Document(F_AGENT_ID, agentId);
        if (agentVersion != null) {
            filter.append(F_AGENT_VERSION, agentVersion);
        }
        return query(filter, skip, limit);
    }

    @Override
    public long countByConversation(String conversationId) {
        return collection.countDocuments(new Document(F_CONVERSATION_ID, conversationId));
    }

    @Override
    public List<AuditEntry> getEntriesByUserId(String userId, int skip, int limit) {
        Document filter = new Document(F_USER_ID, userId);
        return query(filter, skip, limit);
    }
    // ==================== Private Helpers ====================

    private List<AuditEntry> query(Document filter, int skip, int limit) {
        List<AuditEntry> result = new ArrayList<>();
        var iterable = collection.find(filter).sort(new Document(F_TIMESTAMP, -1));

        if (skip > 0)
            iterable.skip(skip);
        if (limit > 0)
            iterable.limit(limit);

        for (Document doc : iterable) {
            result.add(fromDocument(doc));
        }
        return result;
    }

    private static Document toDocument(AuditEntry entry) {
        Document doc = new Document();
        doc.put(F_ID, entry.id());
        doc.put(F_CONVERSATION_ID, entry.conversationId());
        doc.put(F_AGENT_ID, entry.agentId());
        doc.put(F_AGENT_VERSION, entry.agentVersion());
        doc.put(F_USER_ID, entry.userId());
        doc.put(F_ENVIRONMENT, entry.environment());
        doc.put(F_STEP_INDEX, entry.stepIndex());
        doc.put(F_TASK_ID, entry.taskId());
        doc.put(F_TASK_TYPE, entry.taskType());
        doc.put(F_TASK_INDEX, entry.taskIndex());
        doc.put(F_DURATION_MS, entry.durationMs());
        if (entry.input() != null)
            doc.put(F_INPUT, new Document(entry.input()));
        if (entry.output() != null)
            doc.put(F_OUTPUT, new Document(entry.output()));
        if (entry.llmDetail() != null)
            doc.put(F_LLM_DETAIL, new Document(entry.llmDetail()));
        if (entry.toolCalls() != null)
            doc.put(F_TOOL_CALLS, new Document(entry.toolCalls()));
        if (entry.actions() != null)
            doc.put(F_ACTIONS, entry.actions());
        doc.put(F_COST, entry.cost());
        if (entry.timestamp() != null)
            doc.put(F_TIMESTAMP, Date.from(entry.timestamp()));
        if (entry.hmac() != null)
            doc.put(F_HMAC, entry.hmac());
        if (entry.agentSignature() != null)
            doc.put(F_AGENT_SIGNATURE, entry.agentSignature());
        return doc;
    }

    private static AuditEntry fromDocument(Document doc) {
        return new AuditEntry(doc.getString(F_ID), doc.getString(F_CONVERSATION_ID), doc.getString(F_AGENT_ID), doc.getInteger(F_AGENT_VERSION),
                doc.getString(F_USER_ID), doc.getString(F_ENVIRONMENT), doc.getInteger(F_STEP_INDEX, 0), doc.getString(F_TASK_ID),
                doc.getString(F_TASK_TYPE), doc.getInteger(F_TASK_INDEX, 0), doc.getLong(F_DURATION_MS) != null ? doc.getLong(F_DURATION_MS) : 0L,
                doc.get(F_INPUT) instanceof Document d ? new LinkedHashMap<>(d) : null,
                doc.get(F_OUTPUT) instanceof Document d ? new LinkedHashMap<>(d) : null,
                doc.get(F_LLM_DETAIL) instanceof Document d ? new LinkedHashMap<>(d) : null,
                doc.get(F_TOOL_CALLS) instanceof Document d ? new LinkedHashMap<>(d) : null, doc.getList(F_ACTIONS, String.class),
                doc.getDouble(F_COST) != null ? doc.getDouble(F_COST) : 0.0,
                doc.getDate(F_TIMESTAMP) != null ? doc.getDate(F_TIMESTAMP).toInstant() : null, doc.getString(F_HMAC),
                doc.getString(F_AGENT_SIGNATURE));
    }
    @Override
    public long pseudonymizeByUserId(String userId, String pseudonym) {
        return collection.updateMany(
                new Document(F_USER_ID, userId),
                new Document("$set", new Document(F_USER_ID, pseudonym))).getModifiedCount();
    }
}
