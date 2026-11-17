/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health.mongo;

import ai.labs.eddi.engine.health.HealthJson;
import ai.labs.eddi.engine.health.HealthStoreException;
import ai.labs.eddi.engine.health.IHealthProfileStore;
import ai.labs.eddi.engine.health.model.HealthProfile;
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

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Filters.in;

/**
 * MongoDB implementation of {@link IHealthProfileStore}: collection
 * {@code healthprofiles}, {@code _id} the agent id, the profile as JSON text
 * (its shape is the REST contract's, so it is stored as written), the last
 * author in {@code updatedBy} for GDPR erasure.
 */
@ApplicationScoped
@DefaultBean
public class MongoHealthProfileStore implements IHealthProfileStore {

    static final String COLLECTION = "healthprofiles";
    static final String FIELD_PROFILE = "profile";
    static final String FIELD_UPDATED_BY = "updatedBy";

    private final MongoCollection<Document> profiles;

    @Inject
    public MongoHealthProfileStore(MongoDatabase database) {
        this(database.getCollection(COLLECTION));
        profiles.createIndex(Indexes.ascending(FIELD_UPDATED_BY), new IndexOptions().name("idx_health_profiles_updated_by").background(true));
    }

    /** For tests: a collection without index creation. */
    MongoHealthProfileStore(MongoCollection<Document> profiles) {
        this.profiles = profiles;
    }

    @Override
    public Optional<HealthProfile> read(String agentId) {
        try {
            Document d = profiles.find(eq("_id", agentId)).first();
            return Optional.ofNullable(d).map(MongoHealthProfileStore::profile);
        } catch (MongoException e) {
            throw new HealthStoreException("Could not read a health profile", e);
        }
    }

    @Override
    public Map<String, HealthProfile> readAll(Collection<String> agentIds) {
        Map<String, HealthProfile> found = new LinkedHashMap<>();
        if (agentIds == null || agentIds.isEmpty()) {
            return found;
        }
        try {
            for (Document d : profiles.find(in("_id", List.copyOf(agentIds)))) {
                found.put(d.getString("_id"), profile(d));
            }
            return found;
        } catch (MongoException e) {
            throw new HealthStoreException("Could not read health profiles", e);
        }
    }

    @Override
    public void write(HealthProfile profile) {
        try {
            Document d = new Document("_id", profile.agentId()).append(FIELD_PROFILE, HealthJson.write(profile)).append(FIELD_UPDATED_BY,
                    profile.updatedBy());
            profiles.replaceOne(eq("_id", profile.agentId()), d, new ReplaceOptions().upsert(true));
        } catch (MongoException e) {
            throw new HealthStoreException("Could not store a health profile", e);
        }
    }

    @Override
    public boolean delete(String agentId) {
        try {
            return profiles.deleteOne(eq("_id", agentId)).getDeletedCount() > 0;
        } catch (MongoException e) {
            throw new HealthStoreException("Could not delete a health profile", e);
        }
    }

    @Override
    public long clearAuthor(String userId) {
        if (userId == null) {
            return 0;
        }
        long changed = 0;
        try {
            for (Document d : profiles.find(eq(FIELD_UPDATED_BY, userId))) {
                HealthProfile p = profile(d);
                HealthProfile blanked = p.withMeta(p.agentId(), p.stored(), p.updatedAt(), null);
                // Compare-and-set on the document read: a save in between (a new author,
                // a new body) is left alone rather than overwritten with the old body.
                changed += profiles.replaceOne(
                        and(eq("_id", d.getString("_id")), eq(FIELD_UPDATED_BY, userId), eq(FIELD_PROFILE, d.getString(FIELD_PROFILE))),
                        new Document("_id", d.getString("_id")).append(FIELD_PROFILE, HealthJson.write(blanked)).append(FIELD_UPDATED_BY, null))
                        .getModifiedCount();
            }
            return changed;
        } catch (MongoException e) {
            throw new HealthStoreException("Could not clear the author of health profiles", e);
        }
    }

    @Override
    public List<HealthProfile> listByAuthor(String userId) {
        List<HealthProfile> found = new ArrayList<>();
        if (userId == null) {
            return found;
        }
        try {
            for (Document d : profiles.find(eq(FIELD_UPDATED_BY, userId))) {
                found.add(profile(d));
            }
            return found;
        } catch (MongoException e) {
            throw new HealthStoreException("Could not list health profiles", e);
        }
    }

    private static HealthProfile profile(Document d) {
        HealthProfile p = HealthJson.read(d.getString(FIELD_PROFILE), HealthProfile.class);
        return p.withMeta(d.getString("_id"), Boolean.TRUE, p.updatedAt(), p.updatedBy());
    }
}
