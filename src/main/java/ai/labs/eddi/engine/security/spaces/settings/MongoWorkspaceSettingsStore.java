/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.security.spaces.settings;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.ReplaceOptions;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;

import java.util.Date;
import java.util.Optional;

/**
 * MongoDB implementation of {@link IWorkspaceSettingsStore}: one document per
 * tenant in {@code workspace_settings}, keyed by {@code _id = tenantId}. An
 * unset field is an absent key, so "not set" survives a round trip.
 */
@ApplicationScoped
@DefaultBean
public class MongoWorkspaceSettingsStore implements IWorkspaceSettingsStore {

    static final String COLLECTION = "workspace_settings";

    private static final String ID = "_id";
    private static final String DEFAULT_SPACE = "defaultSpace";
    private static final String LEGACY_VISIBILITY = "legacyVisibility";
    private static final String UPDATED_AT = "updatedAt";
    private static final String UPDATED_BY = "updatedBy";

    private final MongoCollection<Document> collection;

    @Inject
    public MongoWorkspaceSettingsStore(MongoDatabase database) {
        this.collection = database.getCollection(COLLECTION);
    }

    @Override
    public Optional<StoredWorkspaceSettings> read(String tenantId) {
        Document document = collection.find(Filters.eq(ID, tenantId)).first();
        if (document == null) {
            return Optional.empty();
        }
        Date updatedAt = document.getDate(UPDATED_AT);
        return Optional.of(new StoredWorkspaceSettings(document.getString(DEFAULT_SPACE), document.getString(LEGACY_VISIBILITY),
                updatedAt == null ? null : updatedAt.toInstant(), document.getString(UPDATED_BY)));
    }

    @Override
    public void write(String tenantId, StoredWorkspaceSettings settings) {
        Document document = new Document(ID, tenantId);
        putIfSet(document, DEFAULT_SPACE, settings.defaultSpace());
        putIfSet(document, LEGACY_VISIBILITY, settings.legacyVisibility());
        putIfSet(document, UPDATED_AT, settings.updatedAt() == null ? null : Date.from(settings.updatedAt()));
        putIfSet(document, UPDATED_BY, settings.updatedBy());
        collection.replaceOne(Filters.eq(ID, tenantId), document, new ReplaceOptions().upsert(true));
    }

    private static void putIfSet(Document document, String key, Object value) {
        if (value != null) {
            document.put(key, value);
        }
    }
}
