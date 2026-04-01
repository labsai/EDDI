package ai.labs.eddi.secrets.persistence;

import ai.labs.eddi.secrets.model.EncryptedDek;
import ai.labs.eddi.secrets.model.EncryptedSecret;
import ai.labs.eddi.utils.RuntimeUtilities;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.Updates;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;
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

    private final MongoCollection<Document> secretsCollection;
    private final MongoCollection<Document> deksCollection;

    @Inject
    public MongoSecretPersistence(MongoDatabase database) {
        RuntimeUtilities.checkNotNull(database, "database");
        this.secretsCollection = database.getCollection(COLLECTION_SECRETS);
        this.deksCollection = database.getCollection(COLLECTION_DEKS);
        ensureIndexes();
    }

    private void ensureIndexes() {
        // Unique compound index on (tenantId, keyName)
        secretsCollection.createIndex(Indexes.compoundIndex(Indexes.ascending(FIELD_TENANT_ID), Indexes.ascending(FIELD_KEY_NAME)),
                new IndexOptions().name("idx_secret_tenant_key").unique(true).background(true));

        // Unique index on tenantId for DEKs (one DEK per tenant)
        deksCollection.createIndex(Indexes.ascending(FIELD_TENANT_ID), new IndexOptions().name("idx_dek_tenant").unique(true).background(true));

        LOGGER.info("Secrets vault MongoDB indexes ensured");
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
        } catch (com.mongodb.MongoException e) {
            throw new PersistenceException("Failed to upsert secret " + secret.getTenantId() + "/" + secret.getKeyName(), e);
        }
    }

    @Override
    public Optional<EncryptedSecret> findSecret(String tenantId, String keyName) {
        try {
            var doc = secretsCollection.find(and(eq(FIELD_TENANT_ID, tenantId), eq(FIELD_KEY_NAME, keyName))).first();
            return doc != null ? Optional.of(documentToSecret(doc)) : Optional.empty();
        } catch (com.mongodb.MongoException e) {
            throw new PersistenceException("Failed to find secret " + tenantId + "/" + keyName, e);
        }
    }

    @Override
    public boolean deleteSecret(String tenantId, String keyName) {
        try {
            var result = secretsCollection.deleteOne(and(eq(FIELD_TENANT_ID, tenantId), eq(FIELD_KEY_NAME, keyName)));
            return result.getDeletedCount() > 0;
        } catch (com.mongodb.MongoException e) {
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
        } catch (com.mongodb.MongoException e) {
            throw new PersistenceException("Failed to list secrets for tenant " + tenantId, e);
        }
    }

    // ─── DEKs ───

    @Override
    public void upsertDek(EncryptedDek dek) {
        RuntimeUtilities.checkNotNull(dek, "dek");
        try {
            var filter = eq(FIELD_TENANT_ID, dek.getTenantId());

            var update = Updates.combine(Updates.set(FIELD_ENCRYPTED_DEK, dek.getEncryptedDek()), Updates.set(FIELD_IV, dek.getIv()),
                    Updates.setOnInsert(FIELD_TENANT_ID, dek.getTenantId()),
                    Updates.setOnInsert(FIELD_CREATED_AT, instantToString(dek.getCreatedAt())));

            deksCollection.updateOne(filter, update, new UpdateOptions().upsert(true));
        } catch (com.mongodb.MongoException e) {
            throw new PersistenceException("Failed to upsert DEK for tenant " + dek.getTenantId(), e);
        }
    }

    @Override
    public Optional<EncryptedDek> findDek(String tenantId) {
        try {
            var doc = deksCollection.find(eq(FIELD_TENANT_ID, tenantId)).first();
            return doc != null ? Optional.of(documentToDek(doc)) : Optional.empty();
        } catch (com.mongodb.MongoException e) {
            throw new PersistenceException("Failed to find DEK for tenant " + tenantId, e);
        }
    }

    @Override
    public void deleteDek(String tenantId) {
        try {
            deksCollection.deleteOne(eq(FIELD_TENANT_ID, tenantId));
        } catch (com.mongodb.MongoException e) {
            throw new PersistenceException("Failed to delete DEK for tenant " + tenantId, e);
        }
    }

    @Override
    public List<EncryptedDek> listAllDeks() {
        try {
            var deks = new ArrayList<EncryptedDek>();
            for (var doc : deksCollection.find()) {
                deks.add(documentToDek(doc));
            }
            return deks;
        } catch (com.mongodb.MongoException e) {
            throw new PersistenceException("Failed to list all DEKs", e);
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
        return new EncryptedDek(doc.getObjectId("_id") != null ? doc.getObjectId("_id").toHexString() : null, doc.getString(FIELD_TENANT_ID),
                doc.getString(FIELD_ENCRYPTED_DEK), doc.getString(FIELD_IV), parseInstant(doc.getString(FIELD_CREATED_AT)));
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
