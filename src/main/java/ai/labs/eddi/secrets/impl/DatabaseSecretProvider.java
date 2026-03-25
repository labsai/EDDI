package ai.labs.eddi.secrets.impl;

import ai.labs.eddi.secrets.ISecretProvider;
import ai.labs.eddi.secrets.VaultStartupBanner;
import ai.labs.eddi.secrets.crypto.EnvelopeCrypto;
import ai.labs.eddi.secrets.model.*;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Optional;

/**
 * Default ISecretProvider implementation using envelope encryption. Secrets are
 * encrypted with tenant-scoped DEKs, which are encrypted with the KEK.
 * <p>
 * Storage is in-memory for now (ConcurrentHashMaps). When the EDDI database
 * abstraction supports a secrets collection, this can be swapped to persistent
 * storage without changing the interface.
 * <p>
 * The KEK (Master Key) is supplied via the {@code EDDI_VAULT_MASTER_KEY}
 * environment variable. If not set, the provider is disabled and all operations
 * throw exceptions.
 */
@ApplicationScoped
public class DatabaseSecretProvider implements ISecretProvider {

    private static final Logger LOGGER = Logger.getLogger(DatabaseSecretProvider.class);

    private final Optional<String> masterKeyConfig;

    private byte[] kek; // Key Encryption Key derived from master key
    private boolean available = false;

    // In-memory storage (will be replaced with DB collections later)
    private final Map<String, EncryptedDek> dekStore = new ConcurrentHashMap<>(); // tenantId -> DEK
    private final Map<String, EncryptedSecret> secretStore = new ConcurrentHashMap<>(); // compositeKey -> secret

    @Inject
    public DatabaseSecretProvider(@ConfigProperty(name = "eddi.vault.master-key") Optional<String> masterKeyConfig) {
        this.masterKeyConfig = masterKeyConfig;
    }

    @PostConstruct
    void init() {
        if (masterKeyConfig.isEmpty() || masterKeyConfig.get().isBlank()) {
            VaultStartupBanner.printDisabled();
            return;
        }

        this.kek = EnvelopeCrypto.deriveKeyFromString(masterKeyConfig.get());
        this.available = true;
        VaultStartupBanner.printEnabled();
    }

    @Override
    public String resolve(SecretReference reference) throws SecretNotFoundException, SecretProviderException {
        ensureAvailable();
        String key = compositeKey(reference);
        EncryptedSecret secret = secretStore.get(key);
        if (secret == null) {
            throw new SecretNotFoundException("Secret not found: " + reference.tenantId() + "/" + reference.agentId() + "/" + reference.keyName());
        }

        // Decrypt: KEK -> DEK -> plaintext
        byte[] dek = getOrCreateDek(reference.tenantId());
        String plaintext = EnvelopeCrypto.decrypt(secret.getEncryptedValue(), secret.getIv(), dek);

        // Update last accessed timestamp
        secret.setLastAccessedAt(Instant.now());

        return plaintext;
    }

    @Override
    public void store(SecretReference reference, String plaintext) throws SecretProviderException {
        ensureAvailable();
        byte[] dek = getOrCreateDek(reference.tenantId());

        // Encrypt the plaintext with the tenant's DEK
        EnvelopeCrypto.EncryptionResult result = EnvelopeCrypto.encrypt(plaintext, dek);
        String checksum = EnvelopeCrypto.sha256Hex(plaintext);

        String key = compositeKey(reference);
        EncryptedSecret existing = secretStore.get(key);

        EncryptedSecret secret = new EncryptedSecret(existing != null ? existing.getId() : UUID.randomUUID().toString(), reference.tenantId(),
                reference.agentId(), reference.keyName(), result.ciphertext(), result.iv(), reference.tenantId(), // dekId is the tenantId for now
                checksum, existing != null ? existing.getCreatedAt() : Instant.now(), null);

        secretStore.put(key, secret);
        LOGGER.infov("Secret stored: {0}/{1}/{2}", reference.tenantId(), reference.agentId(), reference.keyName());
    }

    @Override
    public void delete(SecretReference reference) throws SecretNotFoundException, SecretProviderException {
        ensureAvailable();
        String key = compositeKey(reference);
        EncryptedSecret removed = secretStore.remove(key);
        if (removed == null) {
            throw new SecretNotFoundException("Secret not found: " + reference.tenantId() + "/" + reference.agentId() + "/" + reference.keyName());
        }
        LOGGER.infov("Secret deleted: {0}/{1}/{2}", reference.tenantId(), reference.agentId(), reference.keyName());
    }

    @Override
    public SecretMetadata getMetadata(SecretReference reference) throws SecretNotFoundException, SecretProviderException {
        ensureAvailable();
        String key = compositeKey(reference);
        EncryptedSecret secret = secretStore.get(key);
        if (secret == null) {
            throw new SecretNotFoundException("Secret not found: " + reference.tenantId() + "/" + reference.agentId() + "/" + reference.keyName());
        }
        return new SecretMetadata(secret.getTenantId(), secret.getAgentId(), secret.getKeyName(), secret.getCreatedAt(), secret.getLastAccessedAt(),
                secret.getChecksum());
    }

    @Override
    public List<SecretMetadata> listKeys(String tenantId, String agentId) throws SecretProviderException {
        ensureAvailable();
        return secretStore.values().stream().filter(s -> s.getTenantId().equals(tenantId) && s.getAgentId().equals(agentId)).map(
                s -> new SecretMetadata(s.getTenantId(), s.getAgentId(), s.getKeyName(), s.getCreatedAt(), s.getLastAccessedAt(), s.getChecksum()))
                .toList();
    }

    @Override
    public boolean isAvailable() {
        return available;
    }

    // === Private helpers ===

    private byte[] getOrCreateDek(String tenantId) throws SecretProviderException {
        EncryptedDek encryptedDek = dekStore.get(tenantId);
        if (encryptedDek != null) {
            // Decrypt the DEK using the KEK
            return EnvelopeCrypto.decryptDek(encryptedDek.getEncryptedDek(), encryptedDek.getIv(), kek);
        }

        // Generate a new DEK for this tenant
        byte[] newDek = EnvelopeCrypto.generateDek();
        EnvelopeCrypto.EncryptionResult encResult = EnvelopeCrypto.encryptDek(newDek, kek);

        EncryptedDek dek = new EncryptedDek(UUID.randomUUID().toString(), tenantId, encResult.ciphertext(), encResult.iv(), Instant.now());

        dekStore.put(tenantId, dek);
        LOGGER.infov("Generated new DEK for tenant: {0}", tenantId);
        return newDek;
    }

    private void ensureAvailable() throws SecretProviderException {
        if (!available) {
            throw new SecretProviderException("Secrets Vault is not available. Set EDDI_VAULT_MASTER_KEY environment variable.");
        }
    }

    private static String compositeKey(SecretReference ref) {
        return ref.tenantId() + "/" + ref.agentId() + "/" + ref.keyName();
    }
}
