/*
 * Copyright (c) 2016-2026 EDDI contributors
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
 * Secrets are scoped at the <b>tenant level</b>, identified by
 * {@code (tenantId, keyName)}. There is no agent-level scoping — access control
 * is via <b>configuration authorship</b>: the admin who writes the agent config
 * decides which vault references ({@code ${eddivault:keyName}}) to include.
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
     * Rotate the Data Encryption Key (DEK) for a specific tenant. Generates a new
     * DEK, re-encrypts all secrets for the tenant, and replaces the old DEK.
     *
     * @param tenantId
     *            the tenant whose DEK to rotate
     * @return the number of secrets re-encrypted
     * @throws SecretProviderException
     *             if rotation fails
     */
    int rotateDek(String tenantId) throws SecretProviderException;

    /**
     * Check if the secret provider is properly configured and operational.
     *
     * @return true if the provider can resolve secrets
     */
    boolean isAvailable();

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
