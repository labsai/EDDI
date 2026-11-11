/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health.mongo;

import ai.labs.eddi.engine.health.HealthDailyCodec;
import ai.labs.eddi.engine.health.HealthStoreException;
import ai.labs.eddi.engine.health.IHealthDailyStore;
import ai.labs.eddi.engine.health.model.HealthTurnAggregate;
import com.mongodb.MongoException;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.ReplaceOptions;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;
import org.bson.conversions.Bson;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Filters.gte;
import static com.mongodb.client.model.Filters.in;
import static com.mongodb.client.model.Filters.lt;
import static com.mongodb.client.model.Filters.lte;
import static com.mongodb.client.model.Filters.ne;
import static com.mongodb.client.model.Filters.nin;

/**
 * MongoDB implementation of {@link IHealthDailyStore}: collection
 * {@code healthdaily}, {@code _id}
 * {@code <agentId>|<version or "none">|<origin>|<day>}, the key fields beside a
 * {@code counters} sub-document ({@link HealthDailyCodec}). {@code day} is an
 * ISO {@code yyyy-MM-dd} string, as in {@code healthturns}.
 */
@ApplicationScoped
@DefaultBean
public class MongoHealthDailyStore implements IHealthDailyStore {

    static final String COLLECTION = "healthdaily";
    static final String FIELD_AGENT = "agentId";
    static final String FIELD_VERSION = "agentVersion";
    static final String FIELD_ORIGIN = "origin";
    static final String FIELD_DAY = "day";
    static final String FIELD_COUNTERS = "counters";

    private final MongoCollection<Document> daily;

    @Inject
    public MongoHealthDailyStore(MongoDatabase database) {
        this(database.getCollection(COLLECTION));
        daily.createIndex(Indexes.ascending(FIELD_AGENT, FIELD_DAY), new IndexOptions().name("idx_health_daily_agent_day").background(true));
        daily.createIndex(Indexes.ascending(FIELD_DAY), new IndexOptions().name("idx_health_daily_day").background(true));
    }

    /** For tests: a collection without index creation. */
    MongoHealthDailyStore(MongoCollection<Document> daily) {
        this.daily = daily;
    }

    static String id(HealthTurnAggregate row) {
        return row.agentId() + "|" + (row.agentVersion() == null ? "none" : row.agentVersion()) + "|" + row.origin() + "|" + row.day();
    }

    @Override
    public void replaceDay(String agentId, LocalDate day, List<HealthTurnAggregate> rows) {
        try {
            List<String> kept = new ArrayList<>();
            for (HealthTurnAggregate row : rows) {
                String id = id(row);
                kept.add(id);
                Document document = new Document("_id", id).append(FIELD_AGENT, row.agentId()).append(FIELD_VERSION, row.agentVersion())
                        .append(FIELD_ORIGIN, row.origin()).append(FIELD_DAY, row.day().toString())
                        .append(FIELD_COUNTERS, new Document(HealthDailyCodec.counters(row)));
                daily.replaceOne(eq("_id", id), document, new ReplaceOptions().upsert(true));
            }
            daily.deleteMany(and(eq(FIELD_AGENT, agentId), eq(FIELD_DAY, day.toString()), nin("_id", kept)));
        } catch (MongoException e) {
            throw new HealthStoreException("Could not store the daily health rows of an agent's day", e);
        }
    }

    @Override
    public List<HealthTurnAggregate> find(String agentId, LocalDate from, LocalDate to) {
        return read(and(eq(FIELD_AGENT, agentId), gte(FIELD_DAY, from.toString()), lte(FIELD_DAY, to.toString())));
    }

    @Override
    public List<HealthTurnAggregate> findForAgents(Collection<String> agentIds, LocalDate from, LocalDate to) {
        if (agentIds == null || agentIds.isEmpty()) {
            return List.of();
        }
        return read(and(in(FIELD_AGENT, agentIds), gte(FIELD_DAY, from.toString()), lte(FIELD_DAY, to.toString())));
    }

    private List<HealthTurnAggregate> read(Bson filter) {
        try {
            List<HealthTurnAggregate> rows = new ArrayList<>();
            for (Document d : daily.find(filter)) {
                Object version = d.get(FIELD_VERSION);
                rows.add(HealthDailyCodec.fromCounters(d.getString(FIELD_AGENT), version instanceof Number n ? n.intValue() : null,
                        d.getString(FIELD_ORIGIN), LocalDate.parse(d.getString(FIELD_DAY)), d.get(FIELD_COUNTERS, Document.class)));
            }
            return rows;
        } catch (MongoException e) {
            throw new HealthStoreException("Could not read daily health rows", e);
        }
    }

    @Override
    public Map<Integer, LocalDate> firstDayByVersion(String agentId) {
        try {
            Map<Integer, LocalDate> first = new TreeMap<>();
            List<Document> pipeline = List.of(new Document("$match", and(eq(FIELD_AGENT, agentId), ne(FIELD_VERSION, null))),
                    new Document("$group", new Document("_id", "$" + FIELD_VERSION).append("first", new Document("$min", "$" + FIELD_DAY))));
            for (Document d : daily.aggregate(pipeline)) {
                if (d.get("_id") instanceof Number version && d.getString("first") != null) {
                    first.put(version.intValue(), LocalDate.parse(d.getString("first")));
                }
            }
            return first;
        } catch (MongoException e) {
            throw new HealthStoreException("Could not read the first day of each agent version", e);
        }
    }

    @Override
    public long deleteOlderThan(LocalDate day) {
        try {
            return daily.deleteMany(lt(FIELD_DAY, day.toString())).getDeletedCount();
        } catch (MongoException e) {
            throw new HealthStoreException("Could not delete expired daily health rows", e);
        }
    }
}
