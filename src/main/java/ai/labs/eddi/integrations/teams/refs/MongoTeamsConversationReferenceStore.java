/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.teams.refs;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.ReplaceOptions;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;
import org.bson.conversions.Bson;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Optional;

/**
 * MongoDB {@link ITeamsConversationReferenceStore}: collection
 * {@value #COLLECTION}, unique on (appId, conversationId).
 *
 * @since 6.7.0
 */
@ApplicationScoped
@DefaultBean
public class MongoTeamsConversationReferenceStore implements ITeamsConversationReferenceStore {

    static final String COLLECTION = "teams_conversation_references";

    private static final String APP_ID = "appId";
    private static final String CONVERSATION_ID = "conversationId";
    private static final String CONVERSATION_TYPE = "conversationType";
    private static final String SERVICE_URL = "serviceUrl";
    private static final String TENANT_ID = "tenantId";
    private static final String BOT_ID = "botId";
    private static final String USER_ID = "userId";
    private static final String UPDATED_AT = "updatedAt";

    private final MongoCollection<Document> references;

    @Inject
    public MongoTeamsConversationReferenceStore(MongoDatabase database) {
        this.references = database.getCollection(COLLECTION);
        references.createIndex(Indexes.compoundIndex(Indexes.ascending(APP_ID), Indexes.ascending(CONVERSATION_ID)),
                new IndexOptions().name("idx_teams_ref_key").unique(true));
        references.createIndex(Indexes.ascending(USER_ID), new IndexOptions().name("idx_teams_ref_user").sparse(true));
    }

    @Override
    public void upsert(TeamsConversationReference reference) {
        Document document = new Document(APP_ID, reference.appId())
                .append(CONVERSATION_ID, reference.conversationId())
                .append(CONVERSATION_TYPE, reference.conversationType())
                .append(SERVICE_URL, reference.serviceUrl())
                .append(TENANT_ID, reference.tenantId())
                .append(BOT_ID, reference.botId())
                .append(UPDATED_AT, Date.from(reference.updatedAt() != null ? reference.updatedAt() : Instant.now()));
        if (reference.userId() != null) {
            document.append(USER_ID, reference.userId());
        }
        references.replaceOne(key(reference.appId(), reference.conversationId()), document, new ReplaceOptions().upsert(true));
    }

    @Override
    public Optional<TeamsConversationReference> find(String appId, String conversationId) {
        Document document = references.find(key(appId, conversationId)).first();
        return Optional.ofNullable(document).map(MongoTeamsConversationReferenceStore::toReference);
    }

    @Override
    public List<TeamsConversationReference> findByUser(String userId) {
        List<TeamsConversationReference> result = new ArrayList<>();
        if (userId == null) {
            return result;
        }
        for (Document document : references.find(Filters.eq(USER_ID, userId))) {
            result.add(toReference(document));
        }
        return result;
    }

    @Override
    public long deleteByUser(String userId) {
        if (userId == null) {
            return 0;
        }
        return references.deleteMany(Filters.eq(USER_ID, userId)).getDeletedCount();
    }

    private static Bson key(String appId, String conversationId) {
        return Filters.and(Filters.eq(APP_ID, appId), Filters.eq(CONVERSATION_ID, conversationId));
    }

    private static TeamsConversationReference toReference(Document document) {
        Date updated = document.getDate(UPDATED_AT);
        return new TeamsConversationReference(document.getString(APP_ID), document.getString(CONVERSATION_ID),
                document.getString(CONVERSATION_TYPE), document.getString(SERVICE_URL), document.getString(TENANT_ID),
                document.getString(BOT_ID), document.getString(USER_ID), updated != null ? updated.toInstant() : null);
    }
}
