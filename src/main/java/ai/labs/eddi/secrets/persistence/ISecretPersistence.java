/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.secrets.persistence;

import ai.labs.eddi.secrets.model.EncryptedDek;
import ai.labs.eddi.secrets.model.EncryptedSecret;

import java.time.Instant;
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
     * <p>
     * A {@code null} {@code allowedAgents} or {@code description} means "not
     * supplied": an update leaves the stored value alone, and an insert writes the
     * wildcard grant and no description. A value write that did not mention the
     * grant used to reset a narrowed grant to {@code ["*"]} and wipe the
     * description — and, being a whole-row write, to revert any grant edit that
     * landed between the caller's read and this write.
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

    /**
     * Rewrites one secret's ciphertext, IV and dekId, but only while the row still
     * names {@code expectedDekId}.
     *
     * @param expectedDekId
     *            the dekId read from the row, exactly as stored — a missing value
     *            matches a missing value, so a pre-generation row is guarded on
     *            being pre-generation
     * @return false if another writer changed the row's sealing first, in which
     *         case nothing was written
     * @throws PersistenceException
     *             if the write fails
     */
    boolean updateSecretSealing(EncryptedSecret secret, String expectedDekId);

    /**
     * Rewrites one secret's {@code allowedAgents} and {@code description}, and
     * nothing else.
     * <p>
     * Deliberately narrow. {@link #upsertSecret} can only express "write the whole
     * row", so an operator who wants to widen a grant through it has to supply the
     * ciphertext — which means holding the plaintext, which is precisely what a
     * vault exists to make unnecessary. This method takes no ciphertext, no IV, no
     * dekId and no checksum, so a grant edit <em>cannot</em> touch the value even
     * by mistake: the guarantee is in the signature, not in a caller's discipline.
     * <p>
     * {@code createdAt} and {@code lastRotatedAt} are untouched for the same
     * reason. A grant edit is not a rotation and must not read as one afterwards.
     *
     * @param allowedAgents
     *            the replacement grant list, already canonicalised by the caller
     * @param description
     *            the replacement description; a caller that wants the existing one
     *            kept passes it back in, because "leave unchanged" is a
     *            request-level notion and not a storage one
     * @return false if no such {@code (tenantId, keyName)} row exists, in which
     *         case nothing was written
     * @throws PersistenceException
     *             if the write fails
     */
    boolean updateSecretGrant(String tenantId, String keyName, List<String> allowedAgents, String description);

    /**
     * {@link #updateSecretGrant} guarded on the grant the caller last saw.
     * <p>
     * Two operators editing the same grant from two browser tabs each send a full
     * replacement list built from what they loaded, so the second write silently
     * reverts the first — including a narrowing that was the whole point of the
     * first. With the list the editor started from as a precondition the second
     * write matches nothing and the caller can say so.
     *
     * @param expectedAllowedAgents
     *            the grant the caller read, canonicalised — {@code ["*"]} also
     *            matches a row whose grant is absent or empty, since every layer
     *            reads those as the wildcard too
     * @return false if the row does not exist <em>or</em> its grant is no longer
     *         {@code expectedAllowedAgents}; the caller re-reads to tell which
     * @throws PersistenceException
     *             if the write fails
     */
    boolean updateSecretGrantIfUnchanged(String tenantId, String keyName, List<String> expectedAllowedAgents, List<String> allowedAgents,
                                         String description);

    /**
     * Records that a secret was just resolved, writing {@code lastAccessedAt} and
     * nothing else.
     * <p>
     * Resolution used to record this by re-upserting the whole row it had read.
     * That is a read-modify-write of every field, so a resolve that read the row
     * just before a grant edit — or a rotation — wrote it back a moment later and
     * silently undid that edit. A single-field write has nothing stale to put back.
     *
     * @throws PersistenceException
     *             if the write fails; callers treat this as best-effort
     */
    void touchLastAccessed(String tenantId, String keyName, Instant lastAccessedAt);

    // ─── DEKs ───

    /**
     * Insert or update an encrypted DEK. The key is {@code (tenantId, generation)}.
     *
     * @throws PersistenceException
     *             if the write fails
     */
    void upsertDek(EncryptedDek dek);

    /**
     * Inserts a DEK generation, and only if that generation does not exist yet.
     * <p>
     * This is the commit point of a DEK rotation, which is why it is an insert and
     * not an upsert: two replicas rotating the same tenant at once must produce one
     * winner and one clean refusal, never two keys claiming the same generation.
     *
     * @return false if {@code (tenantId, generation)} was already taken
     * @throws PersistenceException
     *             if the write fails for any other reason
     */
    boolean insertDek(EncryptedDek dek);

    /**
     * Rewrites one DEK generation's wrapping — its encrypted key and IV — but only
     * while the row still carries {@code expectedIv}.
     * <p>
     * Used by KEK rotation. An upsert here could recreate a generation a tenant
     * reset deleted a moment earlier, or overwrite a wrapping another rotation has
     * just written; the guard turns both into a clean {@code false} the caller
     * re-reads.
     *
     * @return false if the row is gone or was re-wrapped by somebody else first, in
     *         which case nothing was written
     * @throws PersistenceException
     *             if the write fails
     */
    boolean updateDekWrapping(EncryptedDek dek, String expectedIv);

    /**
     * Find the tenant's <b>active</b> DEK — the highest generation it holds.
     *
     * @throws PersistenceException
     *             if the read fails
     */
    Optional<EncryptedDek> findDek(String tenantId);

    /**
     * Find one specific DEK generation, so ciphertext can be opened with the key it
     * names rather than with whatever is newest.
     *
     * @throws PersistenceException
     *             if the read fails
     */
    Optional<EncryptedDek> findDek(String tenantId, int generation);

    /**
     * Every generation a tenant holds, oldest first.
     *
     * @throws PersistenceException
     *             if the read fails
     */
    List<EncryptedDek> listDeks(String tenantId);

    /**
     * Delete every DEK generation for a specific tenant. Used when a tenant's vault
     * is reset.
     *
     * @throws PersistenceException
     *             if the delete fails
     */
    void deleteDek(String tenantId);

    /**
     * List all encrypted DEKs across all tenants and all generations. Used during
     * KEK rotation to re-wrap every key with the new master key — every generation,
     * since ciphertext that has not been swept yet still depends on an older one.
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

    /**
     * Writes a metadata value only if the key holds none yet, and returns whatever
     * the key holds afterwards — this call's value when it won, the value somebody
     * else wrote first when it did not.
     * <p>
     * This is how key material that every replica must agree on is created. An
     * unconditional {@link #setMetaValue} lets two replicas booting at once each
     * write their own random salt, and the one that loses the race keeps deriving
     * its KEK from a salt nobody will ever read again: every DEK it wrapped becomes
     * unreadable at its next restart. Returning the stored value, rather than a
     * boolean, is what lets the loser adopt the winner's value in the same call.
     * <p>
     * The default is a read-write-read for stores with no conditional write; both
     * shipped stores override it with an atomic one.
     *
     * @return the value stored under {@code key} once the call returns, or null
     *         only for a store with no metadata support at all
     * @throws PersistenceException
     *             if the read or write fails
     */
    default String putMetaValueIfAbsent(String key, String value) {
        String existing = getMetaValue(key);
        if (existing != null) {
            return existing;
        }
        setMetaValue(key, value);
        String stored = getMetaValue(key);
        return stored != null ? stored : value;
    }

    /**
     * Removes a metadata value. Removing an absent key is not an error.
     *
     * @throws PersistenceException
     *             if the delete fails
     */
    default void deleteMetaValue(String key) {
        // Default = no-op
    }
}
