/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health.mongo;

import ai.labs.eddi.engine.health.HealthStoreException;
import ai.labs.eddi.engine.health.IHealthMarkerStore;
import ai.labs.eddi.engine.health.model.HealthMarker;
import com.mongodb.ErrorCategory;
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
import java.util.Collection;
import java.util.Date;
import java.util.List;

import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Filters.gte;
import static com.mongodb.client.model.Filters.in;
import static com.mongodb.client.model.Filters.lt;

/**
 * MongoDB implementation of {@link IHealthMarkerStore}: collection
 * {@code healthmarkers}, {@code _id} the marker id. A deployment-wide marker
 * has {@code agentId: null}.
 */
@ApplicationScoped
@DefaultBean
public class MongoHealthMarkerStore implements IHealthMarkerStore {

    static final String COLLECTION = "healthmarkers";
    static final String FIELD_AGENT = "agentId";
    static final String FIELD_AT = "at";
    static final String FIELD_KIND = "kind";
    static final String FIELD_LABEL = "label";
    static final String FIELD_VERSION = "version";
    static final String FIELD_TENANT = "tenantId";

    private final MongoCollection<Document> markers;

    @Inject
    public MongoHealthMarkerStore(MongoDatabase database) {
        this(database.getCollection(COLLECTION));
        markers.createIndex(Indexes.ascending(FIELD_AGENT, FIELD_AT), new IndexOptions().name("idx_health_markers_agent_at").background(true));
        markers.createIndex(Indexes.ascending(FIELD_AT), new IndexOptions().name("idx_health_markers_at").background(true));
        markers.createIndex(Indexes.ascending(FIELD_TENANT, FIELD_AT), new IndexOptions().name("idx_health_markers_tenant_at").background(true));
    }

    /** For tests: a collection without index creation. */
    MongoHealthMarkerStore(MongoCollection<Document> markers) {
        this.markers = markers;
    }

    @Override
    public void append(HealthMarker marker) {
        try {
            markers.insertOne(new Document("_id", marker.id()).append(FIELD_AGENT, marker.agentId()).append(FIELD_AT, Date.from(marker.at()))
                    .append(FIELD_KIND, marker.kind()).append(FIELD_LABEL, marker.label()).append(FIELD_VERSION, marker.version())
                    .append(FIELD_TENANT, marker.tenantId()));
        } catch (MongoException e) {
            if (ErrorCategory.fromErrorCode(e.getCode()) == ErrorCategory.DUPLICATE_KEY) {
                return; // coalesced into the marker already stored
            }
            throw new HealthStoreException("Could not store a health marker", e);
        }
    }

    @Override
    public List<HealthMarker> findForAgent(String agentId, Instant from, Instant to, int limit) {
        return read(and(eq(FIELD_AGENT, agentId), gte(FIELD_AT, Date.from(from)), lt(FIELD_AT, Date.from(to))), limit);
    }

    @Override
    public List<HealthMarker> findGlobal(Collection<String> tenantIds, Instant from, Instant to, int limit) {
        if (tenantIds == null || tenantIds.isEmpty()) {
            return List.of();
        }
        return read(and(eq(FIELD_AGENT, null), in(FIELD_TENANT, tenantIds), gte(FIELD_AT, Date.from(from)), lt(FIELD_AT, Date.from(to))), limit);
    }

    private List<HealthMarker> read(Bson filter, int limit) {
        try {
            List<HealthMarker> found = new ArrayList<>();
            for (Document d : markers.find(filter).sort(Sorts.descending(FIELD_AT)).limit(Math.max(1, limit))) {
                Object version = d.get(FIELD_VERSION);
                found.add(new HealthMarker(d.getString("_id"), d.getString(FIELD_AGENT), d.getDate(FIELD_AT).toInstant(), d.getString(FIELD_KIND),
                        d.getString(FIELD_LABEL), version instanceof Number n ? n.intValue() : null, d.getString(FIELD_TENANT)));
            }
            return found;
        } catch (MongoException e) {
            throw new HealthStoreException("Could not read health markers", e);
        }
    }

    @Override
    public long deleteOlderThan(Instant before) {
        try {
            return markers.deleteMany(lt(FIELD_AT, Date.from(before))).getDeletedCount();
        } catch (MongoException e) {
            throw new HealthStoreException("Could not delete expired health markers", e);
        }
    }
}
