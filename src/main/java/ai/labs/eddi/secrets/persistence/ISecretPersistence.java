package ai.labs.eddi.secrets.persistence;

import ai.labs.eddi.secrets.model.EncryptedDek;
import ai.labs.eddi.secrets.model.EncryptedSecret;

import java.util.List;
import java.util.Optional;

/**
 * Low-level persistence interface for secrets vault storage. Implementations
 * handle CRUD operations on encrypted secrets and DEKs in the database.
 * <p>
 * This interface is separate from {@link ai.labs.eddi.secrets.ISecretProvider}
 * which handles the higher-level encrypt/decrypt/resolve logic. The persistence
 * layer only deals with storing and retrieving already-encrypted data.
 * <p>
 * Implementations:
 * <ul>
 * <li>{@code MongoSecretPersistence} — MongoDB (default)</li>
 * <li>{@code PostgresSecretPersistence} — PostgreSQL (activated when
 * {@code eddi.db.type=postgres})</li>
 * </ul>
 *
 * @author ginccc
 * @since 6.0.0
 */
public interface ISecretPersistence {

    // ─── Secrets ───

    /**
     * Insert or update an encrypted secret. The composite key is
     * {@code (tenantId, keyName)}.
     */
    void upsertSecret(EncryptedSecret secret);

    /**
     * Find an encrypted secret by tenant and key name.
     */
    Optional<EncryptedSecret> findSecret(String tenantId, String keyName);

    /**
     * Delete an encrypted secret.
     *
     * @return true if a secret was actually deleted
     */
    boolean deleteSecret(String tenantId, String keyName);

    /**
     * List all encrypted secrets for a given tenant.
     */
    List<EncryptedSecret> listSecretsByTenant(String tenantId);

    // ─── DEKs ───

    /**
     * Insert or update an encrypted DEK. The key is {@code tenantId}.
     */
    void upsertDek(EncryptedDek dek);

    /**
     * Find an encrypted DEK by tenant ID.
     */
    Optional<EncryptedDek> findDek(String tenantId);
}
