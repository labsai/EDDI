/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.org;

import com.mongodb.ErrorCategory;
import com.mongodb.MongoWriteException;
import com.mongodb.ReadConcern;
import com.mongodb.ReadPreference;
import com.mongodb.WriteConcern;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.Updates;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;
import org.bson.conversions.Bson;

import java.util.Date;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * MongoDB {@link IOrgMarkerStore}: one document, {@code _id = "org-platform"},
 * in {@code org_platform_state}.
 * <p>
 * Every operation goes to the primary with majority read and write concern.
 * EDDI's client otherwise reads {@code nearest()}, and a lagging secondary
 * could hide a just-raised {@code layout.required} from {@link DataLayoutGuard}
 * (ADR 0001, DV-11).
 */
@ApplicationScoped
@DefaultBean
public class MongoOrgMarkerStore implements IOrgMarkerStore {

    static final String COLLECTION = "org_platform_state";
    static final String RECORD_ID = "org-platform";
    static final String MARKER_STATE = "markerState";
    static final String REQUIRED_LAYOUT = "requiredLayout";
    static final String UPDATED_AT = "updatedAt";
    /** Bounds one read, so the guard's retry loop keeps its own deadline. */
    static final long READ_TIMEOUT_MS = 5_000;

    private final MongoCollection<Document> collection;

    @Inject
    public MongoOrgMarkerStore(MongoDatabase database) {
        this.collection = database.getCollection(COLLECTION).withReadPreference(ReadPreference.primary())
                .withReadConcern(ReadConcern.MAJORITY).withWriteConcern(WriteConcern.MAJORITY);
    }

    @Override
    public OrgMarker read() {
        Document document = collection.withTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS).find(Filters.eq("_id", RECORD_ID)).first();
        if (document == null) {
            return OrgMarker.NONE;
        }
        Object updatedAt = document.get(UPDATED_AT);
        return OrgMarker.parse(document.get(MARKER_STATE), document.get(REQUIRED_LAYOUT),
                updatedAt instanceof Date date ? date.toInstant() : null);
    }

    @Override
    public boolean moveMarker(OrgMarkerState expected, OrgMarkerState next) {
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(next, "next");
        if (!expected.canMoveTo(next)) {
            throw new IllegalArgumentException("The org marker cannot move from " + expected + " to " + next);
        }
        ensureRecord();
        long modified = collection
                .updateOne(Filters.and(Filters.eq("_id", RECORD_ID), stateIs(expected)),
                        Updates.combine(Updates.set(MARKER_STATE, next.name()), Updates.set(UPDATED_AT, new Date())))
                .getModifiedCount();
        return modified == 1;
    }

    @Override
    public int raiseRequiredLayout(int level) {
        if (level < 0) {
            throw new IllegalArgumentException("level must not be negative");
        }
        ensureRecord();
        // $max is atomic and also sets a missing field, which read() treats as 0
        collection.updateOne(Filters.eq("_id", RECORD_ID), Updates.combine(Updates.max(REQUIRED_LAYOUT, level), Updates.set(UPDATED_AT, new Date())));
        return read().requiredLayout();
    }

    /**
     * Matches the stored state; a record without one reads as ABSENT, so it matches
     * ABSENT too.
     */
    private static Bson stateIs(OrgMarkerState state) {
        return state == OrgMarkerState.ABSENT
                ? Filters.or(Filters.eq(MARKER_STATE, state.name()), Filters.exists(MARKER_STATE, false))
                : Filters.eq(MARKER_STATE, state.name());
    }

    /** Creates the record in its initial state if it does not exist yet. */
    private void ensureRecord() {
        try {
            collection.updateOne(Filters.eq("_id", RECORD_ID),
                    Updates.combine(Updates.setOnInsert(MARKER_STATE, OrgMarkerState.ABSENT.name()), Updates.setOnInsert(REQUIRED_LAYOUT, 0),
                            Updates.setOnInsert(UPDATED_AT, new Date())),
                    new UpdateOptions().upsert(true));
        } catch (MongoWriteException e) {
            // two nodes upserting at once: the loser's insert hits the _id; the record
            // exists either way
            if (e.getError().getCategory() != ErrorCategory.DUPLICATE_KEY) {
                throw e;
            }
        }
    }
}
