/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.secrets.persistence;

import ai.labs.eddi.secrets.model.EncryptedDek;
import ai.labs.eddi.secrets.model.EncryptedSecret;
import ai.labs.eddi.utils.RuntimeUtilities;
import com.mongodb.ErrorCategory;
import com.mongodb.MongoCommandException;
import com.mongodb.MongoException;
import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.Updates;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;

/**
 * MongoDB implementation of {@link ISecretPersistence}. Stores encrypted
 * secrets and DEKs in two collections: {@code secretvault_secrets} and
 * {@code secretvault_deks}.
 * <p>
 * This is the default implementation ({@code @DefaultBean}), active unless the
 * PostgreSQL profile overrides it.
 *
 * @author ginccc
 * @since 6.0.0
 */
@ApplicationScoped
@DefaultBean
public class MongoSecretPersistence implements ISecretPersistence {

    private static final Logger LOGGER = Logger.getLogger(MongoSecretPersistence.class);

    private static final String COLLECTION_SECRETS = "secretvault_secrets";
    private static final String COLLECTION_DEKS = "secretvault_deks";

    private static final String FIELD_TENANT_ID = "tenantId";
    private static final String FIELD_KEY_NAME = "keyName";
    private static final String FIELD_ENCRYPTED_VALUE = "encryptedValue";
    private static final String FIELD_IV = "iv";
    private static final String FIELD_DEK_ID = "dekId";
    private static final String FIELD_CHECKSUM = "checksum";
    private static final String FIELD_DESCRIPTION = "description";
    private static final String FIELD_ALLOWED_AGENTS = "allowedAgents";
    private static final String FIELD_CREATED_AT = "createdAt";
    private static final String FIELD_LAST_ACCESSED_AT = "lastAccessedAt";
    private static final String FIELD_LAST_ROTATED_AT = "lastRotatedAt";
    private static final String FIELD_ENCRYPTED_DEK = "encryptedDek";
    private static final String FIELD_GENERATION = "generation";

    /**
     * The pre-generation index: unique on tenantId alone, so it blocks a second
     * generation.
     */
    private static final String LEGACY_DEK_INDEX = "idx_dek_tenant";

    /**
     * MongoDB's {@code IndexNotFound} — the only reason dropping it may fail
     * benignly.
     */
    private static final int ERROR_INDEX_NOT_FOUND = 27;

    private final MongoCollection<Document> secretsCollection;
    private final MongoCollection<Document> deksCollection;
    private final MongoCollection<Document> metaCollection;

    @Inject
    public MongoSecretPersistence(MongoDatabase database) {
        RuntimeUtilities.checkNotNull(database, "database");
        this.secretsCollection = database.getCollection(COLLECTION_SECRETS);
        this.deksCollection = database.getCollection(COLLECTION_DEKS);
        this.metaCollection = database.getCollection("secretvault_meta");
        ensureIndexes();
    }

    private void ensureIndexes() {
        // Unique compound index on (tenantId, keyName)
        secretsCollection.createIndex(Indexes.compoundIndex(Indexes.ascending(FIELD_TENANT_ID), Indexes.ascending(FIELD_KEY_NAME)),
                new IndexOptions().name("idx_secret_tenant_key").unique(true).background(true));

        migrateDeksToGenerations();

        // Unique index on key for metadata
        metaCollection.createIndex(Indexes.ascending("key"), new IndexOptions().name("idx_meta_key").unique(true).background(true));

        LOGGER.info("Secrets vault MongoDB indexes ensured");
    }

    /**
     * Brings an existing deployment onto one row per (tenant, generation).
     * <p>
     * Order matters. The backfill runs first so every pre-generation document has a
     * generation to be indexed on; then the old unique-on-tenantId index goes,
     * because while it exists a tenant cannot hold a second generation at all and
     * rotation has nowhere to write.
     */
    private void migrateDeksToGenerations() {
        // Below-1 as well as absent. The entity normalizes what it READS, so a row
        // physically holding 0 is handed out as generation 1 — and is then looked
        // up as generation 1 by an exact query that cannot match it. Normalizing
        // the row itself is what keeps the entity and the storage agreeing; doing
        // it only in the entity moves the disagreement rather than removing it.
        deksCollection.updateMany(
                Filters.or(Filters.exists(FIELD_GENERATION, false), Filters.lt(FIELD_GENERATION, EncryptedDek.FIRST_GENERATION)),
                Updates.set(FIELD_GENERATION, EncryptedDek.FIRST_GENERATION));

        try {
            deksCollection.dropIndex(LEGACY_DEK_INDEX);
        } catch (MongoCommandException e) {
            if (e.getErrorCode() != ERROR_INDEX_NOT_FOUND) {
                // Anything else — not authorized, a stepped-down primary — means the
                // index may well still be standing, and a tenant that cannot hold a
                // second generation has nowhere for rotation to write. Reported as a
                // failed boot rather than mistaken for "it was already gone".
                throw e;
            }
            // Absent on every deployment created after generations existed, and on
            // every boot after the first. Nothing to do either way.
            LOGGER.debugf("No legacy DEK index '%s' to drop", LEGACY_DEK_INDEX);
        }

        deksCollection.createIndex(Indexes.compoundIndex(Indexes.ascending(FIELD_TENANT_ID), Indexes.ascending(FIELD_GENERATION)),
                new IndexOptions().name("idx_dek_tenant_generation").unique(true).background(true));
    }

    // ─── Secrets ───

    @Override
    public void upsertSecret(EncryptedSecret secret) {
        RuntimeUtilities.checkNotNull(secret, "secret");
        try {
            var filter = and(eq(FIELD_TENANT_ID, secret.getTenantId()), eq(FIELD_KEY_NAME, secret.getKeyName()));

            var update = Updates.combine(Updates.set(FIELD_ENCRYPTED_VALUE, secret.getEncryptedValue()), Updates.set(FIELD_IV, secret.getIv()),
                    Updates.set(FIELD_DEK_ID, secret.getDekId()), Updates.set(FIELD_CHECKSUM, secret.getChecksum()),
                    Updates.set(FIELD_DESCRIPTION, secret.getDescription()), Updates.set(FIELD_ALLOWED_AGENTS, secret.getAllowedAgents()),
                    Updates.set(FIELD_LAST_ACCESSED_AT, instantToString(secret.getLastAccessedAt())),
                    Updates.set(FIELD_LAST_ROTATED_AT, instantToString(secret.getLastRotatedAt())),
                    Updates.setOnInsert(FIELD_TENANT_ID, secret.getTenantId()), Updates.setOnInsert(FIELD_KEY_NAME, secret.getKeyName()),
                    Updates.setOnInsert(FIELD_CREATED_AT, instantToString(secret.getCreatedAt())));

            secretsCollection.updateOne(filter, update, new UpdateOptions().upsert(true));
        } catch (MongoException e) {
            throw new PersistenceException("Failed to upsert secret " + secret.getTenantId() + "/" + secret.getKeyName(), e);
        }
    }

    @Override
    public Optional<EncryptedSecret> findSecret(String tenantId, String keyName) {
        try {
            var doc = secretsCollection.find(and(eq(FIELD_TENANT_ID, tenantId), eq(FIELD_KEY_NAME, keyName))).first();
            return doc != null ? Optional.of(documentToSecret(doc)) : Optional.empty();
        } catch (MongoException e) {
            throw new PersistenceException("Failed to find secret " + tenantId + "/" + keyName, e);
        }
    }

    @Override
    public boolean deleteSecret(String tenantId, String keyName) {
        try {
            var result = secretsCollection.deleteOne(and(eq(FIELD_TENANT_ID, tenantId), eq(FIELD_KEY_NAME, keyName)));
            return result.getDeletedCount() > 0;
        } catch (MongoException e) {
            throw new PersistenceException("Failed to delete secret " + tenantId + "/" + keyName, e);
        }
    }

    @Override
    public List<EncryptedSecret> listSecretsByTenant(String tenantId) {
        try {
            var secrets = new ArrayList<EncryptedSecret>();
            for (var doc : secretsCollection.find(eq(FIELD_TENANT_ID, tenantId))) {
                secrets.add(documentToSecret(doc));
            }
            return secrets;
        } catch (MongoException e) {
            throw new PersistenceException("Failed to list secrets for tenant " + tenantId, e);
        }
    }

    @Override
    public boolean updateSecretSealing(EncryptedSecret secret, String expectedDekId) {
        RuntimeUtilities.checkNotNull(secret, "secret");
        try {
            // eq(field, null) matches "absent" as well as "explicitly null", which is
            // what guards a row written before dekId was ever stamped.
            var filter = and(eq(FIELD_TENANT_ID, secret.getTenantId()), eq(FIELD_KEY_NAME, secret.getKeyName()),
                    eq(FIELD_DEK_ID, expectedDekId));

            var update = Updates.combine(Updates.set(FIELD_ENCRYPTED_VALUE, secret.getEncryptedValue()), Updates.set(FIELD_IV, secret.getIv()),
                    Updates.set(FIELD_DEK_ID, secret.getDekId()),
                    Updates.set(FIELD_LAST_ROTATED_AT, instantToString(secret.getLastRotatedAt())));

            // matchedCount, not modifiedCount: winning the guard is what matters, and a
            // rewrite that happens to produce identical bytes still won it.
            return secretsCollection.updateOne(filter, update).getMatchedCount() == 1;
        } catch (MongoException e) {
            throw new PersistenceException("Failed to re-seal secret " + secret.getTenantId() + "/" + secret.getKeyName(), e);
        }
    }

    // ─── DEKs ───

    @Override
    public void upsertDek(EncryptedDek dek) {
        RuntimeUtilities.checkNotNull(dek, "dek");
        try {
            var filter = dekKey(dek.getTenantId(), dek.getGeneration());

            var update = Updates.combine(Updates.set(FIELD_ENCRYPTED_DEK, dek.getEncryptedDek()), Updates.set(FIELD_IV, dek.getIv()),
                    Updates.setOnInsert(FIELD_TENANT_ID, dek.getTenantId()), Updates.setOnInsert(FIELD_GENERATION, dek.getGeneration()),
                    Updates.setOnInsert(FIELD_CREATED_AT, instantToString(dek.getCreatedAt())));

            deksCollection.updateOne(filter, update, new UpdateOptions().upsert(true));
        } catch (MongoException e) {
            throw new PersistenceException("Failed to upsert DEK for tenant " + dek.getTenantId(), e);
        }
    }

    @Override
    public boolean insertDek(EncryptedDek dek) {
        RuntimeUtilities.checkNotNull(dek, "dek");
        var document = new Document(FIELD_TENANT_ID, dek.getTenantId()).append(FIELD_GENERATION, dek.getGeneration())
                .append(FIELD_ENCRYPTED_DEK, dek.getEncryptedDek()).append(FIELD_IV, dek.getIv())
                .append(FIELD_CREATED_AT, instantToString(dek.getCreatedAt()));
        try {
            deksCollection.insertOne(document);
            return true;
        } catch (MongoWriteException e) {
            if (e.getError().getCategory() == ErrorCategory.DUPLICATE_KEY) {
                // Somebody else installed this generation. The unique index is the
                // arbiter, so exactly one rotation proceeds.
                return false;
            }
            throw new PersistenceException("Failed to insert DEK generation for tenant " + dek.getTenantId(), e);
        } catch (MongoException e) {
            throw new PersistenceException("Failed to insert DEK generation for tenant " + dek.getTenantId(), e);
        }
    }

    @Override
    public Optional<EncryptedDek> findDek(String tenantId) {
        try {
            var doc = deksCollection.find(eq(FIELD_TENANT_ID, tenantId)).sort(Sorts.descending(FIELD_GENERATION)).first();
            return doc != null ? Optional.of(documentToDek(doc)) : Optional.empty();
        } catch (MongoException e) {
            throw new PersistenceException("Failed to find DEK for tenant " + tenantId, e);
        }
    }

    @Override
    public Optional<EncryptedDek> findDek(String tenantId, int generation) {
        try {
            var doc = deksCollection.find(dekKey(tenantId, generation)).first();
            return doc != null ? Optional.of(documentToDek(doc)) : Optional.empty();
        } catch (MongoException e) {
            throw new PersistenceException("Failed to find DEK generation " + generation + " for tenant " + tenantId, e);
        }
    }

    @Override
    public List<EncryptedDek> listDeks(String tenantId) {
        try {
            var deks = new ArrayList<EncryptedDek>();
            for (var doc : deksCollection.find(eq(FIELD_TENANT_ID, tenantId)).sort(Sorts.ascending(FIELD_GENERATION))) {
                deks.add(documentToDek(doc));
            }
            return deks;
        } catch (MongoException e) {
            throw new PersistenceException("Failed to list DEKs for tenant " + tenantId, e);
        }
    }

    @Override
    public void deleteDek(String tenantId) {
        try {
            deksCollection.deleteMany(eq(FIELD_TENANT_ID, tenantId));
        } catch (MongoException e) {
            throw new PersistenceException("Failed to delete DEK for tenant " + tenantId, e);
        }
    }

    /**
     * A row with no {@code generation} field is generation 1. The boot migration
     * backfills those, but a replica still running an older build writes fresh ones
     * without it, so generation 1 matches both spellings for as long as a rolling
     * upgrade lasts.
     */
    private static Bson dekKey(String tenantId, int generation) {
        return generation == EncryptedDek.FIRST_GENERATION
                ? and(eq(FIELD_TENANT_ID, tenantId), Filters.or(eq(FIELD_GENERATION, generation), Filters.exists(FIELD_GENERATION, false)))
                : and(eq(FIELD_TENANT_ID, tenantId), eq(FIELD_GENERATION, generation));
    }

    @Override
    public List<EncryptedDek> listAllDeks() {
        try {
            var deks = new ArrayList<EncryptedDek>();
            for (var doc : deksCollection.find()) {
                deks.add(documentToDek(doc));
            }
            return deks;
        } catch (MongoException e) {
            throw new PersistenceException("Failed to list all DEKs", e);
        }
    }

    // ─── Metadata ───

    @Override
    public String getMetaValue(String key) {
        try {
            var doc = metaCollection.find(eq("key", key)).first();
            return doc != null ? doc.getString("value") : null;
        } catch (MongoException e) {
            throw new PersistenceException("Failed to read meta value: " + key, e);
        }
    }

    @Override
    public void setMetaValue(String key, String value) {
        try {
            var filter = eq("key", key);
            var update = Updates.combine(
                    Updates.set("value", value),
                    Updates.setOnInsert("key", key));
            metaCollection.updateOne(filter, update, new UpdateOptions().upsert(true));
        } catch (MongoException e) {
            throw new PersistenceException("Failed to write meta value: " + key, e);
        }
    }

    // ─── Document conversion ───

    private EncryptedSecret documentToSecret(Document doc) {
        var secret = new EncryptedSecret();
        secret.setId(doc.getObjectId("_id") != null ? doc.getObjectId("_id").toHexString() : null);
        secret.setTenantId(doc.getString(FIELD_TENANT_ID));
        secret.setKeyName(doc.getString(FIELD_KEY_NAME));
        secret.setEncryptedValue(doc.getString(FIELD_ENCRYPTED_VALUE));
        secret.setIv(doc.getString(FIELD_IV));
        secret.setDekId(doc.getString(FIELD_DEK_ID));
        secret.setChecksum(doc.getString(FIELD_CHECKSUM));
        secret.setDescription(doc.getString(FIELD_DESCRIPTION));
        secret.setAllowedAgents(doc.getList(FIELD_ALLOWED_AGENTS, String.class, List.of("*")));
        secret.setCreatedAt(parseInstant(doc.getString(FIELD_CREATED_AT)));
        secret.setLastAccessedAt(parseInstant(doc.getString(FIELD_LAST_ACCESSED_AT)));
        secret.setLastRotatedAt(parseInstant(doc.getString(FIELD_LAST_ROTATED_AT)));
        return secret;
    }

    private EncryptedDek documentToDek(Document doc) {
        Object generation = doc.get(FIELD_GENERATION);
        return new EncryptedDek(doc.getObjectId("_id") != null ? doc.getObjectId("_id").toHexString() : null, doc.getString(FIELD_TENANT_ID),
                generation instanceof Number number ? number.intValue() : EncryptedDek.FIRST_GENERATION, doc.getString(FIELD_ENCRYPTED_DEK),
                doc.getString(FIELD_IV), parseInstant(doc.getString(FIELD_CREATED_AT)));
    }

    private static String instantToString(Instant instant) {
        return instant != null ? instant.toString() : null;
    }

    private static Instant parseInstant(String str) {
        if (str == null || str.isBlank())
            return null;
        try {
            return Instant.parse(str);
        } catch (Exception e) {
            return null;
        }
    }
}
