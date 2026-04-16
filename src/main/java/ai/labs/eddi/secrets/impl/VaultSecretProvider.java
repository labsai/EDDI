package ai.labs.eddi.secrets.impl;

import ai.labs.eddi.secrets.ISecretProvider;
import ai.labs.eddi.secrets.VaultStartupBanner;
import ai.labs.eddi.secrets.crypto.EnvelopeCrypto;
import ai.labs.eddi.secrets.crypto.VaultSaltManager;
import ai.labs.eddi.secrets.model.*;
import ai.labs.eddi.secrets.persistence.ISecretPersistence;
import ai.labs.eddi.secrets.persistence.PersistenceException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.*;
import java.util.AbstractMap.SimpleEntry;

/**
 * Production-grade {@link ISecretProvider} using envelope encryption with
 * persistent storage.
 * <p>
 * <b>Architecture:</b>
 * <ul>
 * <li>Secrets are encrypted with a tenant-scoped DEK (Data Encryption Key)</li>
 * <li>DEKs are encrypted with the KEK (Key Encryption Key) derived from the
 * master key</li>
 * <li>Both secrets and DEKs are persisted via {@link ISecretPersistence}
 * (MongoDB or PostgreSQL)</li>
 * <li>Secrets are scoped at the <b>tenant level</b> — access control is via
 * configuration authorship</li>
 * </ul>
 * <p>
 * <b>Key rotation:</b> Supports both DEK rotation (per-tenant, re-encrypts all
 * secrets) and KEK rotation (re-encrypts all DEKs with a new master key).
 * <p>
 * <b>Access model:</b> The admin who writes the agent config decides which
 * vault references to include. The {@code allowedAgents} field is stored for
 * visibility/documentation but NOT enforced at resolution time.
 * <p>
 * The KEK (Master Key) is supplied via the {@code EDDI_VAULT_MASTER_KEY}
 * environment variable. If not set, the provider is disabled and all operations
 * throw exceptions.
 *
 * @author ginccc
 * @since 6.0.0
 */
@ApplicationScoped
public class VaultSecretProvider implements ISecretProvider {

    private static final Logger LOGGER = Logger.getLogger(VaultSecretProvider.class);

    private final Optional<String> masterKeyConfig;
    private final ISecretPersistence persistence;
    private final VaultSaltManager saltManager;
    private final MeterRegistry meterRegistry;

    private byte[] kek; // Key Encryption Key derived from master key
    private boolean available = false;

    // ─── Metrics ───
    private Counter resolveCounter;
    private Counter storeCounter;
    private Counter deleteCounter;
    private Counter rotateCounter;
    private Counter errorCounter;
    private Timer resolveTimer;
    private Timer storeTimer;

    @Inject
    public VaultSecretProvider(@ConfigProperty(name = "eddi.vault.master-key") Optional<String> masterKeyConfig, ISecretPersistence persistence,
            VaultSaltManager saltManager, MeterRegistry meterRegistry) {
        this.masterKeyConfig = masterKeyConfig;
        this.persistence = persistence;
        this.saltManager = saltManager;
        this.meterRegistry = meterRegistry;
    }

    @PostConstruct
    void initMetrics() {
        this.resolveCounter = meterRegistry.counter("eddi.vault.resolve.count");
        this.storeCounter = meterRegistry.counter("eddi.vault.store.count");
        this.deleteCounter = meterRegistry.counter("eddi.vault.delete.count");
        this.rotateCounter = meterRegistry.counter("eddi.vault.rotate.count");
        this.errorCounter = meterRegistry.counter("eddi.vault.errors.count");
        this.resolveTimer = meterRegistry.timer("eddi.vault.resolve.duration");
        this.storeTimer = meterRegistry.timer("eddi.vault.store.duration");
    }

    void onStartup(@Observes StartupEvent event) {
        if (masterKeyConfig.isEmpty() || masterKeyConfig.get().isBlank()) {
            VaultStartupBanner.printDisabled();
            return;
        }

        // Initialize per-deployment salt (generates on first boot, loads on subsequent)
        saltManager.initialize();

        this.kek = EnvelopeCrypto.deriveKeyFromString(masterKeyConfig.get(), saltManager.getSalt());
        this.available = true;

        if (saltManager.isUsingLegacySalt()) {
            LOGGER.warn("[VAULT] Using legacy fixed salt for KEK derivation. "
                    + "Run KEK rotation to migrate to a per-deployment random salt.");
        }

        VaultStartupBanner.printEnabled();
    }

    @Override
    public String resolve(SecretReference reference) throws SecretNotFoundException, SecretProviderException {
        ensureAvailable();
        resolveCounter.increment();
        Timer.Sample sample = Timer.start(meterRegistry);

        try {
            var secretOpt = persistence.findSecret(reference.tenantId(), reference.keyName());
            if (secretOpt.isEmpty()) {
                throw new SecretNotFoundException("Secret not found: " + reference.tenantId() + "/" + reference.keyName());
            }

            EncryptedSecret secret = secretOpt.get();

            // Decrypt: KEK → DEK → plaintext
            byte[] dek = getOrCreateDek(reference.tenantId());
            String plaintext = EnvelopeCrypto.decrypt(secret.getEncryptedValue(), secret.getIv(), dek);

            // Update last accessed timestamp (best-effort, fire-and-forget)
            updateLastAccessed(secret);

            return plaintext;
        } catch (SecretNotFoundException e) {
            errorCounter.increment();
            throw e;
        } catch (PersistenceException e) {
            errorCounter.increment();
            throw new SecretProviderException("Persistence failure while resolving " + reference.tenantId() + "/" + reference.keyName(), e);
        } catch (EnvelopeCrypto.CryptoException e) {
            errorCounter.increment();
            throw new SecretProviderException("Decryption failure for " + reference.tenantId() + "/" + reference.keyName(), e);
        } finally {
            sample.stop(resolveTimer);
        }
    }

    /**
     * Update lastAccessedAt in a best-effort manner. Failures are logged but do not
     * propagate — a failed timestamp update should never break secret resolution.
     */
    private void updateLastAccessed(EncryptedSecret secret) {
        try {
            secret.setLastAccessedAt(Instant.now());
            persistence.upsertSecret(secret);
        } catch (PersistenceException e) {
            LOGGER.debugf("Failed to update lastAccessedAt for %s/%s: %s", secret.getTenantId(), secret.getKeyName(), e.getMessage());
        }
    }

    @Override
    public void store(SecretReference reference, String plaintext, String description, List<String> allowedAgents) throws SecretProviderException {
        ensureAvailable();
        storeCounter.increment();
        Timer.Sample sample = Timer.start(meterRegistry);

        try {
            byte[] dek = getOrCreateDek(reference.tenantId());

            // Encrypt the plaintext with the tenant's DEK
            EnvelopeCrypto.EncryptionResult result = EnvelopeCrypto.encrypt(plaintext, dek);
            String checksum = EnvelopeCrypto.sha256Hex(plaintext);

            // Check if this is an update (rotation) or new secret
            var existingOpt = persistence.findSecret(reference.tenantId(), reference.keyName());
            Instant now = Instant.now();

            EncryptedSecret secret = new EncryptedSecret(existingOpt.map(EncryptedSecret::getId).orElse(UUID.randomUUID().toString()),
                    reference.tenantId(), reference.keyName(), result.ciphertext(), result.iv(), reference.tenantId(), checksum, description,
                    allowedAgents != null ? allowedAgents : List.of("*"), existingOpt.map(EncryptedSecret::getCreatedAt).orElse(now), null,
                    existingOpt.isPresent() ? now : null);

            persistence.upsertSecret(secret);
            LOGGER.infof("Secret stored: %s/%s (description: %s)", reference.tenantId(), reference.keyName(),
                    description != null ? description : "none");
        } catch (PersistenceException e) {
            errorCounter.increment();
            throw new SecretProviderException("Persistence failure while storing " + reference.tenantId() + "/" + reference.keyName(), e);
        } catch (EnvelopeCrypto.CryptoException e) {
            errorCounter.increment();
            throw new SecretProviderException("Encryption failure for " + reference.tenantId() + "/" + reference.keyName(), e);
        } finally {
            sample.stop(storeTimer);
        }
    }

    @Override
    public void delete(SecretReference reference) throws SecretNotFoundException, SecretProviderException {
        ensureAvailable();
        deleteCounter.increment();

        try {
            boolean deleted = persistence.deleteSecret(reference.tenantId(), reference.keyName());
            if (!deleted) {
                throw new SecretNotFoundException("Secret not found: " + reference.tenantId() + "/" + reference.keyName());
            }
            LOGGER.infof("Secret deleted: %s/%s", reference.tenantId(), reference.keyName());
        } catch (PersistenceException e) {
            errorCounter.increment();
            throw new SecretProviderException("Persistence failure while deleting " + reference.tenantId() + "/" + reference.keyName(), e);
        }
    }

    @Override
    public SecretMetadata getMetadata(SecretReference reference) throws SecretNotFoundException, SecretProviderException {
        ensureAvailable();
        try {
            var secretOpt = persistence.findSecret(reference.tenantId(), reference.keyName());
            if (secretOpt.isEmpty()) {
                throw new SecretNotFoundException("Secret not found: " + reference.tenantId() + "/" + reference.keyName());
            }
            EncryptedSecret s = secretOpt.get();
            return new SecretMetadata(s.getTenantId(), s.getKeyName(), s.getCreatedAt(), s.getLastAccessedAt(), s.getLastRotatedAt(), s.getChecksum(),
                    s.getDescription(), s.getAllowedAgents());
        } catch (PersistenceException e) {
            errorCounter.increment();
            throw new SecretProviderException("Persistence failure while reading metadata for " + reference.tenantId() + "/" + reference.keyName(),
                    e);
        }
    }

    @Override
    public List<SecretMetadata> listKeys(String tenantId) throws SecretProviderException {
        ensureAvailable();
        try {
            return persistence.listSecretsByTenant(tenantId).stream().map(s -> new SecretMetadata(s.getTenantId(), s.getKeyName(), s.getCreatedAt(),
                    s.getLastAccessedAt(), s.getLastRotatedAt(), s.getChecksum(), s.getDescription(), s.getAllowedAgents())).toList();
        } catch (PersistenceException e) {
            errorCounter.increment();
            throw new SecretProviderException("Persistence failure while listing secrets for tenant " + tenantId, e);
        }
    }

    @Override
    public boolean isAvailable() {
        return available;
    }

    // ─── Key Rotation ───

    /**
     * {@inheritDoc}
     * <p>
     * Generates a new DEK, re-encrypts all secrets for the tenant with the new DEK,
     * and replaces the old DEK. If any re-encryption fails, the operation aborts
     * and an exception is thrown.
     */
    @Override
    public int rotateDek(String tenantId) throws SecretProviderException {
        ensureAvailable();
        rotateCounter.increment();

        try {
            // 1. Verify: decrypt all secrets with old DEK (validates key before mutating
            // anything)
            var dekOpt = persistence.findDek(tenantId);
            if (dekOpt.isEmpty()) {
                throw new SecretProviderException("No DEK found for tenant " + tenantId + " — nothing to rotate");
            }

            byte[] oldDek = EnvelopeCrypto.decryptDek(dekOpt.get().getEncryptedDek(), dekOpt.get().getIv(), kek);
            List<EncryptedSecret> secrets = persistence.listSecretsByTenant(tenantId);

            // 2. Generate new DEK
            byte[] newDek = EnvelopeCrypto.generateDek();

            // 3. Prepare: decrypt all and re-encrypt in memory (no writes yet)
            Instant now = Instant.now();
            List<SimpleEntry<EncryptedSecret, EnvelopeCrypto.EncryptionResult>> reEncrypted = new ArrayList<>();
            for (EncryptedSecret secret : secrets) {
                String plaintext = EnvelopeCrypto.decrypt(secret.getEncryptedValue(), secret.getIv(), oldDek);
                EnvelopeCrypto.EncryptionResult enc = EnvelopeCrypto.encrypt(plaintext, newDek);
                reEncrypted.add(new SimpleEntry<>(secret, enc));
            }

            // 4. Commit: write all re-encrypted secrets, then replace the DEK
            for (var entry : reEncrypted) {
                EncryptedSecret secret = entry.getKey();
                EnvelopeCrypto.EncryptionResult enc = entry.getValue();
                secret.setEncryptedValue(enc.ciphertext());
                secret.setIv(enc.iv());
                secret.setLastRotatedAt(now);
                persistence.upsertSecret(secret);
            }

            EnvelopeCrypto.EncryptionResult newDekEnc = EnvelopeCrypto.encryptDek(newDek, kek);
            EncryptedDek newDekEntity = new EncryptedDek(dekOpt.get().getId(), tenantId, newDekEnc.ciphertext(), newDekEnc.iv(), Instant.now());
            persistence.upsertDek(newDekEntity);

            LOGGER.infof("DEK rotated for tenant '%s': %d secrets re-encrypted", tenantId, secrets.size());
            return secrets.size();
        } catch (PersistenceException | EnvelopeCrypto.CryptoException e) {
            errorCounter.increment();
            throw new SecretProviderException("DEK rotation failed for tenant " + tenantId, e);
        }
    }

    /**
     * Rotate the KEK (Master Key). Re-encrypts all tenant DEKs with a new master
     * key. The actual secret ciphertexts are NOT modified — only the DEK wrappers
     * change.
     * <p>
     * <b>Usage:</b>
     * <ol>
     * <li>Call this method with both the old and new master keys</li>
     * <li>Restart the application with the new master key in the environment</li>
     * </ol>
     *
     * @param oldMasterKey
     *            the current master key (to decrypt existing DEKs)
     * @param newMasterKey
     *            the new master key (to re-encrypt DEKs)
     * @return the number of DEKs re-encrypted
     * @throws SecretProviderException
     *             if rotation fails
     */
    public int rotateKek(String oldMasterKey, String newMasterKey) throws SecretProviderException {
        if (!available) {
            throw new SecretProviderException("Secrets Vault is not available. Cannot rotate KEK.");
        }
        rotateCounter.increment();

        try {
            byte[] oldKek = EnvelopeCrypto.deriveKeyFromString(oldMasterKey, saltManager.getSalt());
            byte[] newKek = EnvelopeCrypto.deriveKeyFromString(newMasterKey, saltManager.getSalt());

            // Phase 1: Verify — decrypt ALL DEKs with old KEK to validate before mutating
            List<EncryptedDek> allDeks = persistence.listAllDeks();
            List<SimpleEntry<EncryptedDek, EnvelopeCrypto.EncryptionResult>> prepared = new ArrayList<>();
            for (EncryptedDek encDek : allDeks) {
                byte[] rawDek = EnvelopeCrypto.decryptDek(encDek.getEncryptedDek(), encDek.getIv(), oldKek);
                EnvelopeCrypto.EncryptionResult reEnc = EnvelopeCrypto.encryptDek(rawDek, newKek);
                prepared.add(new SimpleEntry<>(encDek, reEnc));
            }

            // Phase 2: Commit — write all re-encrypted DEKs
            for (var entry : prepared) {
                EncryptedDek encDek = entry.getKey();
                EnvelopeCrypto.EncryptionResult reEnc = entry.getValue();
                encDek.setEncryptedDek(reEnc.ciphertext());
                encDek.setIv(reEnc.iv());
                persistence.upsertDek(encDek);
            }

            // Update our in-memory KEK to the new one
            this.kek = newKek;

            LOGGER.infof("KEK rotated: %d DEKs re-encrypted", allDeks.size());
            return allDeks.size();
        } catch (PersistenceException | EnvelopeCrypto.CryptoException e) {
            errorCounter.increment();
            throw new SecretProviderException("KEK rotation failed", e);
        }
    }

    // === Private helpers ===

    private byte[] getOrCreateDek(String tenantId) throws SecretProviderException {
        try {
            var dekOpt = persistence.findDek(tenantId);
            if (dekOpt.isPresent()) {
                EncryptedDek encryptedDek = dekOpt.get();
                return EnvelopeCrypto.decryptDek(encryptedDek.getEncryptedDek(), encryptedDek.getIv(), kek);
            }

            // Generate a new DEK for this tenant
            byte[] newDek = EnvelopeCrypto.generateDek();
            EnvelopeCrypto.EncryptionResult encResult = EnvelopeCrypto.encryptDek(newDek, kek);

            EncryptedDek dek = new EncryptedDek(UUID.randomUUID().toString(), tenantId, encResult.ciphertext(), encResult.iv(), Instant.now());

            persistence.upsertDek(dek);
            LOGGER.infof("Generated new DEK for tenant: %s", tenantId);
            return newDek;
        } catch (PersistenceException e) {
            throw new SecretProviderException("Persistence failure while managing DEK for tenant " + tenantId, e);
        }
    }

    private void ensureAvailable() throws SecretProviderException {
        if (!available) {
            throw new SecretProviderException("Secrets Vault is not available. Set EDDI_VAULT_MASTER_KEY environment variable.");
        }
    }
}
