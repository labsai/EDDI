package ai.labs.eddi.secrets.impl;

import ai.labs.eddi.secrets.ISecretProvider;
import ai.labs.eddi.secrets.VaultStartupBanner;
import ai.labs.eddi.secrets.crypto.EnvelopeCrypto;
import ai.labs.eddi.secrets.model.*;
import ai.labs.eddi.secrets.persistence.ISecretPersistence;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.*;

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
 * <b>Access model:</b> The admin who writes the agent config decides which
 * vault references to include. The {@code allowedAgents} field is stored for
 * visibility/documentation but NOT enforced at resolution time. See the
 * implementation plan for the full rationale.
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

    private byte[] kek; // Key Encryption Key derived from master key
    private boolean available = false;

    @Inject
    public VaultSecretProvider(@ConfigProperty(name = "eddi.vault.master-key") Optional<String> masterKeyConfig, ISecretPersistence persistence) {
        this.masterKeyConfig = masterKeyConfig;
        this.persistence = persistence;
    }

    void onStartup(@Observes StartupEvent event) {
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

        var secretOpt = persistence.findSecret(reference.tenantId(), reference.keyName());
        if (secretOpt.isEmpty()) {
            throw new SecretNotFoundException("Secret not found: " + reference.tenantId() + "/" + reference.keyName());
        }

        EncryptedSecret secret = secretOpt.get();

        // Decrypt: KEK → DEK → plaintext
        byte[] dek = getOrCreateDek(reference.tenantId());
        String plaintext = EnvelopeCrypto.decrypt(secret.getEncryptedValue(), secret.getIv(), dek);

        // Update last accessed timestamp
        secret.setLastAccessedAt(Instant.now());
        persistence.upsertSecret(secret);

        return plaintext;
    }

    @Override
    public void store(SecretReference reference, String plaintext, String description, List<String> allowedAgents) throws SecretProviderException {
        ensureAvailable();
        byte[] dek = getOrCreateDek(reference.tenantId());

        // Encrypt the plaintext with the tenant's DEK
        EnvelopeCrypto.EncryptionResult result = EnvelopeCrypto.encrypt(plaintext, dek);
        String checksum = EnvelopeCrypto.sha256Hex(plaintext);

        // Check if this is an update (rotation) or new secret
        var existingOpt = persistence.findSecret(reference.tenantId(), reference.keyName());
        Instant now = Instant.now();

        EncryptedSecret secret = new EncryptedSecret(existingOpt.map(EncryptedSecret::getId).orElse(UUID.randomUUID().toString()),
                reference.tenantId(), reference.keyName(), result.ciphertext(), result.iv(), reference.tenantId(), // dekId is tenantId
                checksum, description, allowedAgents != null ? allowedAgents : List.of("*"),
                existingOpt.map(EncryptedSecret::getCreatedAt).orElse(now), null, existingOpt.isPresent() ? now : null); // lastRotatedAt is set on
                                                                                                                         // update

        persistence.upsertSecret(secret);
        LOGGER.infof("Secret stored: %s/%s (description: %s)", reference.tenantId(), reference.keyName(), description != null ? description : "none");
    }

    @Override
    public void delete(SecretReference reference) throws SecretNotFoundException, SecretProviderException {
        ensureAvailable();
        boolean deleted = persistence.deleteSecret(reference.tenantId(), reference.keyName());
        if (!deleted) {
            throw new SecretNotFoundException("Secret not found: " + reference.tenantId() + "/" + reference.keyName());
        }
        LOGGER.infof("Secret deleted: %s/%s", reference.tenantId(), reference.keyName());
    }

    @Override
    public SecretMetadata getMetadata(SecretReference reference) throws SecretNotFoundException, SecretProviderException {
        ensureAvailable();
        var secretOpt = persistence.findSecret(reference.tenantId(), reference.keyName());
        if (secretOpt.isEmpty()) {
            throw new SecretNotFoundException("Secret not found: " + reference.tenantId() + "/" + reference.keyName());
        }
        EncryptedSecret s = secretOpt.get();
        return new SecretMetadata(s.getTenantId(), s.getKeyName(), s.getCreatedAt(), s.getLastAccessedAt(), s.getLastRotatedAt(), s.getChecksum(),
                s.getDescription(), s.getAllowedAgents());
    }

    @Override
    public List<SecretMetadata> listKeys(String tenantId) throws SecretProviderException {
        ensureAvailable();
        return persistence.listSecretsByTenant(tenantId).stream().map(s -> new SecretMetadata(s.getTenantId(), s.getKeyName(), s.getCreatedAt(),
                s.getLastAccessedAt(), s.getLastRotatedAt(), s.getChecksum(), s.getDescription(), s.getAllowedAgents())).toList();
    }

    @Override
    public boolean isAvailable() {
        return available;
    }

    // === Private helpers ===

    private byte[] getOrCreateDek(String tenantId) throws SecretProviderException {
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
    }

    private void ensureAvailable() throws SecretProviderException {
        if (!available) {
            throw new SecretProviderException("Secrets Vault is not available. Set EDDI_VAULT_MASTER_KEY environment variable.");
        }
    }
}
