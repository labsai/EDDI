/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.alerts.mongo;

import ai.labs.eddi.engine.alerts.AlertJson;
import ai.labs.eddi.engine.alerts.IAlertRuleStore;
import ai.labs.eddi.engine.alerts.model.AlertCondition;
import ai.labs.eddi.engine.alerts.model.AlertRule;
import ai.labs.eddi.engine.alerts.model.AlertRule.DigestMode;
import ai.labs.eddi.engine.alerts.model.AlertRule.Throttle;
import ai.labs.eddi.engine.alerts.model.AlertScope;
import ai.labs.eddi.engine.alerts.model.AlertSource;
import ai.labs.eddi.engine.alerts.model.DeliveryTarget;
import com.fasterxml.jackson.core.type.TypeReference;
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
import org.bson.types.ObjectId;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Filters.or;
import static com.mongodb.client.model.Filters.size;
import static com.mongodb.client.model.Updates.set;

/**
 * MongoDB implementation of {@link IAlertRuleStore}, collection
 * {@code alertrules}. The scope is stored as {@code scope.allAgents} /
 * {@code scope.agentIds} so "the rules watching agent X" is one indexed query.
 *
 * @since 6.6.0
 */
@ApplicationScoped
@DefaultBean
public class AlertRuleStore implements IAlertRuleStore {

    static final String COLLECTION = "alertrules";

    private static final TypeReference<List<DeliveryTarget>> TARGETS = new TypeReference<>() {
    };

    private final MongoCollection<Document> collection;

    @Inject
    public AlertRuleStore(MongoDatabase database) {
        this.collection = database.getCollection(COLLECTION);
        collection.createIndex(Indexes.ascending("scope.agentIds"), new IndexOptions().name("idx_alertrule_agents"));
        collection.createIndex(Indexes.compoundIndex(Indexes.ascending("enabled"), Indexes.ascending("scope.allAgents")),
                new IndexOptions().name("idx_alertrule_enabled"));
    }

    @Override
    public AlertRule create(AlertRule rule) {
        AlertRule stored = rule.withId(new ObjectId().toHexString());
        collection.insertOne(toDocument(stored));
        return stored;
    }

    @Override
    public Optional<AlertRule> read(String id) {
        Document doc = collection.find(eq("_id", id)).first();
        return Optional.ofNullable(doc).map(AlertRuleStore::fromDocument);
    }

    @Override
    public boolean update(AlertRule rule) {
        return collection.replaceOne(eq("_id", rule.id()), toDocument(rule)).getMatchedCount() > 0;
    }

    @Override
    public boolean delete(String id) {
        return collection.deleteOne(eq("_id", id)).getDeletedCount() > 0;
    }

    @Override
    public List<AlertRule> list(String agentId, boolean onlyThatAgent, int offset, int limit) {
        List<AlertRule> rules = new ArrayList<>();
        collection.find(listFilter(agentId, onlyThatAgent)).sort(Sorts.descending("createdAt", "_id")).skip(Math.max(0, offset)).limit(limit)
                .forEach(doc -> rules.add(fromDocument(doc)));
        return rules;
    }

    @Override
    public long count(String agentId, boolean onlyThatAgent) {
        return collection.countDocuments(listFilter(agentId, onlyThatAgent));
    }

    @Override
    public List<AlertRule> findEnabledForAgent(String agentId) {
        List<AlertRule> rules = new ArrayList<>();
        collection.find(and(eq("enabled", true), or(eq("scope.allAgents", true), eq("scope.agentIds", agentId))))
                .forEach(doc -> rules.add(fromDocument(doc)));
        return rules;
    }

    @Override
    public List<AlertRule> findEnabled() {
        List<AlertRule> rules = new ArrayList<>();
        collection.find(eq("enabled", true)).forEach(doc -> rules.add(fromDocument(doc)));
        return rules;
    }

    @Override
    public long clearAuthor(String userId) {
        long created = collection.updateMany(eq("createdBy", userId), set("createdBy", null)).getModifiedCount();
        long updated = collection.updateMany(eq("updatedBy", userId), set("updatedBy", null)).getModifiedCount();
        return created + updated;
    }

    @Override
    public List<AlertRule> findByAuthor(String userId, int limit) {
        List<AlertRule> rules = new ArrayList<>();
        collection.find(or(eq("createdBy", userId), eq("updatedBy", userId))).sort(Sorts.descending("createdAt", "_id")).limit(limit)
                .forEach(doc -> rules.add(fromDocument(doc)));
        return rules;
    }

    private static Bson listFilter(String agentId, boolean onlyThatAgent) {
        if (agentId == null) {
            return new Document();
        }
        return onlyThatAgent ? and(eq("scope.agentIds", agentId), size("scope.agentIds", 1)) : eq("scope.agentIds", agentId);
    }

    static Document toDocument(AlertRule rule) {
        Document doc = new Document("_id", rule.id());
        doc.put("name", rule.name());
        doc.put("enabled", rule.enabled());
        AlertScope scope = rule.scope() == null ? new AlertScope(false, List.of()) : rule.scope();
        doc.put("scope", new Document("allAgents", scope.allAgents()).append("agentIds", scope.agentIds()));
        doc.put("source", rule.source() == null ? null : rule.source().name());
        doc.put("condition", map(AlertJson.toMap(rule.condition())));
        List<Document> delivery = new ArrayList<>();
        for (DeliveryTarget target : rule.delivery()) {
            delivery.add(map(AlertJson.toMap(target)));
        }
        doc.put("delivery", delivery);
        doc.put("throttle", map(AlertJson.toMap(rule.throttle())));
        doc.put("digest", rule.digest() == null ? null : rule.digest().name());
        doc.put("createdBy", rule.createdBy());
        doc.put("createdAt", date(rule.createdAt()));
        doc.put("updatedBy", rule.updatedBy());
        doc.put("updatedAt", date(rule.updatedAt()));
        return doc;
    }

    static AlertRule fromDocument(Document doc) {
        Document scopeDoc = doc.get("scope", Document.class);
        AlertScope scope = scopeDoc == null
                ? new AlertScope(false, List.of())
                : new AlertScope(Boolean.TRUE.equals(scopeDoc.getBoolean("allAgents")), scopeDoc.getList("agentIds", String.class));
        String source = doc.getString("source");
        String digest = doc.getString("digest");
        return new AlertRule(doc.getString("_id"), doc.getString("name"), Boolean.TRUE.equals(doc.getBoolean("enabled")), scope,
                source == null ? null : AlertSource.valueOf(source), AlertJson.convert(doc.get("condition"), AlertCondition.class),
                AlertJson.convert(doc.get("delivery"), TARGETS), AlertJson.convert(doc.get("throttle"), Throttle.class),
                digest == null ? null : DigestMode.valueOf(digest), doc.getString("createdBy"), instant(doc.getDate("createdAt")),
                doc.getString("updatedBy"), instant(doc.getDate("updatedAt")));
    }

    private static Document map(Map<String, Object> value) {
        return value == null ? null : new Document(value);
    }

    private static Date date(Instant instant) {
        return instant == null ? null : Date.from(instant);
    }

    private static Instant instant(Date date) {
        return date == null ? null : date.toInstant();
    }
}
