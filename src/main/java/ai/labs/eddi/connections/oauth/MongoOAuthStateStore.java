/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.connections.oauth;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.model.Updates;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;

import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * MongoDB implementation of {@link IOAuthStateStore}.
 * <p>
 * A TTL index handles expiry as a backstop; {@link #claim} still checks
 * {@code expiresAt} itself, because Mongo's TTL monitor runs about once a
 * minute and "usually deleted by now" is not a security property.
 */
@ApplicationScoped
@DefaultBean
public class MongoOAuthStateStore implements IOAuthStateStore {

    static final String COLLECTION = "connection_oauth_states";

    private static final String FIELD_STATE = "state";
    private static final String FIELD_TENANT = "tenantId";
    private static final String FIELD_CONNECTION = "connectionName";
    private static final String FIELD_PRINCIPAL = "principal";
    private static final String FIELD_VERIFIER = "codeVerifier";
    private static final String FIELD_REDIRECT_URI = "redirectUri";
    private static final String FIELD_RETURN_TO = "returnTo";
    private static final String FIELD_CREATED = "createdAt";
    private static final String FIELD_EXPIRES = "expiresAt";
    private static final String FIELD_CONSUMED = "consumedAt";

    private final MongoCollection<Document> states;

    @Inject
    public MongoOAuthStateStore(MongoDatabase database) {
        this.states = database.getCollection(COLLECTION);
        this.states.createIndex(Indexes.ascending(FIELD_STATE), new IndexOptions().name("idx_oauth_state").unique(true).background(true));
        // Backstop only — see the class comment.
        this.states.createIndex(Indexes.ascending(FIELD_EXPIRES),
                new IndexOptions().name("idx_oauth_state_ttl").expireAfter(0L, TimeUnit.SECONDS).background(true));
    }

    @Override
    public void create(OAuthState state) {
        states.insertOne(new Document(FIELD_STATE, state.getState()).append(FIELD_TENANT, state.getTenantId())
                .append(FIELD_CONNECTION, state.getConnectionName()).append(FIELD_PRINCIPAL, state.getPrincipal())
                .append(FIELD_VERIFIER, state.getCodeVerifier()).append(FIELD_REDIRECT_URI, state.getRedirectUri())
                .append(FIELD_RETURN_TO, state.getReturnTo()).append(FIELD_CREATED, Date.from(state.getCreatedAt()))
                .append(FIELD_EXPIRES, Date.from(state.getExpiresAt())));
    }

    @Override
    public Optional<OAuthState> claim(String state) {
        if (state == null || state.isBlank()) {
            return Optional.empty();
        }
        Instant now = Instant.now();
        // findOneAndUpdate: one round trip, one document lock, and it returns the
        // row we just claimed. A find() followed by an updateOne() would let two
        // concurrent callbacks both see consumedAt absent.
        Document claimed = states.findOneAndUpdate(
                Filters.and(Filters.eq(FIELD_STATE, state), Filters.or(Filters.exists(FIELD_CONSUMED, false), Filters.eq(FIELD_CONSUMED, null)),
                        Filters.gt(FIELD_EXPIRES, Date.from(now))),
                Updates.set(FIELD_CONSUMED, Date.from(now)), new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
        return Optional.ofNullable(claimed).map(MongoOAuthStateStore::toState);
    }

    @Override
    public int deleteExpired() {
        return (int) states.deleteMany(Filters.lt(FIELD_EXPIRES, Date.from(Instant.now()))).getDeletedCount();
    }

    private static OAuthState toState(Document document) {
        var state = new OAuthState();
        state.setState(document.getString(FIELD_STATE));
        state.setTenantId(document.getString(FIELD_TENANT));
        state.setConnectionName(document.getString(FIELD_CONNECTION));
        state.setPrincipal(document.getString(FIELD_PRINCIPAL));
        state.setCodeVerifier(document.getString(FIELD_VERIFIER));
        state.setRedirectUri(document.getString(FIELD_REDIRECT_URI));
        state.setReturnTo(document.getString(FIELD_RETURN_TO));
        state.setCreatedAt(document.getDate(FIELD_CREATED) == null ? null : document.getDate(FIELD_CREATED).toInstant());
        state.setExpiresAt(document.getDate(FIELD_EXPIRES) == null ? null : document.getDate(FIELD_EXPIRES).toInstant());
        state.setConsumedAt(document.getDate(FIELD_CONSUMED) == null ? null : document.getDate(FIELD_CONSUMED).toInstant());
        return state;
    }
}
