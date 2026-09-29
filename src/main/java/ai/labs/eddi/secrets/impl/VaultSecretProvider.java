/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.secrets.impl;

import ai.labs.eddi.secrets.ISecretProvider;
import ai.labs.eddi.secrets.SealedDataRotationParticipant;
import ai.labs.eddi.secrets.VaultStartupBanner;
import ai.labs.eddi.secrets.crypto.EnvelopeCrypto;
import ai.labs.eddi.secrets.crypto.VaultChecksum;
import ai.labs.eddi.secrets.crypto.VaultMasterKeyStrength;
import ai.labs.eddi.secrets.crypto.VaultSaltManager;
import io.quarkus.runtime.LaunchMode;
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

    /**
     * Deployment-wide HMAC key for keyed secret checksums. Random, generated once
     * and persisted <em>KEK-wrapped</em> (and re-wrapped on KEK rotation) so it
     * survives rotation (see {@link #checksumKey()}); loaded lazily.
     * Package-private for tests.
     */
    volatile byte[] checksumKey;

    /**
     * Production opt-out for the master-key strength gate, mirroring
     * {@code eddi.security.allow-unauthenticated}. When {@code true}, a weak master
     * key downgrades the production boot failure to a WARN so a brownfield
     * deployment can boot, rotate to a strong key, and then remove the flag —
     * rather than being wedged (a weak-but-functional key cannot be changed without
     * a booted vault to run {@code rotate-kek}). Field-injected so the test seam
     * constructor need not carry it. Package-private for tests.
     */
    @ConfigProperty(name = "eddi.vault.allow-weak-master-key", defaultValue = "false")
    boolean allowWeakMasterKey;

    // ─── Metrics ───
    private Counter resolveCounter;
    private Counter storeCounter;
    private Counter deleteCounter;
    private Counter rotateCounter;
    private Counter grantUpdateCounter;
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
        // Counted separately from stores, not folded into them: a spike in grant
        // widening is a different operational signal from a spike in key rotation,
        // and folding the two would hide it.
        this.grantUpdateCounter = meterRegistry.counter("eddi.vault.grant.update.count");
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

        // Refuse to protect real secrets with a weak or publicly-known master key.
        // Fails startup in production, warns (and continues) in development/test —
        // the same production-only enforcement AuthStartupGuard applies to OIDC. The
        // eddi.vault.allow-weak-master-key opt-out downgrades the production failure to
        // a WARN so a brownfield deployment on a weak key can boot, rotate to a strong
        // key, and remove the flag — instead of being wedged (the key cannot be changed
        // without a booted vault).
        VaultMasterKeyStrength.weakness(masterKeyConfig.get()).ifPresent(reason -> {
            boolean prod = getLaunchMode() == LaunchMode.NORMAL;
            if (prod && !allowWeakMasterKey) {
                throw new IllegalStateException("[VAULT] Refusing to start: " + reason + ". "
                        + "Set EDDI_VAULT_MASTER_KEY to a strong, unique passphrase (at least " + VaultMasterKeyStrength.MIN_LENGTH
                        + " characters); the installer can generate one. If you must boot on the current key to migrate off it, set "
                        + "eddi.vault.allow-weak-master-key=true, then rotate the key (POST /secretstore/secrets/admin/rotate-kek) and "
                        + "remove the flag.");
            }
            if (prod) {
                LOGGER.warnf("[VAULT] %s. Booting anyway because eddi.vault.allow-weak-master-key=true — rotate to a strong key via "
                        + "POST /secretstore/secrets/admin/rotate-kek and remove the flag.", reason);
            } else {
                LOGGER.warnf("[VAULT] %s. This is tolerated in %s mode but would FAIL startup in production. "
                        + "Set EDDI_VAULT_MASTER_KEY to a strong, unique passphrase.", reason, getLaunchMode().name().toLowerCase());
            }
        });

        // Initialize per-deployment salt (generates on first boot, loads on subsequent)
        saltManager.initialize();

        this.kek = EnvelopeCrypto.deriveKeyFromString(masterKeyConfig.get(), saltManager.getSalt());
        this.available = true;
        // The keyed-checksum key is loaded lazily (see checksumKey()), not derived from
        // the KEK here: it must survive KEK rotation, so it is a random deployment key
        // persisted sealed rather than a function of the rotating master key.

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
            String plaintext = decryptSecretWithFallback(secret, dek);

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
            // A single-field write, never upsertSecret(secret): re-upserting the row
            // read at the start of resolve() wrote back every field as it was then, so a
            // grant edit (or rotation) landing in between was silently reverted.
            persistence.touchLastAccessed(secret.getTenantId(), secret.getKeyName(), Instant.now());
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
            // Check if this is an update (rotation) or new secret
            var existingOpt = persistence.findSecret(reference.tenantId(), reference.keyName());
            Instant now = Instant.now();

            EncryptedSecret secret = seal(reference, plaintext, description, allowedAgents, existingOpt.map(EncryptedSecret::getId).orElse(null),
                    existingOpt.map(EncryptedSecret::getCreatedAt).orElse(now), existingOpt.isPresent() ? now : null);

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
    public boolean storeIfAbsent(SecretReference reference, String plaintext, String description, List<String> allowedAgents)
            throws SecretProviderException {
        ensureAvailable();
        storeCounter.increment();
        Timer.Sample sample = Timer.start(meterRegistry);

        try {
            // No findSecret first: the existence check IS the insert. A read here would
            // reopen the window this method exists to close.
            EncryptedSecret secret = seal(reference, plaintext, description, allowedAgents, null, Instant.now(), null);

            boolean created = persistence.insertSecretIfAbsent(secret);
            if (created) {
                LOGGER.infof("Secret created: %s (description: %s)", describe(reference), description != null ? sanitize(description) : "none");
            } else {
                LOGGER.debugf("Secret already exists, left untouched: %s", describe(reference));
            }
            return created;
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

    /**
     * Seals {@code plaintext} under the tenant's active DEK into the row both write
     * paths persist.
     */
    private EncryptedSecret seal(SecretReference reference, String plaintext, String description, List<String> allowedAgents, String existingId,
                                 Instant createdAt, Instant lastRotatedAt)
            throws SecretProviderException, EnvelopeCrypto.CryptoException {
        ActiveDek dek = activeDek(reference.tenantId());

        // Encrypt the plaintext with the tenant's DEK, binding the row identity
        // (tenant|key|dekId) as GCM AAD so the ciphertext cannot be swapped onto a
        // different key by someone with DB write access.
        EnvelopeCrypto.EncryptionResult result = EnvelopeCrypto.encrypt(plaintext, dek.key(),
                secretAad(reference.tenantId(), reference.keyName(), dek.dekId()));
        // Keyed, tenant-bound checksum — never a plain SHA-256 an attacker with DB
        // read access could brute-force offline or use to link equal values across
        // rows/tenants. Legacy bare-SHA-256 rows keep verifying via matchesChecksum
        // and migrate to this form the next time they are written.
        String checksum = VaultChecksum.compute(checksumKey(), reference.tenantId(), plaintext);

        return new EncryptedSecret(existingId != null ? existingId : UUID.randomUUID().toString(), reference.tenantId(), reference.keyName(),
                result.ciphertext(), result.iv(), dek.dekId(), checksum, description, allowedAgents != null ? allowedAgents : List.of("*"),
                createdAt, null, lastRotatedAt);
    }

    @Override
    public SecretMetadata updateGrant(SecretReference reference, List<String> allowedAgents, String description)
            throws SecretNotFoundException, SecretProviderException {
        ensureAvailable();

        try {
            var existingOpt = persistence.findSecret(reference.tenantId(), reference.keyName());
            if (existingOpt.isEmpty()) {
                throw new SecretNotFoundException("Secret not found: " + describe(reference));
            }
            EncryptedSecret existing = existingOpt.get();

            List<String> grant = SecretMetadata.canonicalGrant(allowedAgents);
            // Null means "keep", so the existing value is passed back down: the
            // persistence call sets both fields unconditionally and knows nothing
            // about request semantics.
            String effectiveDescription = description != null ? description : existing.getDescription();

            // No activeDek() call anywhere on this path. The DEK is only needed to seal
            // or open a value, and this method does neither — which is also why a grant
            // edit does not care whether the tenant's newest DEK generation is the one
            // its row names.
            if (!persistence.updateSecretGrant(reference.tenantId(), reference.keyName(), grant, effectiveDescription)) {
                // The row was there a moment ago and is not now: a concurrent delete.
                // Reported as not-found rather than as a server error, because that is
                // what the caller should now believe about the secret.
                throw new SecretNotFoundException("Secret not found: " + describe(reference));
            }

            // Logged at INFO with both lists: the grant is a security control, and
            // "who may use this key" changing is the kind of thing an operator needs to
            // be able to reconstruct afterwards. Agent IDs are not secrets.
            // Counted only once written: a 404 or a persistence failure is not a grant
            // update, and counting it as one would hide a spike of failed attempts.
            grantUpdateCounter.increment();
            LOGGER.infof("Secret grant updated: %s (allowedAgents %s -> %s)", describe(reference),
                    sanitize(String.valueOf(existing.getAllowedAgents())), sanitize(String.valueOf(grant)));

            // Built from the row that was read plus the two fields just written, rather
            // than re-read. createdAt, lastRotatedAt, lastAccessedAt and the checksum
            // are carried through unchanged — visible proof, in the response the
            // operator sees, that a grant edit did not touch the value.
            return new SecretMetadata(existing.getTenantId(), existing.getKeyName(), existing.getCreatedAt(), existing.getLastAccessedAt(),
                    existing.getLastRotatedAt(), existing.getChecksum(), effectiveDescription, grant);
        } catch (PersistenceException e) {
            errorCounter.increment();
            throw new SecretProviderException("Persistence failure while updating the grant of " + describe(reference), e);
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
    public boolean matchesChecksum(String tenantId, String storedChecksum, String plaintext) {
        // Holds the keyed-checksum key, so it can verify both the current keyed form
        // and legacy bare SHA-256 rows written before the upgrade.
        // The key is fetched only for a keyed checksum: a legacy row verifies without
        // it,
        // so it needs no metadata read.
        boolean keyed = storedChecksum != null && storedChecksum.startsWith(VaultChecksum.KEYED_PREFIX);
        return VaultChecksum.matches(keyed ? checksumKey() : null, tenantId, storedChecksum, plaintext);
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
            String plaintext = decryptSecretWithFallback(current, dekFor(tenantId, rowDekId));
            EnvelopeCrypto.EncryptionResult enc = EnvelopeCrypto.encrypt(plaintext, activeDek,
                    secretAad(tenantId, current.getKeyName(), activeDekId));
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
        requireAcceptableNewMasterKey(newMasterKey);
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

            // Phase 1b: Verify the checksum key the same way, and prepare its new wrapping
            // now, BEFORE any write. It is re-wrapped with the new KEK exactly like a DEK,
            // so it decrypts to the SAME random value after rotation and every stored h1:
            // checksum keeps verifying. A malformed or unwrappable checksum key therefore
            // aborts the rotation here, with nothing written, instead of after every DEK
            // has already moved to the new KEK. Absent (never created yet) → nothing to do.
            String storedChecksumKey = persistence.getMetaValue(CHECKSUM_KEY_META);
            String rewrappedChecksumKey = storedChecksumKey == null
                    ? null
                    : wrapChecksumKey(unwrapChecksumKeyWith(storedChecksumKey, oldKek), newKek);

            // Phase 2: Commit — write all re-encrypted DEKs, then the checksum key. Each
            // DEK's previous wrapping is remembered before it is written, so a failed
            // write rolls the already-written DEKs back to the old KEK: the vault is then
            // still fully readable under the master key it is configured with, and the
            // rotation can simply be retried.
            List<SimpleEntry<EncryptedDek, String[]>> attempted = new ArrayList<>();
            try {
                for (var entry : prepared) {
                    EncryptedDek encDek = entry.getKey();
                    EnvelopeCrypto.EncryptionResult reEnc = entry.getValue();
                    attempted.add(new SimpleEntry<>(encDek, new String[]{encDek.getEncryptedDek(), encDek.getIv()}));
                    encDek.setEncryptedDek(reEnc.ciphertext());
                    encDek.setIv(reEnc.iv());
                    persistence.upsertDek(encDek);
                }
                if (rewrappedChecksumKey != null) {
                    persistence.setMetaValue(CHECKSUM_KEY_META, rewrappedChecksumKey);
                }
            } catch (RuntimeException e) {
                // Any failure, not only a PersistenceException: an unchecked error from a
                // store call (a driver/pool exception the implementation does not wrap)
                // after DEKs were written must still roll them back, or the stored DEKs
                // would sit on the new KEK while this node keeps using the old one.
                rollBackDeks(attempted);
                throw e;
            }

            // Phase 3: Persist new salt AFTER DEKs are re-encrypted.
            // If this fails, DEKs are on newKek but salt in DB is still legacy.
            // The operator can retry — the legacy salt is a known constant.
            if (migratingFromLegacy) {
                saltManager.migrateSalt(newSalt);
            }

            // Update our in-memory KEK and drop the cached checksum key so the next use
            // re-reads it (now wrapped with the new KEK). It unwraps to the same value.
            this.kek = newKek;
            this.checksumKey = null;

            LOGGER.infof("KEK rotated: %d DEKs re-encrypted%s", allDeks.size(),
                    migratingFromLegacy ? " + salt migrated to per-deployment random" : "");
            return allDeks.size();
        } catch (RuntimeException e) {
            // Covers PersistenceException and CryptoException as well as any unchecked
            // store error, so a failed rotation always surfaces as the documented
            // SecretProviderException rather than a raw unchecked exception.
            errorCounter.increment();
            throw new SecretProviderException("KEK rotation failed", e);
        }
    }

    /**
     * Refuse a KEK rotation to a master key the startup gate would refuse on the
     * next production boot — otherwise the rotation reports success and the restart
     * the operator is told to do next fails. Stricter than the startup gate on
     * purpose: {@code eddi.vault.allow-weak-master-key} exists to let a deployment
     * BOOT on a weak key so it can rotate off it, never to rotate onto one. Outside
     * production the key is accepted with a warning, as at startup.
     */
    private void requireAcceptableNewMasterKey(String newMasterKey) throws SecretProviderException {
        Optional<String> weakness = VaultMasterKeyStrength.weakness(newMasterKey);
        if (weakness.isEmpty()) {
            return;
        }
        if (getLaunchMode() == LaunchMode.NORMAL) {
            throw new SecretProviderException("Refusing to rotate to a weak master key: " + weakness.get()
                    + ". Nothing was changed. Choose a strong, unique passphrase (at least " + VaultMasterKeyStrength.MIN_LENGTH
                    + " characters).");
        }
        LOGGER.warnf("[VAULT] Rotating to a weak master key: %s. Tolerated in %s mode; a production boot would refuse it.",
                weakness.get(), getLaunchMode().name().toLowerCase());
    }

    /**
     * Best-effort restore of DEKs to the wrapping they had before a failed KEK
     * rotation commit. Each is attempted independently so one failed restore does
     * not strand the rest; any that cannot be restored are named in the log, since
     * those alone now need the NEW master key.
     */
    private void rollBackDeks(List<SimpleEntry<EncryptedDek, String[]>> attempted) {
        int failed = 0;
        for (var entry : attempted) {
            EncryptedDek encDek = entry.getKey();
            encDek.setEncryptedDek(entry.getValue()[0]);
            encDek.setIv(entry.getValue()[1]);
            try {
                persistence.upsertDek(encDek);
            } catch (RuntimeException rollbackFailure) {
                failed++;
                LOGGER.errorf("[VAULT] KEK rotation rollback could not restore the DEK of tenant %s (generation %d); it is now "
                        + "wrapped with the NEW master key. Retry the rotation with the same keys once the store is reachable.",
                        sanitize(encDek.getTenantId()), encDek.getGeneration());
            }
        }
        if (failed == 0) {
            LOGGER.warnf("[VAULT] KEK rotation failed during commit; rolled %d DEK(s) back to the old master key. Nothing changed.",
                    attempted.size());
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

    /**
     * The current launch mode. Package-private so a test can exercise the
     * production-vs-development branch of the master-key strength gate without
     * booting a container ({@link LaunchMode#current()} is static and not
     * mockable).
     */
    LaunchMode getLaunchMode() {
        return LaunchMode.current();
    }

    /** Meta key under which the sealed deployment checksum key is persisted. */
    private static final String CHECKSUM_KEY_META = "vault-checksum-key";

    /**
     * The deployment-wide keyed-checksum HMAC key, loaded (or created) on first
     * use.
     * <p>
     * It is a <b>random</b> key, generated once and persisted <b>wrapped by the
     * KEK</b> (like a DEK), never derived from the KEK. Wrapping — rather than
     * deriving — is what lets it survive KEK rotation: {@link #rotateKek} re-wraps
     * it with the new KEK just as it re-wraps the DEKs, so it unwraps to the same
     * value before and after rotation and every {@code h1:} checksum keeps
     * verifying. A KEK-<em>derived</em> key would change on rotation and turn a
     * legitimate same-value re-setup into a spurious "value does not match"
     * failure.
     * <p>
     * It is kept secret from a database-read attacker (the whole point of a keyed
     * checksum) precisely because it is stored KEK-wrapped, not in the clear like
     * the salt. Unlike a DEK-sealed key, wrapping touches no tenant DEK, so first
     * use does not create a default-tenant DEK as a side effect.
     */
    private byte[] checksumKey() {
        byte[] k = checksumKey;
        if (k != null) {
            return k;
        }
        synchronized (this) {
            if (checksumKey == null) {
                checksumKey = loadOrCreateChecksumKey();
            }
            return checksumKey;
        }
    }

    /**
     * Load the KEK-wrapped checksum key from the meta store, or create, wrap and
     * persist a fresh random one.
     * <p>
     * A new key is created ONLY when the store confirms none exists. Every
     * {@code h1:} checksum in the vault was written with the stored key, so
     * replacing it would make all of them unverifiable — permanently, because the
     * old key would be gone. A failed read (a transient database error) or a failed
     * unwrap of an existing key (a KEK mismatch) therefore propagates instead of
     * falling through to creation; nothing is cached, so the next use retries. The
     * create itself is an insert-if-absent, so racing creators on several nodes
     * converge on the one key that won the insert rather than the last writer
     * replacing a key another node has already used. There is no fallback key: a
     * checksum written under any other key would be unverifiable later, which is
     * the damage this method exists to prevent.
     */
    private byte[] loadOrCreateChecksumKey() {
        String stored = persistence.getMetaValue(CHECKSUM_KEY_META);
        if (stored != null) {
            return unwrapChecksumKey(stored);
        }
        byte[] raw = new byte[32];
        new SecureRandom().nextBytes(raw);
        String winner = persistence.setMetaValueIfAbsent(CHECKSUM_KEY_META, wrapChecksumKey(raw, kek));
        if (winner == null) {
            // A store that keeps no metadata cannot hold the key durably. A key that
            // lived only for this process would make every h1: checksum it wrote
            // unverifiable after the next restart, so refuse rather than write one.
            throw new PersistenceException("The secret store keeps no vault metadata, so the keyed-checksum key cannot be "
                    + "persisted; refusing to write a checksum no restart could verify");
        }
        return unwrapChecksumKey(winner);
    }

    /**
     * {@code iv|ciphertext} (both base64) of the checksum key wrapped by
     * {@code wrapKek}.
     */
    private static String wrapChecksumKey(byte[] rawKey, byte[] wrapKek) {
        EnvelopeCrypto.EncryptionResult enc = EnvelopeCrypto.encryptDek(rawKey, wrapKek);
        return enc.iv() + "|" + enc.ciphertext();
    }

    private byte[] unwrapChecksumKey(String serialized) {
        return unwrapChecksumKeyWith(serialized, kek);
    }

    /**
     * As {@link #unwrapChecksumKey} but with an explicit KEK — used during KEK
     * rotation.
     */
    private static byte[] unwrapChecksumKeyWith(String serialized, byte[] kekToUse) {
        String[] parts = serialized.split("\\|", 2);
        if (parts.length != 2) {
            throw new EnvelopeCrypto.CryptoException("Malformed persisted checksum key");
        }
        return EnvelopeCrypto.decryptDek(parts[1], parts[0], kekToUse);
    }

    /**
     * The GCM AAD that binds a secret's ciphertext to the row it belongs to:
     * {@code tenantId|keyName|dekId}. All three are stored on the row and so are
     * reconstructable at decrypt time, and none of them can change without a
     * re-seal (a grant edit touches neither), so binding them cannot break a later
     * legitimate read. The grant list is deliberately NOT bound —
     * {@code updateGrant} rewrites it without re-encrypting, so binding it would
     * make every post-grant-edit read fail.
     */
    private static byte[] secretAad(String tenantId, String keyName, String dekId) {
        return (tenantId + "|" + keyName + "|" + dekId).getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * Decrypt a stored secret, verifying the row-identity AAD, and falling back to
     * a no-AAD decrypt for rows written before AAD binding existed.
     * <p>
     * GCM authenticates or fails, so a legacy (no-AAD) row cannot be read with the
     * AAD and vice versa; trying the AAD first and the legacy form only on failure
     * lets both coexist without a schema flag. A tampered row — ciphertext swapped
     * from another key — fails both and surfaces as a decryption failure, which is
     * the intended outcome.
     */
    private String decryptSecretWithFallback(EncryptedSecret secret, byte[] dek) {
        byte[] aad = secretAad(secret.getTenantId(), secret.getKeyName(), secret.getDekId());
        try {
            return EnvelopeCrypto.decrypt(secret.getEncryptedValue(), secret.getIv(), dek, aad);
        } catch (EnvelopeCrypto.CryptoException withAadFailed) {
            // Legacy row written before AAD binding — retry without AAD. If this also
            // fails, the original (AAD) failure is the more informative one to surface.
            try {
                return EnvelopeCrypto.decrypt(secret.getEncryptedValue(), secret.getIv(), dek);
            } catch (EnvelopeCrypto.CryptoException legacyFailed) {
                throw withAadFailed;
            }
        }
    }

    private void ensureAvailable() throws SecretProviderException {
        if (!available) {
            throw new SecretProviderException("Secrets Vault is not available. Set EDDI_VAULT_MASTER_KEY environment variable.");
        }
    }
}
