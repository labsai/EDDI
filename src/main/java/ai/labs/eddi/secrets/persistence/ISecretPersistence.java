/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
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
     *
     * @throws PersistenceException
     *             if the write fails
     */
    void upsertSecret(EncryptedSecret secret);

    /**
     * Find an encrypted secret by tenant and key name.
     *
     * @throws PersistenceException
     *             if the read fails
     */
    Optional<EncryptedSecret> findSecret(String tenantId, String keyName);

    /**
     * Delete an encrypted secret.
     *
     * @return true if a secret was actually deleted
     * @throws PersistenceException
     *             if the delete fails
     */
    boolean deleteSecret(String tenantId, String keyName);

    /**
     * List all encrypted secrets for a given tenant.
     *
     * @throws PersistenceException
     *             if the read fails
     */
    List<EncryptedSecret> listSecretsByTenant(String tenantId);

    // ─── DEKs ───

    /**
     * Insert or update an encrypted DEK. The key is {@code tenantId}.
     *
     * @throws PersistenceException
     *             if the write fails
     */
    void upsertDek(EncryptedDek dek);

    /**
     * Find an encrypted DEK by tenant ID.
     *
     * @throws PersistenceException
     *             if the read fails
     */
    Optional<EncryptedDek> findDek(String tenantId);

    /**
     * Delete an encrypted DEK for a specific tenant. Used during DEK rotation.
     *
     * @throws PersistenceException
     *             if the delete fails
     */
    void deleteDek(String tenantId);

    /**
     * List all encrypted DEKs across all tenants. Used during KEK rotation to
     * re-encrypt every tenant's DEK with the new master key.
     *
     * @throws PersistenceException
     *             if the read fails
     */
    List<EncryptedDek> listAllDeks();

    // ─── Metadata ───

    /**
     * Read a vault infrastructure metadata value by key. Used for per-deployment
     * configuration like the KEK salt.
     *
     * @param key
     *            the metadata key
     * @return the value, or null if not found
     * @throws PersistenceException
     *             if the read fails
     */
    default String getMetaValue(String key) {
        return null; // Default = no metadata store available
    }

    /**
     * Write a vault infrastructure metadata value by key. Creates or updates the
     * entry.
     *
     * @param key
     *            the metadata key
     * @param value
     *            the value to store
     * @throws PersistenceException
     *             if the write fails
     */
    default void setMetaValue(String key, String value) {
        // Default = no-op
    }
}
