/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health.mongo;

import ai.labs.eddi.engine.health.HealthStoreException;
import ai.labs.eddi.engine.health.IHealthReportLinkStore;
import ai.labs.eddi.engine.health.model.HealthReportLink;
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

import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Optional;

import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Filters.lt;
import static com.mongodb.client.model.Updates.set;

/**
 * MongoDB implementation of {@link IHealthReportLinkStore}: collection
 * {@code healthreportlinks}, {@code _id} the link id, a unique index on the
 * token hash (the only way a public request finds a link).
 */
@ApplicationScoped
@DefaultBean
public class MongoHealthReportLinkStore implements IHealthReportLinkStore {

    static final String COLLECTION = "healthreportlinks";

    private final MongoCollection<Document> links;

    @Inject
    public MongoHealthReportLinkStore(MongoDatabase database) {
        this(database.getCollection(COLLECTION));
        links.createIndex(Indexes.ascending("tokenHash"), new IndexOptions().name("idx_health_report_links_token").unique(true).background(true));
        links.createIndex(Indexes.ascending("agentId", "createdAt"), new IndexOptions().name("idx_health_report_links_agent").background(true));
        links.createIndex(Indexes.ascending("expiresAt"), new IndexOptions().name("idx_health_report_links_expires").background(true));
    }

    /** For tests: a collection without index creation. */
    MongoHealthReportLinkStore(MongoCollection<Document> links) {
        this.links = links;
    }

    @Override
    public void create(HealthReportLink link) {
        try {
            links.insertOne(new Document("_id", link.id()).append("agentId", link.agentId()).append("tokenHash", link.tokenHash())
                    .append("sections", link.sections()).append("period", link.period()).append("createdAt", date(link.createdAt()))
                    .append("createdBy", link.createdBy()).append("expiresAt", date(link.expiresAt())));
        } catch (MongoException e) {
            throw new HealthStoreException("Could not store a health report link", e);
        }
    }

    @Override
    public Optional<HealthReportLink> findByTokenHash(String tokenHash) {
        try {
            return Optional.ofNullable(links.find(eq("tokenHash", tokenHash)).first()).map(MongoHealthReportLinkStore::link);
        } catch (MongoException e) {
            throw new HealthStoreException("Could not read a health report link", e);
        }
    }

    @Override
    public List<HealthReportLink> listByAgent(String agentId) {
        try {
            List<HealthReportLink> found = new ArrayList<>();
            for (Document d : links.find(eq("agentId", agentId)).sort(Sorts.descending("createdAt"))) {
                found.add(link(d));
            }
            return found;
        } catch (MongoException e) {
            throw new HealthStoreException("Could not list health report links", e);
        }
    }

    @Override
    public boolean delete(String agentId, String id) {
        try {
            return links.deleteOne(and(eq("_id", id), eq("agentId", agentId))).getDeletedCount() > 0;
        } catch (MongoException e) {
            throw new HealthStoreException("Could not revoke a health report link", e);
        }
    }

    @Override
    public long deleteExpiredBefore(Instant before) {
        try {
            return links.deleteMany(lt("expiresAt", Date.from(before))).getDeletedCount();
        } catch (MongoException e) {
            throw new HealthStoreException("Could not delete expired health report links", e);
        }
    }

    @Override
    public long clearCreator(String userId) {
        if (userId == null) {
            return 0;
        }
        try {
            return links.updateMany(eq("createdBy", userId), set("createdBy", null)).getModifiedCount();
        } catch (MongoException e) {
            throw new HealthStoreException("Could not clear the creator of health report links", e);
        }
    }

    private static Date date(Instant instant) {
        return instant == null ? null : Date.from(instant);
    }

    private static HealthReportLink link(Document d) {
        Date created = d.getDate("createdAt");
        Date expires = d.getDate("expiresAt");
        return new HealthReportLink(d.getString("_id"), d.getString("agentId"), d.getString("tokenHash"), d.getList("sections", String.class),
                d.getString("period"), created == null ? null : created.toInstant(), d.getString("createdBy"),
                expires == null ? null : expires.toInstant());
    }
}
