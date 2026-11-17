/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health.mongo;

import ai.labs.eddi.engine.health.HealthStoreException;
import ai.labs.eddi.engine.health.IHealthAlertStateStore;
import ai.labs.eddi.engine.health.model.HealthAlertState;
import com.mongodb.MongoException;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.ReplaceOneModel;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.WriteModel;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Filters.in;
import static com.mongodb.client.model.Filters.lt;

/**
 * MongoDB implementation of {@link IHealthAlertStateStore}: collection
 * {@code healthalertstate}, {@code _id} {@code <ruleId>|<agentId>}.
 */
@ApplicationScoped
@DefaultBean
public class MongoHealthAlertStateStore implements IHealthAlertStateStore {

    static final String COLLECTION = "healthalertstate";

    private final MongoCollection<Document> states;

    @Inject
    public MongoHealthAlertStateStore(MongoDatabase database) {
        this(database.getCollection(COLLECTION));
        states.createIndex(Indexes.ascending("evaluatedAt"), new IndexOptions().name("idx_health_alert_state_evaluated").background(true));
    }

    /** For tests: a collection without index creation. */
    MongoHealthAlertStateStore(MongoCollection<Document> states) {
        this.states = states;
    }

    static String key(String ruleId, String agentId) {
        return ruleId + "|" + agentId;
    }

    @Override
    public Map<String, HealthAlertState> read(String ruleId, Collection<String> agentIds) {
        Map<String, HealthAlertState> found = new LinkedHashMap<>();
        if (agentIds == null || agentIds.isEmpty()) {
            return found;
        }
        List<String> keys = agentIds.stream().map(a -> key(ruleId, a)).toList();
        try {
            for (Document d : states.find(in("_id", keys))) {
                Date at = d.getDate("evaluatedAt");
                found.put(d.getString("agentId"), new HealthAlertState(d.getString("ruleId"), d.getString("agentId"), d.getString("status"),
                        d.getList("active", String.class), at == null ? null : at.toInstant()));
            }
            return found;
        } catch (MongoException e) {
            throw new HealthStoreException("Could not read health alert states", e);
        }
    }

    @Override
    public void write(Collection<HealthAlertState> batch) {
        if (batch == null || batch.isEmpty()) {
            return;
        }
        List<WriteModel<Document>> writes = new ArrayList<>();
        for (HealthAlertState s : batch) {
            String key = key(s.ruleId(), s.agentId());
            Document d = new Document("_id", key).append("ruleId", s.ruleId()).append("agentId", s.agentId()).append("status", s.status())
                    .append("active", s.active()).append("evaluatedAt", s.evaluatedAt() == null ? null : Date.from(s.evaluatedAt()));
            writes.add(new ReplaceOneModel<>(eq("_id", key), d, new ReplaceOptions().upsert(true)));
        }
        try {
            states.bulkWrite(writes);
        } catch (MongoException e) {
            throw new HealthStoreException("Could not store health alert states", e);
        }
    }

    @Override
    public long deleteEvaluatedBefore(Instant before) {
        try {
            return states.deleteMany(lt("evaluatedAt", Date.from(before))).getDeletedCount();
        } catch (MongoException e) {
            throw new HealthStoreException("Could not delete stale health alert states", e);
        }
    }

    @Override
    public long deleteByAgent(String agentId) {
        try {
            return states.deleteMany(eq("agentId", agentId)).getDeletedCount();
        } catch (MongoException e) {
            throw new HealthStoreException("Could not delete the health alert states of an agent", e);
        }
    }
}
