package ai.labs.eddi.secrets;

import ai.labs.eddi.secrets.model.SecretMetadata;
import ai.labs.eddi.secrets.model.SecretReference;

import java.util.List;

/**
 * Service Provider Interface for secrets management.
 * Implementations handle the actual storage and retrieval of encrypted secrets.
 * <p>
 * All implementations MUST ensure:
 * <ul>
 *   <li>Plaintext values exist only in volatile JVM memory</li>
 *   <li>Namespace isolation: tenant/bot scoping is enforced</li>
 *   <li>Thread safety for concurrent access</li>
 * </ul>
 */
public interface ISecretProvider {

    /**
     * Resolve a secret reference to its plaintext value.
     * The plaintext MUST only be used ephemerally — never persisted.
     *
     * @param reference the secret reference to resolve
     * @return the plaintext secret value
     * @throws SecretNotFoundException if the secret does not exist
     * @throws SecretProviderException if resolution fails
     */
    String resolve(SecretReference reference) throws SecretNotFoundException, SecretProviderException;

    /**
     * Store a new secret. If a secret with the same reference already exists, it is overwritten.
     *
     * @param reference the secret reference (tenant/bot/keyName)
     * @param plaintext the plaintext value to encrypt and store
     * @throws SecretProviderException if storage fails
     */
    void store(SecretReference reference, String plaintext) throws SecretProviderException;

    /**
     * Delete a secret from the backend.
     *
     * @param reference the secret reference to delete
     * @throws SecretNotFoundException if the secret does not exist
     * @throws SecretProviderException if deletion fails
     */
    void delete(SecretReference reference) throws SecretNotFoundException, SecretProviderException;

    /**
     * Get non-sensitive metadata about a secret (timestamps, checksum).
     * Plaintext is NEVER returned through this method.
     *
     * @param reference the secret reference
     * @return metadata about the secret
     * @throws SecretNotFoundException if the secret does not exist
     * @throws SecretProviderException if the operation fails
     */
    SecretMetadata getMetadata(SecretReference reference) throws SecretNotFoundException, SecretProviderException;

    /**
     * List all secret keys for a given tenant and bot namespace.
     *
     * @param tenantId the tenant identifier
     * @param botId    the bot identifier
     * @return list of metadata for all secrets in the namespace
     * @throws SecretProviderException if the operation fails
     */
    List<SecretMetadata> listKeys(String tenantId, String botId) throws SecretProviderException;

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
