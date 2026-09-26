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

    /**
     * Meta key of the KEK check value: a known constant sealed under the vault's
     * current KEK.
     * <p>
     * It answers one question cheaply — "is the KEK this node holds still the
     * vault's KEK?" — and is consulted before a node wraps a new DEK. After a KEK
     * rotation every other replica keeps running on the old master key until it is
     * restarted; before this check such a replica would happily wrap a new tenant's
     * DEK under the retired KEK, and that DEK — and every secret sealed with it —
     * became unreadable the moment the replica restarted with the new key.
     */
    static final String KEK_CHECK_META_KEY = "vault-kek-check";

    /**
     * The tenant whose DEKs seal EDDI's own system values — see
     * {@link #pinSystemValue}. Its name is a valid tenant id, so it is refused
     * explicitly where destroying its DEKs would destroy those values.
     */
    public static final String SYSTEM_TENANT = "__eddi-system";

    static final String SYSTEM_VALUE_META_PREFIX = "system-value:";

    private static final String KEK_CHECK_PLAINTEXT = "eddi-vault-kek-check";
    private static final String KEK_CHECK_AAD = "eddi-kek-check|v1";

    /**
     * How many catch-up passes a KEK rotation makes for DEKs created mid-rotation.
     */
    private static final int KEK_CATCH_UP_PASSES = 3;

    private volatile byte[] kek; // Key Encryption Key derived from master key

    /**
     * A second KEK this node can open DEKs with, or null: the one derived from the
     * pending salt of a legacy-salt KEK rotation that did not finish. Some DEKs may
     * already be wrapped under it, and they must keep opening until the rotation is
     * re-run.
     */
    private volatile byte[] fallbackKek;
    private volatile boolean available = false;

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

        // Initialize per-deployment salt (generates on first boot, loads on
        // subsequent).
        // Throws when the salt cannot be established, which fails the start: every
        // fall-back would derive a KEK that may not be the one the DEKs are wrapped
        // with.
        saltManager.initialize();

        this.kek = EnvelopeCrypto.deriveKeyFromString(masterKeyConfig.get(), saltManager.getSalt());

        if (saltManager.isUsingLegacySalt()) {
            LOGGER.warn("[VAULT] Using legacy fixed salt for KEK derivation. "
                    + "Run KEK rotation to migrate to a per-deployment random salt.");
            try {
                byte[] pendingSalt = saltManager.getPendingSalt();
                if (pendingSalt != null) {
                    this.fallbackKek = EnvelopeCrypto.deriveKeyFromString(masterKeyConfig.get(), pendingSalt);
                }
            } catch (PersistenceException e) {
                LOGGER.warn("[VAULT] Could not check for an unfinished salt migration: " + e.getMessage());
            }
        }

        this.available = true;
        reconcileKekCheck();

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
            String plaintext = EnvelopeCrypto.decrypt(secret.getEncryptedValue(), secret.getIv(), dek,
                    secretAad(reference.tenantId(), reference.keyName()));

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
            ActiveDek dek = activeDek(reference.tenantId());

            // Encrypt the plaintext with the tenant's DEK, bound to the row it is
            // stored in so it cannot be copied into another secret's row.
            EnvelopeCrypto.EncryptionResult result = EnvelopeCrypto.encrypt(plaintext, dek.key(),
                    secretAad(reference.tenantId(), reference.keyName()));
            String checksum = EnvelopeCrypto.sha256Hex(plaintext);

            // Check if this is an update (rotation) or new secret
            var existingOpt = persistence.findSecret(reference.tenantId(), reference.keyName());
            Instant now = Instant.now();

            // A null grant or description is passed through as null — "not supplied" —
            // rather than defaulted here. The store keeps what the row already has on an
            // update and writes the defaults only on an insert. Defaulting here reset a
            // narrowed grant to ["*"] and wiped the description on every value rotation
            // that did not restate them, and the whole-row write also reverted any grant
            // edit that landed between the read above and the write below.
            EncryptedSecret secret = new EncryptedSecret(existingOpt.map(EncryptedSecret::getId).orElse(UUID.randomUUID().toString()),
                    reference.tenantId(), reference.keyName(), result.ciphertext(), result.iv(), dek.dekId(), checksum, description,
                    allowedAgents, existingOpt.map(EncryptedSecret::getCreatedAt).orElse(now), null, existingOpt.isPresent() ? now : null);

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
    public SecretMetadata updateGrant(SecretReference reference, List<String> allowedAgents, String description)
            throws SecretNotFoundException, SecretProviderException {
        return updateGrant(reference, allowedAgents, description, null);
    }

    @Override
    public SecretMetadata updateGrant(SecretReference reference, List<String> allowedAgents, String description,
                                      List<String> expectedAllowedAgents)
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
            if (expectedAllowedAgents != null) {
                List<String> expected = SecretMetadata.canonicalGrant(expectedAllowedAgents);
                // Checked here for the common case, and again by the write itself: the
                // read above and the write below are two statements, and only the guarded
                // write can see an edit that lands in between.
                if (!SecretMetadata.sameGrant(existing.getAllowedAgents(), expected)) {
                    throw grantConflict(reference, existing.getAllowedAgents());
                }
                if (!persistence.updateSecretGrantIfUnchanged(reference.tenantId(), reference.keyName(), expected, grant,
                        effectiveDescription)) {
                    var now = persistence.findSecret(reference.tenantId(), reference.keyName());
                    if (now.isEmpty()) {
                        throw new SecretNotFoundException("Secret not found: " + describe(reference));
                    }
                    throw grantConflict(reference, now.get().getAllowedAgents());
                }
            } else if (!persistence.updateSecretGrant(reference.tenantId(), reference.keyName(), grant, effectiveDescription)) {
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

    private GrantConflictException grantConflict(SecretReference reference, List<String> current) {
        return new GrantConflictException("The grant of " + describe(reference)
                + " was changed by somebody else since it was read; nothing was written. Review the current grant and apply the edit again.",
                SecretMetadata.canonicalGrant(current));
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
                unwrap(generation);
                highest = Math.max(highest, generation.getGeneration());
            }

            // 2. Commit. The single atomic step in the whole operation.
            ensureKekCurrent(tenantId);
            nextGeneration = highest + 1;
            newDek = EnvelopeCrypto.generateDek();
            EnvelopeCrypto.EncryptionResult enc = EnvelopeCrypto.encryptDek(newDek, kek, dekAad(tenantId, nextGeneration));
            EncryptedDek entity = new EncryptedDek(UUID.randomUUID().toString(), tenantId, nextGeneration, enc.ciphertext(), enc.iv(),
                    Instant.now());
            if (!persistence.insertDek(entity)) {
                throw new SecretProviderException("Generation " + nextGeneration + " already exists for tenant '" + sanitize(tenantId)
                        + "'. Another rotation installed it first; nothing was changed by this one.");
            }
            // Before the sweep moves a single row onto it.
            takeBackIfKekRetired(entity);
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
            String aad = secretAad(tenantId, current.getKeyName());
            String plaintext = EnvelopeCrypto.decrypt(current.getEncryptedValue(), current.getIv(), dekFor(tenantId, rowDekId), aad);
            // Re-sealed WITH associated data even when the row was sealed without it, so a
            // DEK rotation is also what migrates a tenant's older rows onto the bound form.
            EnvelopeCrypto.EncryptionResult enc = EnvelopeCrypto.encrypt(plaintext, activeDek, aad);
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
     * <b>Not transactional, so built to be re-run.</b> Neither store can re-wrap
     * every DEK atomically, so the rotation is ordered so that stopping anywhere
     * leaves something a retry with the same two keys completes:
     * <ol>
     * <li><b>New salt first.</b> A legacy-salt deployment migrates to a random salt
     * as part of the rotation, and that salt is persisted as <em>pending</em>
     * before anything is wrapped under it. It used to live in memory until the end,
     * so a rotation that failed half-way left DEKs wrapped under a KEK derived from
     * a salt nobody had kept.</li>
     * <li><b>Verify</b> — every DEK must open with the old KEK <em>or the new
     * one</em>. The second is what an interrupted run leaves behind; refusing it,
     * as this used to, made the documented "retry" fail every time.</li>
     * <li><b>Announce</b> — the KEK check value is switched to the new KEK before
     * any DEK is re-wrapped, so no replica still on the old master key wraps a new
     * DEK under it from here on (see {@link #KEK_CHECK_META_KEY}).</li>
     * <li><b>Re-wrap</b> each DEK, guarded on the wrapping it was read with, then
     * sweep again for a DEK another replica created between the listing and the
     * announcement.</li>
     * <li><b>Promote</b> the pending salt to the deployment's salt.</li>
     * </ol>
     * <p>
     * <b>Usage:</b>
     * <ol>
     * <li>Call this method with both the old and new master keys</li>
     * <li>Restart <em>every</em> replica with the new master key in the
     * environment. Until they are restarted, replicas other than this one cannot
     * open the re-wrapped DEKs, and refuse to create new ones</li>
     * </ol>
     *
     * @param oldMasterKey
     *            the current master key (to decrypt existing DEKs)
     * @param newMasterKey
     *            the new master key (to re-encrypt DEKs)
     * @return the number of DEKs wrapped under the new master key when this returns
     * @throws SecretProviderException
     *             if rotation fails
     */
    public int rotateKek(String oldMasterKey, String newMasterKey) throws SecretProviderException {
        if (!available) {
            throw new SecretProviderException("Secrets Vault is not available. Cannot rotate KEK.");
        }
        rotateCounter.increment();

        List<byte[]> oldKeks = new ArrayList<>();
        byte[] newKek;
        byte[] newSalt;
        boolean migratingFromLegacy;
        List<SimpleEntry<EncryptedDek, byte[]>> toRewrap = new ArrayList<>();
        int alreadyOnNewKek = 0;
        boolean reservedFreshPendingSalt = false;
        try {
            // 1. Derive old KEK with current salt (legacy or random)
            oldKeks.add(EnvelopeCrypto.deriveKeyFromString(oldMasterKey, saltManager.getSalt()));

            // 2. Determine new salt — migrate from legacy if needed. A pending salt an
            // interrupted rotation persisted is reused, so this run derives the same
            // new KEK that run wrapped some DEKs under.
            migratingFromLegacy = saltManager.isUsingLegacySalt();
            if (migratingFromLegacy) {
                byte[] earlierPending = saltManager.getPendingSalt();
                if (earlierPending != null) {
                    // An earlier legacy migration announced the pending-salt KEK and
                    // stopped, and the nodes adopted it at restart (reconcileKekCheck). DEKs
                    // it re-wrapped, and DEKs created since, are under the OLD master key
                    // with the PENDING salt — which the legacy-salt derivation above
                    // cannot open.
                    oldKeks.add(EnvelopeCrypto.deriveKeyFromString(oldMasterKey, earlierPending));
                }
                newSalt = saltManager.reservePendingSalt();
                // No earlier pending salt: this run created the reservation, so nothing
                // can be wrapped under it yet, and a refusal below must take it back.
                reservedFreshPendingSalt = earlierPending == null;
                LOGGER.info("[VAULT] KEK rotation will also migrate from legacy salt to per-deployment random salt.");
            } else {
                newSalt = saltManager.getSalt();
            }
            newKek = EnvelopeCrypto.deriveKeyFromString(newMasterKey, newSalt);

            // 3. Verify — every DEK must open with the old KEK, or already with the new
            // one. Nothing has been written yet, so a DEK that opens with neither is a
            // clean refusal.
            for (EncryptedDek encDek : persistence.listAllDeks()) {
                byte[] opening = openingKek(encDek, oldKeks);
                if (opening != null) {
                    toRewrap.add(new SimpleEntry<>(encDek, opening));
                } else if (opensWith(encDek, newKek)) {
                    alreadyOnNewKek++;
                } else {
                    throw new SecretProviderException("KEK rotation refused: DEK generation " + encDek.getGeneration() + " of tenant '"
                            + sanitize(encDek.getTenantId()) + "' opens with neither the old nor the new master key. Nothing was changed."
                            + (migratingFromLegacy
                                    ? " If an earlier KEK rotation from a previous master key never finished, this DEK is still under that "
                                            + "previous key: first re-run rotate-kek from the previous key to the current one, then rotate again."
                                    : ""));
                }
            }
        } catch (SecretProviderException e) {
            discardFreshPendingSalt(reservedFreshPendingSalt);
            throw e;
        } catch (PersistenceException | EnvelopeCrypto.CryptoException e) {
            errorCounter.increment();
            discardFreshPendingSalt(reservedFreshPendingSalt);
            throw new SecretProviderException("KEK rotation failed before anything was changed", e);
        }

        int rewrapped = 0;
        try {
            // 4. Announce, BEFORE the first re-wrap: from here on a replica still on the
            // old master key refuses to wrap a new DEK instead of wrapping it under a
            // KEK that is on its way out.
            persistence.setMetaValue(KEK_CHECK_META_KEY, kekCheckValue(newKek));

            // 5. Re-wrap.
            for (var entry : toRewrap) {
                rewrap(entry.getKey(), entry.getValue(), newKek);
                rewrapped++;
            }

            // 6. Catch up: a replica that read the old check value just before step 4 may
            // have wrapped a DEK under the old KEK after the listing in step 3.
            for (int pass = 0; pass < KEK_CATCH_UP_PASSES; pass++) {
                int caughtUp = 0;
                for (EncryptedDek encDek : persistence.listAllDeks()) {
                    byte[] opening = opensWith(encDek, newKek) ? null : openingKek(encDek, oldKeks);
                    if (opening != null) {
                        rewrap(encDek, opening, newKek);
                        caughtUp++;
                    }
                }
                rewrapped += caughtUp;
                if (caughtUp == 0) {
                    break;
                }
            }

            // 7. Promote the pending salt. If this write fails every DEK is already on
            // the new KEK and the pending salt is persisted, so a re-run only has this
            // step left to do.
            if (migratingFromLegacy) {
                saltManager.migrateSalt(newSalt);
            }
        } catch (PersistenceException | EnvelopeCrypto.CryptoException | IllegalStateException e) {
            errorCounter.increment();
            throw new SecretProviderException("KEK rotation incomplete: " + (alreadyOnNewKek + rewrapped) + " DEK(s) are wrapped under the new "
                    + "master key and the rest still under the old one. Nothing is lost — every DEK opens with one of the two keys — and "
                    + "re-running the rotation with the same old and new master keys completes it.", e);
        }

        // Update our in-memory KEK to the new one
        this.kek = newKek;
        this.fallbackKek = null;

        int total = alreadyOnNewKek + rewrapped;
        LOGGER.infof("KEK rotated: %d DEKs re-encrypted%s. Restart every replica with the new EDDI_VAULT_MASTER_KEY.", total,
                migratingFromLegacy ? " + salt migrated to per-deployment random" : "");
        return total;
    }

    private static byte[] openingKek(EncryptedDek encDek, List<byte[]> candidates) {
        for (byte[] candidate : candidates) {
            if (opensWith(encDek, candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * Re-wraps one DEK from the old KEK to the new one, guarded on the wrapping it
     * was read with. On a lost guard the row is re-read once: gone (a tenant reset)
     * or already on the new KEK (a concurrent rotation) both count as done.
     */
    private void rewrap(EncryptedDek encDek, byte[] oldKek, byte[] newKek) {
        EncryptedDek current = encDek;
        for (int attempt = 0; attempt < 2; attempt++) {
            String aad = dekAad(current.getTenantId(), current.getGeneration());
            byte[] rawDek = EnvelopeCrypto.decryptDek(current.getEncryptedDek(), current.getIv(), oldKek, aad);
            EnvelopeCrypto.EncryptionResult reEnc = EnvelopeCrypto.encryptDek(rawDek, newKek, aad);
            String expectedIv = current.getIv();
            current.setEncryptedDek(reEnc.ciphertext());
            current.setIv(reEnc.iv());
            if (persistence.updateDekWrapping(current, expectedIv)) {
                return;
            }
            current = persistence.findDek(encDek.getTenantId(), encDek.getGeneration()).orElse(null);
            if (current == null || opensWith(current, newKek)) {
                return;
            }
        }
        throw new PersistenceException("DEK generation " + encDek.getGeneration() + " of tenant '" + sanitize(encDek.getTenantId())
                + "' kept changing while it was being re-wrapped");
    }

    @Override
    public int resetTenant(String tenantId) throws SecretProviderException {
        ensureAvailable();
        if (SYSTEM_TENANT.equals(tenantId)) {
            // Its DEKs seal the system values (the audit ledger's pinned key), and
            // nothing a reset is for — recovering from a lost master key — is fixed by
            // destroying them.
            throw new SecretProviderException("Tenant '" + SYSTEM_TENANT + "' holds EDDI's own sealed system values and cannot be reset.");
        }

        try {
            // Delete all secrets first, then the DEK
            var secrets = persistence.listSecretsByTenant(tenantId);
            int deletedCount = 0;

            for (var secret : secrets) {
                if (persistence.deleteSecret(tenantId, secret.getKeyName())) {
                    deletedCount++;
                }
            }

            // Everything else sealed with the tenant's DEKs goes too, BEFORE the DEKs do.
            // Left in place it is not merely unreadable: the next DEK this tenant gets is
            // generation 1 again, with the same dekId the stranded rows name, so every
            // later read opened them with the wrong key and failed GCM authentication on
            // every request instead of reporting the data as gone.
            int discarded = discardParticipantData(tenantId, false);

            persistence.deleteDek(tenantId);

            // And once more after: a value sealed between the discard above and the
            // delete (an OAuth callback landing mid-reset) names a generation-1 dekId the
            // tenant's next DEK reuses — the stranded row this discard exists to prevent.
            discarded += discardParticipantData(tenantId, true);

            LOGGER.infof("[VAULT] Tenant '%s' reset: %d secret(s) deleted, %d other sealed value(s) discarded, DEK removed.", sanitize(tenantId),
                    deletedCount, discarded);
            return deletedCount;
        } catch (PersistenceException e) {
            throw new SecretProviderException("Failed to reset vault for tenant " + sanitize(tenantId), e);
        }
    }

    /**
     * Asks every {@link SealedDataRotationParticipant} to drop what it sealed for a
     * tenant whose DEKs are about to be deleted.
     *
     * @throws SecretProviderException
     *             if any participant fails — before the DEKs are deleted, so the
     *             reset can be re-run rather than leaving rows stranded
     */
    private int discardParticipantData(String tenantId, boolean deksAlreadyDeleted) throws SecretProviderException {
        if (rotationParticipants == null || rotationParticipants.isUnsatisfied()) {
            return 0;
        }
        int discarded = 0;
        for (SealedDataRotationParticipant participant : rotationParticipants) {
            try {
                discarded += participant.discardAll(tenantId);
            } catch (RuntimeException e) {
                errorCounter.increment();
                throw new SecretProviderException("Vault reset for tenant '" + sanitize(tenantId) + "' stopped: "
                        + participant.sealedDataDescription() + " could not be discarded. "
                        + (deksAlreadyDeleted
                                ? "The tenant's secrets and DEKs are deleted; re-run the reset to discard what is left."
                                : "The tenant's secrets are deleted but its DEKs are not, so nothing is stranded; re-run the reset."),
                        e);
            }
        }
        return discarded;
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

    /**
     * {@inheritDoc}
     * <p>
     * Sealed with a DEK of {@link #SYSTEM_TENANT} and kept in vault metadata, not
     * in the secret collection. Sealing under a DEK rather than straight under the
     * KEK is what carries the value through a KEK rotation with no extra step: the
     * rotation re-wraps every DEK, and the ciphertext itself never needs touching.
     * A DEK rotation of the system tenant leaves the value on an older generation,
     * which is never deleted.
     */
    @Override
    public String pinSystemValue(String name, String candidate) throws SecretProviderException {
        ensureAvailable();
        String metaKey = SYSTEM_VALUE_META_PREFIX + name;
        try {
            String stored = persistence.getMetaValue(metaKey);
            if (stored == null) {
                stored = persistence.putMetaValueIfAbsent(metaKey, encodeSealed(sealSystemValue(name, candidate)));
                if (stored == null) {
                    throw new SecretProviderException("The secret persistence layer has no metadata store; system value '" + sanitize(name)
                            + "' cannot be kept.");
                }
            }
            return unsealSystemValue(name, decodeSealed(stored));
        } catch (PersistenceException | IllegalArgumentException | EnvelopeCrypto.CryptoException e) {
            errorCounter.increment();
            throw new SecretProviderException("Could not pin system value '" + sanitize(name) + "'", e);
        }
    }

    @Override
    public Optional<String> readSystemValue(String name) throws SecretProviderException {
        ensureAvailable();
        try {
            String stored = persistence.getMetaValue(SYSTEM_VALUE_META_PREFIX + name);
            return stored == null ? Optional.empty() : Optional.of(unsealSystemValue(name, decodeSealed(stored)));
        } catch (PersistenceException | IllegalArgumentException | EnvelopeCrypto.CryptoException e) {
            errorCounter.increment();
            throw new SecretProviderException("Could not read system value '" + sanitize(name) + "'", e);
        }
    }

    /**
     * Takes back the pending salt a refused legacy-salt rotation reserved, so the
     * deployment does not go on reporting an unfinished rotation that never wrote
     * anything. Best-effort: the refusal is what the caller needs to see.
     */
    private void discardFreshPendingSalt(boolean reservedFresh) {
        if (!reservedFresh) {
            return;
        }
        try {
            saltManager.discardPendingSalt();
        } catch (PersistenceException e) {
            LOGGER.warn("[VAULT] Could not remove the pending salt of a refused KEK rotation: " + sanitize(e.getMessage()));
        }
    }

    /**
     * Seals a system value bound to its name, so a sealed value copied under
     * another name in the metadata store fails authentication instead of reading as
     * that name's value.
     */
    private SealedValue sealSystemValue(String name, String value) throws SecretProviderException {
        ActiveDek dek = activeDek(SYSTEM_TENANT);
        EnvelopeCrypto.EncryptionResult result = EnvelopeCrypto.encrypt(value, dek.key(), systemValueAad(name));
        return new SealedValue(result.ciphertext(), result.iv(), dek.dekId());
    }

    private String unsealSystemValue(String name, SealedValue sealed) throws SecretProviderException {
        return EnvelopeCrypto.decrypt(sealed.ciphertext(), sealed.iv(), dekFor(SYSTEM_TENANT, sealed.dekId()), systemValueAad(name));
    }

    static String systemValueAad(String name) {
        return "eddi-system-value|v1|" + name;
    }

    /**
     * What {@link #adoptCurrentMasterKey()} did.
     *
     * @param tenantsNeedingReset
     *            tenants whose DEKs this node's master key cannot open. Their
     *            secrets are unrecoverable without the previous key; {@code POST
     *            /{tenantId}/reset} clears each one
     * @param systemValuesReset
     *            whether the system tenant's DEKs and every pinned system value
     *            were discarded — they were sealed under the lost key and are
     *            re-created on next use (the audit ledger pins a new key)
     */
    public record MasterKeyAdoption(List<String> tenantsNeedingReset, boolean systemValuesReset) {
    }

    /**
     * Makes this node's master key the vault's master key, for the case a KEK
     * rotation cannot cover: the previous master key is <b>lost</b>.
     * <p>
     * The KEK check value records which KEK the vault uses, and every node refuses
     * to wrap a new DEK under any other — that is what keeps a replica still on a
     * retired key from stranding a tenant after a rotation. It cannot tell that
     * case apart from an operator who lost the key and set a new one: from a node's
     * side the two look identical. So after a lost key every DEK creation was
     * refused, for every tenant, forever — the documented "reset the tenant and
     * start fresh" recovery included. This is the explicit operator decision that
     * resolves it:
     * <ol>
     * <li>the check value is re-announced with this node's KEK;</li>
     * <li>if the system tenant's DEKs do not open with it, they and every pinned
     * system value are discarded (they are re-created on next use);</li>
     * <li>every other tenant whose DEKs do not open is reported, not touched — its
     * secrets are gone, and resetting it is a separate, deliberate step.</li>
     * </ol>
     * <b>Never call this while a KEK rotation is merely unfinished</b>, or on a
     * replica that simply has not been restarted with a rotated key: in those cases
     * the previous key still exists, {@code rotate-kek} recovers everything, and
     * adopting the wrong key makes every other replica refuse DEK creation instead.
     */
    public MasterKeyAdoption adoptCurrentMasterKey() throws SecretProviderException {
        ensureAvailable();
        try {
            Set<String> unreadable = new TreeSet<>();
            for (EncryptedDek dek : persistence.listAllDeks()) {
                if (!opensWith(dek, kek)) {
                    unreadable.add(dek.getTenantId());
                }
            }
            persistence.setMetaValue(KEK_CHECK_META_KEY, kekCheckValue(kek));
            this.fallbackKek = null;

            boolean systemReset = unreadable.remove(SYSTEM_TENANT);
            if (systemReset) {
                persistence.deleteMetaValuesWithPrefix(SYSTEM_VALUE_META_PREFIX);
                persistence.deleteDek(SYSTEM_TENANT);
                // A pinned value sealed in the gap between the two deletes would name a
                // generation the next DEK reuses; clear once more now the DEKs are gone.
                persistence.deleteMetaValuesWithPrefix(SYSTEM_VALUE_META_PREFIX);
            }
            LOGGER.warnf("[VAULT] The configured master key was adopted as the vault's master key.%s %d tenant(s) hold DEKs it cannot open "
                    + "and need POST /secretstore/secrets/{tenantId}/reset: %s", systemReset ? " System values were reset." : "", unreadable.size(),
                    sanitize(String.valueOf(unreadable)));
            return new MasterKeyAdoption(List.copyOf(unreadable), systemReset);
        } catch (PersistenceException e) {
            errorCounter.increment();
            throw new SecretProviderException("Adopting the master key failed", e);
        }
    }

    private static String encodeSealed(SealedValue sealed) {
        return sealed.dekId() + "|" + sealed.iv() + "|" + sealed.ciphertext();
    }

    private static SealedValue decodeSealed(String encoded) {
        String[] parts = encoded.split("\\|", 3);
        if (parts.length != 3) {
            throw new IllegalArgumentException("Malformed sealed system value");
        }
        return new SealedValue(parts[2], parts[1], parts[0]);
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
                return new ActiveDek(unwrap(encryptedDek), encryptedDek.dekId());
            }

            return firstDek(tenantId);
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
            return unwrap(dekOpt.get());
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
        boolean pendingMigration = false;
        boolean checkIsOurs = false;
        try {
            pendingMigration = saltManager.getPendingSalt() != null;
            String check = persistence.getMetaValue(KEK_CHECK_META_KEY);
            checkIsOurs = check != null && kekCheckOpens(check, kek);
        } catch (PersistenceException | IllegalStateException e) {
            // Fall through to the general message.
        }
        if (pendingMigration || checkIsOurs) {
            // This node's key IS the vault's key, so this DEK was simply not reached by a
            // KEK rotation. One re-run recovers it; a reset would destroy it.
            throw new SecretProviderException("Cannot decrypt the Data Encryption Key (DEK) for tenant '" + safeTenantId + "': it is still "
                    + "wrapped under a previous master key, while this node's EDDI_VAULT_MASTER_KEY is the vault's current one. A KEK rotation "
                    + "did not finish. Re-run POST /secretstore/secrets/admin/rotate-kek with the previous and the current master key — nothing "
                    + "is lost. " + (pendingMigration
                            ? ""
                            : "Only if the previous master key is lost for good: " + secretInfo + " POST /secretstore/secrets/" + safeTenantId
                                    + "/reset clears this tenant."),
                    cause);
        }
        throw new SecretProviderException(
                "Cannot decrypt the Data Encryption Key (DEK) for tenant '" + safeTenantId + "'. "
                        + "This means the EDDI_VAULT_MASTER_KEY has changed since the DEK was created. "
                        + secretInfo + " "
                        + "Recovery options: "
                        + "(1) Set EDDI_VAULT_MASTER_KEY back to the original value and restart. "
                        + "(2) If you have both old and new keys, use POST /secretstore/secrets/admin/rotate-kek "
                        + "to migrate all encrypted data to the new key. "
                        + "(3) To start fresh (deletes all secrets for this tenant), use "
                        + "POST /secretstore/secrets/" + safeTenantId + "/reset to clear the vault for this tenant. "
                        + "(4) If the previous master key is lost for good, POST /secretstore/secrets/admin/adopt-master-key?confirm=true "
                        + "makes the current key the vault's key, so new DEKs can be created again.",
                cause);
    }

    /**
     * Creates a tenant's first DEK generation — with an <em>insert</em>, so two
     * concurrent first stores for a tenant cannot both believe they created it.
     * <p>
     * This used to be an upsert. Both callers generated a key, both wrote
     * generation 1, the second write replaced the first, and the secret the first
     * caller had already sealed with its key was lost for good. The loser of the
     * insert now reads back and uses the winner's key.
     */
    private ActiveDek firstDek(String tenantId) throws SecretProviderException {
        ensureKekCurrent(tenantId);
        byte[] newDek = EnvelopeCrypto.generateDek();
        EnvelopeCrypto.EncryptionResult encResult = EnvelopeCrypto.encryptDek(newDek, kek, dekAad(tenantId, EncryptedDek.FIRST_GENERATION));

        EncryptedDek dek = new EncryptedDek(UUID.randomUUID().toString(), tenantId, EncryptedDek.FIRST_GENERATION, encResult.ciphertext(),
                encResult.iv(), Instant.now());

        if (persistence.insertDek(dek)) {
            takeBackIfKekRetired(dek);
            LOGGER.infof("Generated new DEK for tenant: %s", sanitize(tenantId));
            return new ActiveDek(newDek, dek.dekId());
        }

        // Somebody else created it first. Use theirs — whatever is now newest, since a
        // rotation may even have followed.
        EncryptedDek winner = persistence.findDek(tenantId)
                .orElseThrow(() -> new SecretProviderException("DEK for tenant '" + sanitize(tenantId)
                        + "' was created concurrently but cannot be read back"));
        return new ActiveDek(unwrap(winner), winner.dekId());
    }

    /**
     * Opens a stored DEK with this node's KEK — or, after an interrupted
     * legacy-salt rotation, with the KEK derived from the pending salt.
     */
    private byte[] unwrap(EncryptedDek encryptedDek) throws SecretProviderException {
        String aad = dekAad(encryptedDek.getTenantId(), encryptedDek.getGeneration());
        try {
            return EnvelopeCrypto.decryptDek(encryptedDek.getEncryptedDek(), encryptedDek.getIv(), kek, aad);
        } catch (EnvelopeCrypto.CryptoException e) {
            byte[] fallback = fallbackKek;
            if (fallback != null) {
                try {
                    return EnvelopeCrypto.decryptDek(encryptedDek.getEncryptedDek(), encryptedDek.getIv(), fallback, aad);
                } catch (EnvelopeCrypto.CryptoException ignored) {
                    // Reported below with the original cause.
                }
            }
            return handleDekDecryptionFailure(encryptedDek.getTenantId(), e);
        }
    }

    private static boolean opensWith(EncryptedDek encryptedDek, byte[] candidateKek) {
        try {
            EnvelopeCrypto.decryptDek(encryptedDek.getEncryptedDek(), encryptedDek.getIv(), candidateKek,
                    dekAad(encryptedDek.getTenantId(), encryptedDek.getGeneration()));
            return true;
        } catch (EnvelopeCrypto.CryptoException e) {
            return false;
        }
    }

    /**
     * Refuses to wrap a new DEK when this node's KEK is no longer the vault's — a
     * KEK rotation ran elsewhere and this replica has not been restarted with the
     * new master key yet. A deployment that predates the check value has none, and
     * is not refused.
     */
    private void ensureKekCurrent(String tenantId) throws SecretProviderException {
        String check;
        try {
            check = persistence.getMetaValue(KEK_CHECK_META_KEY);
        } catch (PersistenceException e) {
            throw new SecretProviderException("Could not confirm this node's master key before creating a DEK for tenant '" + sanitize(tenantId)
                    + "'", e);
        }
        if (check != null && !kekCheckOpens(check, kek)) {
            errorCounter.increment();
            throw new SecretProviderException("This node's EDDI_VAULT_MASTER_KEY is no longer the vault's master key — a KEK rotation has run. "
                    + "Restart this node with the new master key. No DEK was created for tenant '" + sanitize(tenantId)
                    + "', so nothing was sealed under the retired key.");
        }
    }

    /**
     * Checks the KEK again <em>after</em> a DEK was inserted, and takes the DEK
     * back if a KEK rotation announced a new KEK in between.
     * <p>
     * {@link #ensureKekCurrent} before the insert leaves a window: a node on the
     * old master key reads the old check value, stalls (a GC pause, a slow
     * database) past the rotation's catch-up sweeps, and then inserts a DEK wrapped
     * under the retired KEK, which becomes unreadable once every node restarts with
     * the new one. Reading the check after the insert closes it: if the check still
     * matches, the insert happened before the announcement, and the rotation's
     * catch-up sweep — which lists DEKs after announcing — sees it and re-wraps it.
     * If it does not match, the DEK is deleted before anything is sealed with it.
     */
    private void takeBackIfKekRetired(EncryptedDek inserted) throws SecretProviderException {
        String check;
        try {
            check = persistence.getMetaValue(KEK_CHECK_META_KEY);
        } catch (PersistenceException e) {
            check = null;
        }
        if (check == null || kekCheckOpens(check, kek)) {
            return;
        }
        try {
            persistence.deleteDekIfWrappedWith(inserted.getTenantId(), inserted.getGeneration(), inserted.getIv());
        } catch (PersistenceException e) {
            LOGGER.errorf(e, "[VAULT] Could not take back DEK generation %d of tenant '%s', wrapped under a retired KEK",
                    inserted.getGeneration(), sanitize(inserted.getTenantId()));
        }
        errorCounter.increment();
        throw new SecretProviderException("A KEK rotation ran while this node was creating a DEK for tenant '" + sanitize(inserted.getTenantId())
                + "'. The DEK was taken back before anything was sealed with it. Restart this node with the new master key.");
    }

    /**
     * Records the KEK check value on a deployment that has none, and reports a
     * master key that does not match the one recorded. Best-effort: a problem here
     * is logged, never fatal — the DEK paths report the same problem with the
     * recovery options when they meet it.
     */
    private void reconcileKekCheck() {
        try {
            String check = persistence.getMetaValue(KEK_CHECK_META_KEY);
            if (check == null) {
                // Recorded only when this KEK demonstrably is the vault's KEK. Pinning a
                // mistyped master key as the reference would make the correct one look
                // wrong at the next restart.
                List<EncryptedDek> deks = persistence.listAllDeks();
                if (deks.isEmpty() || deks.stream().anyMatch(dek -> opensWith(dek, kek))) {
                    persistence.putMetaValueIfAbsent(KEK_CHECK_META_KEY, kekCheckValue(kek));
                } else {
                    LOGGER.error("[VAULT] The configured EDDI_VAULT_MASTER_KEY opens none of the stored DEKs. Every secret operation will fail "
                            + "until the original master key is restored.");
                }
                return;
            }
            if (kekCheckOpens(check, kek)) {
                return;
            }
            byte[] fallback = fallbackKek;
            if (fallback != null && kekCheckOpens(check, fallback)) {
                // An interrupted legacy-salt rotation announced the new KEK — this node's
                // master key with the PENDING salt — and then stopped. That KEK is the
                // vault's KEK now, so new DEKs must be wrapped with it. The DEKs the
                // rotation did not reach are still under the PREVIOUS master key, which
                // this node does not have; they fail with a message that says to re-run
                // the rotation. The legacy-salt derivation kept as the fallback opens
                // nothing that exists in that scenario; it is kept only so a DEK some
                // replica wrapped under it before restarting still opens.
                this.fallbackKek = this.kek;
                this.kek = fallback;
                LOGGER.warn("[VAULT] Using the KEK of an unfinished KEK rotation. Re-run POST /secretstore/secrets/admin/rotate-kek with the "
                        + "same old and new master keys to complete it.");
                return;
            }
            if (persistence.listAllDeks().isEmpty()) {
                // No DEK exists anywhere, so no key the check could be protecting: nothing
                // is stranded by adopting this node's key. This is the "started once with
                // key X, restarted with key Y before storing anything" dev case.
                persistence.setMetaValue(KEK_CHECK_META_KEY, kekCheckValue(kek));
                LOGGER.warn("[VAULT] The vault holds no DEKs; adopted the configured EDDI_VAULT_MASTER_KEY as its master key.");
                return;
            }
            LOGGER.error("[VAULT] The configured EDDI_VAULT_MASTER_KEY is not the vault's current master key (a KEK rotation has run since "
                    + "it was set, or it was mistyped). New DEKs will not be created on this node until it is restarted with the current key. "
                    + "If the previous master key is lost for good, POST /secretstore/secrets/admin/adopt-master-key?confirm=true adopts this "
                    + "one — see the Secrets Vault guide, 'Lost master key'.");
        } catch (PersistenceException e) {
            LOGGER.warn("[VAULT] Could not check the master key against the vault's KEK check value: " + e.getMessage());
        }
    }

    private static String kekCheckValue(byte[] candidateKek) {
        EnvelopeCrypto.EncryptionResult enc = EnvelopeCrypto.encrypt(KEK_CHECK_PLAINTEXT, candidateKek, KEK_CHECK_AAD);
        // The IV is Base64 and never contains a colon, so the first colon separates it
        // from the ciphertext, whose own "a1:" marker follows.
        return enc.iv() + ":" + enc.ciphertext();
    }

    private static boolean kekCheckOpens(String checkValue, byte[] candidateKek) {
        int separator = checkValue.indexOf(':');
        if (separator <= 0) {
            return false;
        }
        try {
            return KEK_CHECK_PLAINTEXT.equals(
                    EnvelopeCrypto.decrypt(checkValue.substring(separator + 1), checkValue.substring(0, separator), candidateKek, KEK_CHECK_AAD));
        } catch (EnvelopeCrypto.CryptoException e) {
            return false;
        }
    }

    /**
     * The associated data a secret's ciphertext is bound to: the row it is stored
     * in. Length-prefixed so no pair of tenant and key names can spell another
     * pair's binding.
     */
    static String secretAad(String tenantId, String keyName) {
        return "eddi-secret|v1|" + tenantId.length() + ":" + tenantId + "|" + keyName;
    }

    /** The associated data a wrapped DEK is bound to: its tenant and generation. */
    static String dekAad(String tenantId, int generation) {
        return "eddi-dek|v1|" + tenantId.length() + ":" + tenantId + "|" + generation;
    }

    private void ensureAvailable() throws SecretProviderException {
        if (!available) {
            throw new SecretProviderException("Secrets Vault is not available. Set EDDI_VAULT_MASTER_KEY environment variable.");
        }
    }
}
