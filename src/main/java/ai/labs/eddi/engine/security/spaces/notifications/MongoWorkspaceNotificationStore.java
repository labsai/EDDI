/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.security.spaces.notifications;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.Projections;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.Updates;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.List;

/**
 * MongoDB implementation of {@link IWorkspaceNotificationStore}: one document
 * per notification in {@code workspace_notifications}.
 */
@ApplicationScoped
@DefaultBean
public class MongoWorkspaceNotificationStore implements IWorkspaceNotificationStore {

    private static final Logger LOGGER = Logger.getLogger(MongoWorkspaceNotificationStore.class);

    static final String COLLECTION = "workspace_notifications";

    private static final String ID = "_id";
    private static final String RECIPIENT = "recipient";
    private static final String TYPE = "type";
    private static final String RESOURCE_ID = "resourceId";
    private static final String RESOURCE_URI = "resourceUri";
    private static final String RESOURCE_NAME = "resourceName";
    private static final String ACTOR = "actor";
    private static final String ACTOR_LABEL = "actorLabel";
    private static final String LEVEL = "level";
    private static final String MESSAGE = "message";
    private static final String CREATED_AT = "createdAt";
    private static final String READ_AT = "readAt";

    private final MongoCollection<Document> collection;

    @Inject
    public MongoWorkspaceNotificationStore(MongoDatabase database) {
        this.collection = database.getCollection(COLLECTION);
        try {
            collection.createIndex(Indexes.compoundIndex(Indexes.ascending(RECIPIENT), Indexes.descending(CREATED_AT)),
                    new IndexOptions().background(true));
            collection.createIndex(Indexes.compoundIndex(Indexes.ascending(ACTOR), Indexes.descending(CREATED_AT)),
                    new IndexOptions().background(true));
        } catch (RuntimeException e) {
            LOGGER.warnf("Could not create the %s indexes: %s", COLLECTION, e.getMessage());
        }
    }

    @Override
    public void add(WorkspaceNotification n) {
        Document document = new Document(ID, n.id()).append(RECIPIENT, n.recipient()).append(TYPE, n.type().name())
                .append(RESOURCE_ID, n.resourceId()).append(RESOURCE_URI, n.resourceUri()).append(RESOURCE_NAME, n.resourceName())
                .append(ACTOR, n.actor()).append(ACTOR_LABEL, n.actorLabel()).append(LEVEL, n.level()).append(MESSAGE, n.message())
                .append(CREATED_AT, Date.from(n.createdAt())).append(READ_AT, n.readAt() == null ? null : Date.from(n.readAt()));
        collection.insertOne(document);
    }

    @Override
    public List<WorkspaceNotification> list(String recipient, boolean unreadOnly, int limit) {
        Bson filter = unreadOnly ? Filters.and(Filters.eq(RECIPIENT, recipient), Filters.eq(READ_AT, null)) : Filters.eq(RECIPIENT, recipient);
        return collect(filter, limit);
    }

    @Override
    public long countUnread(String recipient) {
        return collection.countDocuments(Filters.and(Filters.eq(RECIPIENT, recipient), Filters.eq(READ_AT, null)));
    }

    @Override
    public long markRead(String recipient, Collection<String> ids, Instant readAt) {
        Bson filter = Filters.and(Filters.eq(RECIPIENT, recipient), Filters.eq(READ_AT, null));
        if (ids != null) {
            filter = Filters.and(filter, Filters.in(ID, ids));
        }
        return collection.updateMany(filter, Updates.set(READ_AT, Date.from(readAt))).getModifiedCount();
    }

    @Override
    public boolean hasUnread(String recipient, String actor, String resourceId, WorkspaceNotification.Type type) {
        return collection.find(Filters.and(Filters.eq(RECIPIENT, recipient), Filters.eq(ACTOR, actor), Filters.eq(RESOURCE_ID, resourceId),
                Filters.eq(TYPE, type.name()), Filters.eq(READ_AT, null))).limit(1).first() != null;
    }

    @Override
    public long countByActorSince(String actor, WorkspaceNotification.Type type, Instant since) {
        return collection.countDocuments(Filters.and(Filters.eq(ACTOR, actor), Filters.eq(TYPE, type.name()),
                Filters.gte(CREATED_AT, Date.from(since))));
    }

    @Override
    public void prune(String recipient, int keep, Instant olderThan) {
        collection.deleteMany(Filters.and(Filters.eq(RECIPIENT, recipient), Filters.lt(CREATED_AT, Date.from(olderThan))));
        List<Object> overflow = new ArrayList<>();
        collection.find(Filters.eq(RECIPIENT, recipient)).sort(Sorts.descending(CREATED_AT)).skip(Math.max(0, keep))
                .projection(Projections.include(ID)).forEach(doc -> overflow.add(doc.get(ID)));
        if (!overflow.isEmpty()) {
            collection.deleteMany(Filters.in(ID, overflow));
        }
    }

    @Override
    public long deleteInvolving(String principal) {
        return collection.deleteMany(Filters.or(Filters.eq(RECIPIENT, principal), Filters.eq(ACTOR, principal))).getDeletedCount();
    }

    @Override
    public List<WorkspaceNotification> listInvolving(String principal, int limit) {
        return collect(Filters.or(Filters.eq(RECIPIENT, principal), Filters.eq(ACTOR, principal)), limit);
    }

    private List<WorkspaceNotification> collect(Bson filter, int limit) {
        List<WorkspaceNotification> result = new ArrayList<>();
        collection.find(filter).sort(Sorts.descending(CREATED_AT)).limit(Math.max(1, limit)).forEach(doc -> result.add(toNotification(doc)));
        return result;
    }

    private static WorkspaceNotification toNotification(Document doc) {
        Date created = doc.getDate(CREATED_AT);
        Date read = doc.getDate(READ_AT);
        WorkspaceNotification.Type type;
        try {
            type = WorkspaceNotification.Type.valueOf(doc.getString(TYPE));
        } catch (RuntimeException e) {
            type = WorkspaceNotification.Type.SHARED_WITH_YOU;
        }
        return new WorkspaceNotification(String.valueOf(doc.get(ID)), doc.getString(RECIPIENT), type, doc.getString(RESOURCE_ID),
                doc.getString(RESOURCE_URI), doc.getString(RESOURCE_NAME), doc.getString(ACTOR), doc.getString(ACTOR_LABEL),
                doc.getString(LEVEL), doc.getString(MESSAGE), created == null ? null : created.toInstant(), read == null ? null : read.toInstant());
    }
}
