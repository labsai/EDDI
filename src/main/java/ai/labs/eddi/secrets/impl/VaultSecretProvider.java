/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.secrets.impl;

import ai.labs.eddi.secrets.ISecretProvider;
import ai.labs.eddi.secrets.SealedDataRotationParticipant;
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
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.interceptor.Interceptor;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.*;
import java.util.AbstractMap.SimpleEntry;
import java.util.function.UnaryOperator;

import java.security.SecureRandom;
import static ai.labs.eddi.utils.LogSanitizer.sanitize;

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
 * <li>Secrets are stored at the <b>tenant level</b>; which agents may use one
 * is governed by {@code allowedAgents} (see below)</li>
 * </ul>
 * <p>
 * <b>Key rotation:</b> Supports both DEK rotation and KEK rotation. DEK
 * rotation is per-tenant and <b>additive</b>: it installs a new DEK generation
 * and then sweeps existing rows onto it, rather than replacing a key underneath
 * data that names it. Every ciphertext records the generation that sealed it,
 * so a sweep that stops halfway leaves every row readable and the rotation safe
 * to re-run. KEK rotation re-wraps every generation of every tenant with a new
 * master key and does not touch any ciphertext.
 * <p>
 * <b>Access model:</b> {@code allowedAgents} is not consulted here — this class
 * resolves secrets and does not police who asked. It is checked one level up,
 * when an agent is deployed, by {@link ai.labs.eddi.secrets.VaultGrantGate},
 * which blocks or merely logs according to
 * {@code eddi.vault.grant-enforcement}. "Not enforced at resolution time" is a
 * statement about this class, not about the field.
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

    /**
     * Everything else that stores data sealed with a tenant DEK. Discovered through
     * CDI so this package stays a leaf - see {@link SealedDataRotationParticipant}.
     */
    private final Instance<SealedDataRotationParticipant> rotationParticipants;

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
            VaultSaltManager saltManager, MeterRegistry meterRegistry, Instance<SealedDataRotationParticipant> rotationParticipants) {
        this.masterKeyConfig = masterKeyConfig;
        this.persistence = persistence;
        this.saltManager = saltManager;
        this.meterRegistry = meterRegistry;
        this.rotationParticipants = rotationParticipants;
    }

    /**
     * Test seam: no sealed-data participants.
     * <p>
     * Rotation then re-seals only the secret collection, which is exactly what the
     * pre-participant behaviour was — fine for a test that is not about rotation,
     * and never what production wires up.
     */
    VaultSecretProvider(Optional<String> masterKeyConfig, ISecretPersistence persistence, VaultSaltManager saltManager,
            MeterRegistry meterRegistry) {
        this(masterKeyConfig, persistence, saltManager, meterRegistry, null);
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

    /**
     * Turns the vault on.
     * <p>
     * The priority orders this <em>among {@link StartupEvent} observers only</em>:
     * {@link Interceptor.Priority#APPLICATION} is below the CDI default of
     * {@code APPLICATION + 500}, so every observer that does not name a lower
     * priority of its own sees {@link #isAvailable()} already settled. It orders
     * nothing else. {@code @PostConstruct} callbacks are outside that sequence
     * entirely — {@link ai.labs.eddi.secrets.SecretResolver} reads
     * {@code isAvailable()} from one, which fires whenever that bean is first
     * instantiated and may well be before this observer has run. Callers on that
     * side must tolerate a not-yet-available vault rather than rely on ordering.
     */
    void onStartup(@Observes
    @Priority(Interceptor.Priority.APPLICATION) StartupEvent event) {
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
                throw new SecretNotFoundException("Secret not found: " + describe(reference));
            }

            EncryptedSecret secret = secretOpt.get();

            // Decrypt: KEK → the DEK generation this row names → plaintext. Not the
            // newest generation: a row the last rotation's sweep has not reached yet is
            // still sealed with an older one, and that one still exists.
            byte[] dek = dekFor(reference.tenantId(), secret.getDekId());
            String plaintext = EnvelopeCrypto.decrypt(secret.getEncryptedValue(), secret.getIv(), dek);

            // Update last accessed timestamp (best-effort, fire-and-forget)
            updateLastAccessed(secret);

            return plaintext;
        } catch (SecretNotFoundException e) {
            errorCounter.increment();
            throw e;
        } catch (PersistenceException e) {
            errorCounter.increment();
            throw new SecretProviderException("Persistence failure while resolving " + describe(reference), e);
        } catch (EnvelopeCrypto.CryptoException e) {
            errorCounter.increment();
            throw new SecretProviderException("Decryption failure for " + describe(reference), e);
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
            LOGGER.debugf("Failed to update lastAccessedAt for %s/%s: %s", sanitize(secret.getTenantId()), sanitize(secret.getKeyName()),
                    e.getMessage());
        }
    }

    @Override
    public void store(SecretReference reference, String plaintext, String description, List<String> allowedAgents) throws SecretProviderException {
        ensureAvailable();
        storeCounter.increment();
        Timer.Sample sample = Timer.start(meterRegistry);

        try {
            ActiveDek dek = activeDek(reference.tenantId());

            // Encrypt the plaintext with the tenant's DEK
            EnvelopeCrypto.EncryptionResult result = EnvelopeCrypto.encrypt(plaintext, dek.key());
            String checksum = EnvelopeCrypto.sha256Hex(plaintext);

            // Check if this is an update (rotation) or new secret
            var existingOpt = persistence.findSecret(reference.tenantId(), reference.keyName());
            Instant now = Instant.now();

            EncryptedSecret secret = new EncryptedSecret(existingOpt.map(EncryptedSecret::getId).orElse(UUID.randomUUID().toString()),
                    reference.tenantId(), reference.keyName(), result.ciphertext(), result.iv(), dek.dekId(), checksum, description,
                    allowedAgents != null ? allowedAgents : List.of("*"), existingOpt.map(EncryptedSecret::getCreatedAt).orElse(now), null,
                    existingOpt.isPresent() ? now : null);

            persistence.upsertSecret(secret);
            LOGGER.infof("Secret stored: %s (description: %s)", describe(reference), description != null ? sanitize(description) : "none");
        } catch (PersistenceException e) {
            errorCounter.increment();
            throw new SecretProviderException("Persistence failure while storing " + describe(reference), e);
        } catch (EnvelopeCrypto.CryptoException e) {
            errorCounter.increment();
            throw new SecretProviderException("Encryption failure for " + describe(reference), e);
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
                throw new SecretNotFoundException("Secret not found: " + describe(reference));
            }
            LOGGER.infof("Secret deleted: %s", describe(reference));
        } catch (PersistenceException e) {
            errorCounter.increment();
            throw new SecretProviderException("Persistence failure while deleting " + describe(reference), e);
        }
    }

    @Override
    public SecretMetadata getMetadata(SecretReference reference) throws SecretNotFoundException, SecretProviderException {
        ensureAvailable();
        try {
            var secretOpt = persistence.findSecret(reference.tenantId(), reference.keyName());
            if (secretOpt.isEmpty()) {
                throw new SecretNotFoundException("Secret not found: " + describe(reference));
            }
            EncryptedSecret s = secretOpt.get();
            return new SecretMetadata(s.getTenantId(), s.getKeyName(), s.getCreatedAt(), s.getLastAccessedAt(), s.getLastRotatedAt(), s.getChecksum(),
                    s.getDescription(), s.getAllowedAgents());
        } catch (PersistenceException e) {
            errorCounter.increment();
            throw new SecretProviderException("Persistence failure while reading metadata for " + describe(reference),
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
            throw new SecretProviderException("Persistence failure while listing secrets for tenant " + sanitize(tenantId), e);
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
     * Three phases, and only the middle one is irreversible:
     * <ol>
     * <li><b>Verify</b> — every existing generation is opened with the current KEK,
     * so a wrong master key is discovered before anything is written.</li>
     * <li><b>Commit</b> — the next generation is <em>inserted</em>. One statement,
     * guarded by a unique key on (tenant, generation), so two racing rotations
     * produce one winner and one clean refusal. From here on new values seal with
     * the new key while every existing row still names a generation that exists and
     * decrypts.</li>
     * <li><b>Sweep</b> — rows are moved onto the new generation one at a time, each
     * write guarded on the state the row was read in. A row the sweep cannot move
     * is reported, not lost: it keeps working, and re-running the rotation picks it
     * up.</li>
     * </ol>
     * Old generations are never deleted here. Deleting one is what would make a
     * partially swept tenant unreadable, which is the failure this design exists to
     * remove.
     */
    @Override
    public int rotateDek(String tenantId) throws SecretProviderException {
        ensureAvailable();
        rotateCounter.increment();

        int nextGeneration;
        byte[] newDek;
        try {
            // 1. Verify. Every generation, not just the newest: the sweep below has to
            // open older ones, and finding out mid-sweep that the KEK cannot is a
            // discovery that belongs before the commit point.
            List<EncryptedDek> generations = persistence.listDeks(tenantId);
            if (generations.isEmpty()) {
                throw new SecretProviderException("No DEK found for tenant '" + sanitize(tenantId) + "' — nothing to rotate");
            }
            int highest = 0;
            for (EncryptedDek generation : generations) {
                EnvelopeCrypto.decryptDek(generation.getEncryptedDek(), generation.getIv(), kek);
                highest = Math.max(highest, generation.getGeneration());
            }

            // 2. Commit. The single atomic step in the whole operation.
            nextGeneration = highest + 1;
            newDek = EnvelopeCrypto.generateDek();
            EnvelopeCrypto.EncryptionResult enc = EnvelopeCrypto.encryptDek(newDek, kek);
            EncryptedDek entity = new EncryptedDek(UUID.randomUUID().toString(), tenantId, nextGeneration, enc.ciphertext(), enc.iv(),
                    Instant.now());
            if (!persistence.insertDek(entity)) {
                throw new SecretProviderException("Generation " + nextGeneration + " already exists for tenant '" + sanitize(tenantId)
                        + "'. Another rotation installed it first; nothing was changed by this one.");
            }
        } catch (PersistenceException | EnvelopeCrypto.CryptoException e) {
            errorCounter.increment();
            throw new SecretProviderException("DEK rotation failed for tenant '" + sanitize(tenantId) + "'", e);
        }

        // 3. Sweep. Past the commit point, so a failure here is incomplete rather
        // than destructive and is reported as such.
        String activeDekId = EncryptedDek.dekId(tenantId, nextGeneration);
        Instant now = Instant.now();
        int migrated = 0;
        int outstanding = 0;

        List<EncryptedSecret> secrets;
        try {
            secrets = persistence.listSecretsByTenant(tenantId);
        } catch (PersistenceException e) {
            errorCounter.increment();
            throw new SecretProviderException("DEK rotation for tenant '" + sanitize(tenantId) + "': generation " + nextGeneration
                    + " is now the active key, but the secrets could not be listed to migrate them. Nothing is lost and the operation is safe to"
                    + " re-run.", e);
        }
        for (EncryptedSecret secret : secrets) {
            // Per row, so one secret nobody can open does not strand the rest of the
            // tenant on an older generation for every future rotation as well.
            try {
                if (migrateSecret(tenantId, secret, activeDekId, newDek, now)) {
                    migrated++;
                } else {
                    outstanding++;
                }
            } catch (SecretProviderException | PersistenceException | EnvelopeCrypto.CryptoException e) {
                outstanding++;
                LOGGER.errorf(e, "DEK rotation for tenant '%s': secret '%s' could not be moved to generation %d", sanitize(tenantId),
                        sanitize(secret.getKeyName()), nextGeneration);
            }
        }

        outstanding += resealParticipants(tenantId, activeDekId, newDek);

        if (outstanding > 0) {
            errorCounter.increment();
            throw new SecretProviderException("DEK rotation for tenant '" + sanitize(tenantId) + "': generation " + nextGeneration
                    + " is now the active key and every new value is sealed with it, but at least " + outstanding
                    + " sealed row(s) still name an older generation. Nothing is lost — those rows still decrypt with the generation they name,"
                    + " which has not been deleted — and the operation is safe to re-run to finish the migration.");
        }

        LOGGER.infof("DEK rotated for tenant '%s': generation %d is active, %d secret(s) migrated", sanitize(tenantId), nextGeneration, migrated);
        return migrated;
    }

    /**
     * Moves one secret onto the active generation, tolerating a concurrent writer.
     * <p>
     * The write is guarded on the dekId the row was read with, so a {@code store}
     * that landed in between is never overwritten with a re-seal of the value it
     * replaced. On a lost guard the row is re-read once: if it now names the active
     * generation somebody else already did the work, and otherwise one retry is
     * enough — a row losing twice is a row being written continuously, and leaving
     * it costs nothing because the generation it names still opens it.
     *
     * @return whether the row is on the active generation when this returns
     */
    private boolean migrateSecret(String tenantId, EncryptedSecret secret, String activeDekId, byte[] activeDek, Instant now)
            throws SecretProviderException {
        EncryptedSecret current = secret;
        for (int attempt = 0; attempt < 2; attempt++) {
            if (current == null) {
                // Deleted mid-sweep. There is nothing left to migrate, which is not a
                // failure.
                return true;
            }
            String rowDekId = current.getDekId();
            if (activeDekId.equals(rowDekId)) {
                return true;
            }
            String plaintext = EnvelopeCrypto.decrypt(current.getEncryptedValue(), current.getIv(), dekFor(tenantId, rowDekId));
            EnvelopeCrypto.EncryptionResult enc = EnvelopeCrypto.encrypt(plaintext, activeDek);
            current.setEncryptedValue(enc.ciphertext());
            current.setIv(enc.iv());
            current.setDekId(activeDekId);
            current.setLastRotatedAt(now);
            if (persistence.updateSecretSealing(current, rowDekId)) {
                return true;
            }
            current = persistence.findSecret(tenantId, secret.getKeyName()).orElse(null);
        }
        return false;
    }

    /**
     * Asks every {@link SealedDataRotationParticipant} to move its rows onto the
     * new generation.
     * <p>
     * Runs AFTER the new generation is committed, which is what makes it safe to
     * fail: every older generation still exists, so a row this sweep does not reach
     * still names a key that opens it. The re-sealer decrypts with the generation
     * each value names rather than with one assumed key, so a participant holding a
     * mix of generations — the normal state after an interrupted rotation — is
     * migrated correctly.
     *
     * @return how many rows are known to still be on an older generation, counting
     *         a participant that failed outright as at least one
     */
    private int resealParticipants(String tenantId, String activeDekId, byte[] activeDek) {
        if (rotationParticipants == null || rotationParticipants.isUnsatisfied()) {
            return 0;
        }
        UnaryOperator<SealedValue> resealer = sealed -> {
            if (sealed == null || sealed.ciphertext() == null) {
                return sealed;
            }
            try {
                String plaintext = EnvelopeCrypto.decrypt(sealed.ciphertext(), sealed.iv(), dekFor(tenantId, sealed.dekId()));
                EnvelopeCrypto.EncryptionResult enc = EnvelopeCrypto.encrypt(plaintext, activeDek);
                return new SealedValue(enc.ciphertext(), enc.iv(), activeDekId);
            } catch (SecretProviderException | EnvelopeCrypto.CryptoException e) {
                // Never quotes the ciphertext or the participant's row: this runs over
                // refresh tokens.
                throw new IllegalStateException("Failed to re-seal a value during DEK rotation for tenant " + sanitize(tenantId), e);
            }
        };
        int outstanding = 0;
        for (SealedDataRotationParticipant participant : rotationParticipants) {
            try {
                int left = participant.resealAll(tenantId, activeDekId, resealer);
                outstanding += left;
                LOGGER.infof("DEK rotation for tenant '%s': %d row(s) of %s still on an older generation", sanitize(tenantId), left,
                        participant.sealedDataDescription());
            } catch (RuntimeException e) {
                // Participants signal a re-seal failure with an unchecked exception, and
                // one that gave up mid-sweep cannot say how much it left behind. Counted
                // as one so the caller reports "at least N" honestly rather than
                // reporting success.
                outstanding++;
                LOGGER.errorf(e, "DEK rotation for tenant '%s': %s could not be fully migrated", sanitize(tenantId),
                        participant.sealedDataDescription());
            }
        }
        return outstanding;
    }

    /**
     * Rotate the KEK (Master Key). Re-encrypts all tenant DEKs with a new master
     * key. The actual secret ciphertexts are NOT modified — only the DEK wrappers
     * change.
     * <p>
     * Every generation is re-wrapped, not just the newest. A tenant part-way
     * through a DEK sweep still has rows depending on an older generation, and
     * leaving one behind on the old KEK is exactly the orphaned-key failure DEK
     * generations exist to prevent.
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
            // 1. Derive old KEK with current salt (legacy or random)
            byte[] oldKek = EnvelopeCrypto.deriveKeyFromString(oldMasterKey, saltManager.getSalt());

            // 2. Determine new salt — migrate from legacy if needed
            byte[] newSalt;
            boolean migratingFromLegacy = saltManager.isUsingLegacySalt();
            if (migratingFromLegacy) {
                newSalt = new byte[16];
                new SecureRandom().nextBytes(newSalt);
                LOGGER.info("[VAULT] KEK rotation will also migrate from legacy salt to per-deployment random salt.");
            } else {
                newSalt = saltManager.getSalt();
            }
            byte[] newKek = EnvelopeCrypto.deriveKeyFromString(newMasterKey, newSalt);

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

            // Phase 3: Persist new salt AFTER DEKs are re-encrypted.
            // If this fails, DEKs are on newKek but salt in DB is still legacy.
            // The operator can retry — the legacy salt is a known constant.
            if (migratingFromLegacy) {
                saltManager.migrateSalt(newSalt);
            }

            // Update our in-memory KEK to the new one
            this.kek = newKek;

            LOGGER.infof("KEK rotated: %d DEKs re-encrypted%s", allDeks.size(),
                    migratingFromLegacy ? " + salt migrated to per-deployment random" : "");
            return allDeks.size();
        } catch (PersistenceException | EnvelopeCrypto.CryptoException e) {
            errorCounter.increment();
            throw new SecretProviderException("KEK rotation failed", e);
        }
    }

    @Override
    public int resetTenant(String tenantId) throws SecretProviderException {
        ensureAvailable();

        try {
            // Delete all secrets first, then the DEK
            var secrets = persistence.listSecretsByTenant(tenantId);
            int deletedCount = 0;

            for (var secret : secrets) {
                if (persistence.deleteSecret(tenantId, secret.getKeyName())) {
                    deletedCount++;
                }
            }
            persistence.deleteDek(tenantId);

            LOGGER.infof("[VAULT] Tenant '%s' reset: %d secret(s) deleted, DEK removed.", sanitize(tenantId), deletedCount);
            return deletedCount;
        } catch (PersistenceException e) {
            throw new SecretProviderException("Failed to reset vault for tenant " + sanitize(tenantId), e);
        }
    }

    @Override
    public SealedValue seal(String tenantId, String plaintext) throws SecretProviderException {
        ensureAvailable();
        if (plaintext == null) {
            return null;
        }
        try {
            ActiveDek dek = activeDek(tenantId);
            EnvelopeCrypto.EncryptionResult result = EnvelopeCrypto.encrypt(plaintext, dek.key());
            // The caller persists the dekId next to the ciphertext; without it the value
            // would only ever be openable by whatever generation happened to be newest
            // at read time.
            return new SealedValue(result.ciphertext(), result.iv(), dek.dekId());
        } catch (EnvelopeCrypto.CryptoException e) {
            errorCounter.increment();
            throw new SecretProviderException("Encryption failure while sealing data for tenant " + sanitize(tenantId), e);
        }
    }

    @Override
    public String unseal(String tenantId, SealedValue sealed) throws SecretProviderException {
        ensureAvailable();
        if (sealed == null || sealed.ciphertext() == null) {
            return null;
        }
        try {
            return EnvelopeCrypto.decrypt(sealed.ciphertext(), sealed.iv(), dekFor(tenantId, sealed.dekId()));
        } catch (EnvelopeCrypto.CryptoException e) {
            errorCounter.increment();
            // The message deliberately says nothing about the ciphertext. A failed
            // authentication tag means either a changed master key or tampering, and
            // both are answered the same way: refuse and say which tenant.
            throw new SecretProviderException("Decryption failure while unsealing data for tenant " + sanitize(tenantId), e);
        }
    }

    // === Private helpers ===

    /**
     * A tenant/key pair as it may appear in a message.
     * <p>
     * Both halves are caller-controlled and every message built here is eventually
     * logged by somebody, so a newline in either would forge log records (CWE-117).
     * Going through one helper is what keeps that true of the next message somebody
     * adds.
     */
    private static String describe(SecretReference reference) {
        return sanitize(reference.tenantId()) + "/" + sanitize(reference.keyName());
    }

    /**
     * A usable DEK together with the name ciphertext must record for it.
     */
    private record ActiveDek(byte[] key, String dekId) {
    }

    /**
     * The generation new values are sealed with — the newest one — creating it on
     * first use.
     * <p>
     * Read from the store on every call, deliberately un-cached. That is the
     * behaviour this class already had, and a cache here would need invalidating
     * the instant another replica installs a generation.
     */
    private ActiveDek activeDek(String tenantId) throws SecretProviderException {
        try {
            var dekOpt = persistence.findDek(tenantId);
            if (dekOpt.isPresent()) {
                EncryptedDek encryptedDek = dekOpt.get();
                try {
                    return new ActiveDek(EnvelopeCrypto.decryptDek(encryptedDek.getEncryptedDek(), encryptedDek.getIv(), kek),
                            encryptedDek.dekId());
                } catch (EnvelopeCrypto.CryptoException e) {
                    return new ActiveDek(handleDekDecryptionFailure(tenantId, e), encryptedDek.dekId());
                }
            }

            return new ActiveDek(generateAndPersistDek(tenantId), EncryptedDek.dekId(tenantId, EncryptedDek.FIRST_GENERATION));
        } catch (PersistenceException e) {
            throw new SecretProviderException("Persistence failure while managing DEK for tenant '" + sanitize(tenantId) + "'", e);
        }
    }

    /**
     * The key that a stored dekId names.
     * <p>
     * Never creates one: a missing generation means ciphertext exists that nothing
     * can open, and minting a fresh key would answer that with a decryption failure
     * one layer further down instead of saying what actually happened.
     */
    private byte[] dekFor(String tenantId, String dekId) throws SecretProviderException {
        int generation = EncryptedDek.generationOf(tenantId, dekId);
        try {
            var dekOpt = persistence.findDek(tenantId, generation);
            if (dekOpt.isEmpty()) {
                throw new SecretProviderException("DEK generation " + generation + " for tenant '" + sanitize(tenantId)
                        + "' is missing, but stored data is still sealed with it. Old generations must never be deleted while any row names them.");
            }
            EncryptedDek encryptedDek = dekOpt.get();
            try {
                return EnvelopeCrypto.decryptDek(encryptedDek.getEncryptedDek(), encryptedDek.getIv(), kek);
            } catch (EnvelopeCrypto.CryptoException e) {
                return handleDekDecryptionFailure(tenantId, e);
            }
        } catch (PersistenceException e) {
            throw new SecretProviderException(
                    "Persistence failure while reading DEK generation " + generation + " for tenant '" + sanitize(tenantId) + "'", e);
        }
    }

    /**
     * Handles the case where an existing DEK cannot be decrypted — typically
     * because EDDI_VAULT_MASTER_KEY changed since the DEK was created.
     * <p>
     * Never auto-recovers. Always fails with a clear, actionable error so the user
     * can choose the appropriate recovery path.
     */
    private byte[] handleDekDecryptionFailure(String tenantId, EnvelopeCrypto.CryptoException cause) throws SecretProviderException {
        int secretCount;
        try {
            secretCount = persistence.listSecretsByTenant(tenantId).size();
        } catch (PersistenceException e) {
            secretCount = -1; // unknown
        }

        String secretInfo = secretCount == 0
                ? "No secrets are stored for this tenant, so no data would be lost by resetting."
                : secretCount > 0
                        ? secretCount + " secret(s) are stored for this tenant and would be permanently lost if you reset."
                        : "Unable to determine how many secrets are stored for this tenant.";

        String safeTenantId = sanitize(tenantId);
        throw new SecretProviderException(
                "Cannot decrypt the Data Encryption Key (DEK) for tenant '" + safeTenantId + "'. "
                        + "This means the EDDI_VAULT_MASTER_KEY has changed since the DEK was created. "
                        + secretInfo + " "
                        + "Recovery options: "
                        + "(1) Set EDDI_VAULT_MASTER_KEY back to the original value and restart. "
                        + "(2) If you have both old and new keys, use POST /secretstore/secrets/admin/rotate-kek "
                        + "to migrate all encrypted data to the new key. "
                        + "(3) To start fresh (deletes all secrets for this tenant), use "
                        + "POST /secretstore/secrets/" + safeTenantId + "/reset to clear the vault for this tenant.",
                cause);
    }

    private byte[] generateAndPersistDek(String tenantId) {
        byte[] newDek = EnvelopeCrypto.generateDek();
        EnvelopeCrypto.EncryptionResult encResult = EnvelopeCrypto.encryptDek(newDek, kek);

        EncryptedDek dek = new EncryptedDek(UUID.randomUUID().toString(), tenantId, EncryptedDek.FIRST_GENERATION, encResult.ciphertext(),
                encResult.iv(), Instant.now());

        persistence.upsertDek(dek);
        LOGGER.infof("Generated new DEK for tenant: %s", sanitize(tenantId));
        return newDek;
    }

    private void ensureAvailable() throws SecretProviderException {
        if (!available) {
            throw new SecretProviderException("Secrets Vault is not available. Set EDDI_VAULT_MASTER_KEY environment variable.");
        }
    }
}
