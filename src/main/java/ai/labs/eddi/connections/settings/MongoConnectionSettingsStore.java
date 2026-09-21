/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.connections.settings;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.ReplaceOptions;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;

import java.util.Date;
import java.util.List;
import java.util.Optional;

/**
 * MongoDB implementation of {@link IConnectionSettingsStore}: one document per
 * tenant in {@code connection_settings}, keyed by {@code _id = tenantId}.
 * <p>
 * An unset field is stored as an absent key rather than as {@code null}, so a
 * document read back distinguishes "not set" from nothing at all the same way
 * the model does.
 */
@ApplicationScoped
@DefaultBean
public class MongoConnectionSettingsStore implements IConnectionSettingsStore {

    static final String COLLECTION = "connection_settings";

    static final String FIELD_ID = "_id";
    static final String FIELD_ENABLED = "enabled";
    static final String FIELD_PUBLIC_BASE_URL = "publicBaseUrl";
    static final String FIELD_CREDENTIAL_ENDPOINT_ALLOWLIST = "credentialEndpointAllowlist";
    static final String FIELD_ALLOW_PLAINTEXT_REMOTE_ORIGINS = "allowPlaintextRemoteOrigins";
    static final String FIELD_UPDATED_AT = "updatedAt";
    static final String FIELD_UPDATED_BY = "updatedBy";

    private final MongoCollection<Document> settings;

    @Inject
    public MongoConnectionSettingsStore(MongoDatabase database) {
        this.settings = database.getCollection(COLLECTION);
    }

    @Override
    public Optional<StoredConnectionSettings> read(String tenantId) {
        Document document = settings.find(Filters.eq(FIELD_ID, tenantId)).first();
        if (document == null) {
            return Optional.empty();
        }
        var model = new ConnectionSettings(document.getBoolean(FIELD_ENABLED), document.getString(FIELD_PUBLIC_BASE_URL),
                stringList(document), document.getBoolean(FIELD_ALLOW_PLAINTEXT_REMOTE_ORIGINS));
        Date updatedAt = document.getDate(FIELD_UPDATED_AT);
        return Optional.of(new StoredConnectionSettings(model, updatedAt == null ? null : updatedAt.toInstant(),
                document.getString(FIELD_UPDATED_BY)));
    }

    @Override
    public void write(String tenantId, StoredConnectionSettings stored) {
        ConnectionSettings model = stored.settings();
        var document = new Document(FIELD_ID, tenantId);
        putIfSet(document, FIELD_ENABLED, model.getEnabled());
        putIfSet(document, FIELD_PUBLIC_BASE_URL, model.getPublicBaseUrl());
        putIfSet(document, FIELD_CREDENTIAL_ENDPOINT_ALLOWLIST,
                model.getCredentialEndpointAllowlist() == null ? null : List.copyOf(model.getCredentialEndpointAllowlist()));
        putIfSet(document, FIELD_ALLOW_PLAINTEXT_REMOTE_ORIGINS, model.getAllowPlaintextRemoteOrigins());
        putIfSet(document, FIELD_UPDATED_AT, stored.updatedAt() == null ? null : Date.from(stored.updatedAt()));
        putIfSet(document, FIELD_UPDATED_BY, stored.updatedBy());
        settings.replaceOne(Filters.eq(FIELD_ID, tenantId), document, new ReplaceOptions().upsert(true));
    }

    private static void putIfSet(Document document, String key, Object value) {
        if (value != null) {
            document.put(key, value);
        }
    }

    private static List<String> stringList(Document document) {
        if (!document.containsKey(FIELD_CREDENTIAL_ENDPOINT_ALLOWLIST)) {
            return null;
        }
        List<String> values = document.getList(FIELD_CREDENTIAL_ENDPOINT_ALLOWLIST, String.class);
        return values == null ? null : List.copyOf(values);
    }
}
