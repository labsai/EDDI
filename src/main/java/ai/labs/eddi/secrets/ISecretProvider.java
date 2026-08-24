/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.secrets;

import ai.labs.eddi.secrets.model.SecretMetadata;
import ai.labs.eddi.secrets.model.SecretReference;

import java.util.List;

/**
 * Service Provider Interface for secrets management. Implementations handle the
 * actual storage and retrieval of encrypted secrets.
 * <p>
 * Secrets are stored at the <b>tenant level</b>, identified by
 * {@code (tenantId, keyName)}. Which agents may use a secret is governed by
 * {@code SecretMetadata.allowedAgents}, checked when an agent is deployed (see
 * {@link VaultGrantGate}) — not by this interface, whose implementations
 * resolve any valid reference they are asked for.
 * <p>
 * All implementations MUST ensure:
 * <ul>
 * <li>Plaintext values exist only in volatile JVM memory</li>
 * <li>Namespace isolation: tenant scoping is enforced</li>
 * <li>Thread safety for concurrent access</li>
 * </ul>
 *
 * @author ginccc
 * @since 6.0.0
 */
public interface ISecretProvider {

    /**
     * Resolve a secret reference to its plaintext value. The plaintext MUST only be
     * used ephemerally — never persisted.
     *
     * @param reference
     *            the secret reference to resolve
     * @return the plaintext secret value
     * @throws SecretNotFoundException
     *             if the secret does not exist
     * @throws SecretProviderException
     *             if resolution fails
     */
    String resolve(SecretReference reference) throws SecretNotFoundException, SecretProviderException;

    /**
     * Store a new secret or update an existing one.
     *
     * @param reference
     *            the secret reference (tenantId/keyName)
     * @param plaintext
     *            the plaintext value to encrypt and store
     * @param description
     *            human-readable description (nullable)
     * @param allowedAgents
     *            list of agent IDs allowed to use this secret, or {@code ["*"]} for
     *            all agents (nullable → defaults to ["*"])
     * @throws SecretProviderException
     *             if storage fails
     */
    void store(SecretReference reference, String plaintext, String description, List<String> allowedAgents) throws SecretProviderException;

    /**
     * Delete a secret from the backend.
     *
     * @param reference
     *            the secret reference to delete
     * @throws SecretNotFoundException
     *             if the secret does not exist
     * @throws SecretProviderException
     *             if deletion fails
     */
    void delete(SecretReference reference) throws SecretNotFoundException, SecretProviderException;

    /**
     * Get non-sensitive metadata about a secret (timestamps, checksum, description,
     * allowedAgents). Plaintext is NEVER returned through this method.
     *
     * @param reference
     *            the secret reference
     * @return metadata about the secret
     * @throws SecretNotFoundException
     *             if the secret does not exist
     * @throws SecretProviderException
     *             if the operation fails
     */
    SecretMetadata getMetadata(SecretReference reference) throws SecretNotFoundException, SecretProviderException;

    /**
     * List all secret keys for a given tenant.
     *
     * @param tenantId
     *            the tenant identifier
     * @return list of metadata for all secrets in the tenant
     * @throws SecretProviderException
     *             if the operation fails
     */
    List<SecretMetadata> listKeys(String tenantId) throws SecretProviderException;

    /**
     * Rotate the Data Encryption Key (DEK) for a specific tenant: adds a new DEK
     * generation, then sweeps existing sealed data onto it.
     * <p>
     * The old generations are kept, not replaced, so a row that has not been swept
     * yet still names a key that exists and decrypts. The single irreversible step
     * is the insert of the new generation; everything after it is idempotent and
     * safe to re-run.
     *
     * @param tenantId
     *            the tenant whose DEK to rotate
     * @return the number of secrets moved onto the new generation
     * @throws SecretProviderException
     *             if the new generation could not be installed, or if it was
     *             installed but some rows are still on an older one — the message
     *             says which case it is
     */
    int rotateDek(String tenantId) throws SecretProviderException;

    /**
     * Reset the vault for a specific tenant. Deletes ALL secrets and the DEK,
     * allowing the vault to start fresh with the current master key.
     * <p>
     * This is a <b>destructive</b> operation — all encrypted secrets for the tenant
     * will be permanently deleted. This bypasses DEK decryption entirely, making it
     * safe to call even when the master key has changed.
     *
     * @param tenantId
     *            the tenant to reset
     * @return the number of secrets that were deleted
     * @throws SecretProviderException
     *             if the reset fails
     */
    int resetTenant(String tenantId) throws SecretProviderException;

    /**
     * Check if the secret provider is properly configured and operational.
     *
     * @return true if the provider can resolve secrets
     */
    boolean isAvailable();

    /**
     * Encrypt arbitrary runtime data with a tenant's data-encryption key, without
     * storing it as a named secret.
     * <p>
     * Exists for OAuth grants. A grant is not a secret in the vault's sense — it is
     * not author-managed, not referenced by name, not subject to
     * {@code allowedAgents}, and must never appear in an export — so it does not
     * belong in the secret collection. What it DOES need is exactly the vault's
     * envelope encryption and exactly the vault's per-tenant DEK: a second key
     * hierarchy for refresh tokens would mean a second key to rotate, a second
     * master key to lose, and a second place for the crypto to be subtly wrong.
     *
     * @param tenantId
     *            whose DEK to seal with; created on first use, as for a secret
     * @param plaintext
     *            the value to seal; {@code null} passes through as {@code null}
     *            rather than sealing an empty value, so a grant that simply has no
     *            refresh token stays distinguishable from one whose refresh token
     *            sealed to nothing
     * @return the ciphertext and the name of the key that sealed it, or
     *         {@code null} when {@code plaintext} was null
     * @throws SecretProviderException
     *             when the vault is inactive or the DEK cannot be obtained. Note
     *             the availability check comes first: a null plaintext against an
     *             inactive vault still throws, it does not return null
     */
    SealedValue seal(String tenantId, String plaintext) throws SecretProviderException;

    /**
     * Reverse of {@link #seal}.
     *
     * @param tenantId
     *            whose DEK the value was sealed with
     * @param sealed
     *            the ciphertext to open, as {@link #seal} returned it
     * @return the plaintext, or {@code null} when {@code sealed} is null or carries
     *         no ciphertext — the mirror of {@code seal}, so a value that was never
     *         sealed round-trips as absent rather than as an error
     * @throws SecretProviderException
     *             when the vault is inactive, the DEK cannot be obtained, or the
     *             ciphertext fails its authentication tag. As with {@code seal},
     *             the availability check precedes the null check
     */
    String unseal(String tenantId, SealedValue sealed) throws SecretProviderException;

    /**
     * Ciphertext, its initialization vector, and the name of the key that sealed
     * it.
     * <p>
     * The {@code dekId} is what makes rotation survivable: a value carries the DEK
     * generation it was sealed under, so it stays readable after the tenant moves
     * to a newer generation and until a sweep has re-sealed it. Callers persist it
     * alongside the ciphertext and hand it back on {@link #unseal}.
     * <p>
     * {@code toString} is overridden because this travels through log statements
     * and debugger views on the token-refresh path.
     */
    record SealedValue(String ciphertext, String iv, String dekId) {

        /**
         * For ciphertext whose sealing key is not recorded — everything written before
         * generations existed. It reads as generation 1, which is what those rows are
         * actually sealed with.
         */
        public SealedValue(String ciphertext, String iv) {
            this(ciphertext, iv, null);
        }

        @Override
        public String toString() {
            return "SealedValue[<REDACTED>]";
        }
    }

    // === Exception types ===

    class SecretNotFoundException extends Exception {
        public SecretNotFoundException(String message) {
            super(message);
        }
    }

    class SecretProviderException extends Exception {
        public SecretProviderException(String message) {
            super(message);
        }

        public SecretProviderException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
