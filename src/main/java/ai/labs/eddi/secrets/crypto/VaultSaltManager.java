/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.secrets.crypto;

import ai.labs.eddi.secrets.persistence.ISecretPersistence;
import ai.labs.eddi.secrets.persistence.PersistenceException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Manages a per-deployment cryptographic salt for KEK derivation.
 * <p>
 * On first boot, generates a 16-byte random salt and persists it to the
 * vault-meta collection. On subsequent boots, loads the existing salt. This
 * ensures each EDDI deployment uses a unique salt, preventing cross-deployment
 * rainbow table attacks on the KEK.
 * <p>
 * For backward compatibility, when no persisted salt is found and the vault
 * already contains DEKs (i.e., this is an upgrade from pre-6.0.2), the legacy
 * fixed salt is used and a migration warning is logged.
 * <p>
 * <b>Every replica must derive its KEK from the same salt.</b> A replica that
 * derives it from any other salt wraps DEKs nobody else can open, and that
 * nobody — including itself, after a restart — will ever open again. Two rules
 * follow, and both used to be broken:
 * <ul>
 * <li>A new salt is created with an insert-if-absent that hands back the
 * winner, so two replicas booting against an empty database agree on one salt
 * instead of each keeping its own.</li>
 * <li>A salt that cannot be read is a failed start, never a quiet fall-back to
 * the legacy salt. On a deployment that already has a random salt, the legacy
 * one is simply the wrong key.</li>
 * </ul>
 *
 * @since 6.0.2
 */
@ApplicationScoped
public class VaultSaltManager {

    private static final Logger LOGGER = Logger.getLogger(VaultSaltManager.class);

    /** Key used to store/retrieve the salt in the persistence layer. */
    static final String SALT_META_KEY = "vault-kek-salt";

    /**
     * The salt a legacy-salt KEK rotation is migrating to, persisted
     * <em>before</em> any DEK is re-wrapped under it.
     * <p>
     * It used to exist only in memory until the rotation finished. A rotation that
     * re-wrapped some DEKs and then failed — or crashed — left them wrapped under a
     * KEK derived from a salt nobody had written down: unrecoverable, even with
     * both master keys in hand. Persisting it first means a retry derives the very
     * same KEK and finishes the job.
     */
    static final String PENDING_SALT_META_KEY = "vault-kek-salt-pending";

    /** Salt length in bytes — 16 bytes (128 bits) is the NIST recommendation. */
    private static final int SALT_LENGTH = 16;

    private static final byte[] LEGACY_SALT = "eddi-vault-kek-v1".getBytes(StandardCharsets.UTF_8);

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final ISecretPersistence persistence;

    /** The active salt — either random (new deployment) or legacy (upgrade). */
    private volatile byte[] activeSalt;

    /** True if this deployment is using the legacy fixed salt. */
    private volatile boolean usingLegacySalt = false;

    @Inject
    public VaultSaltManager(ISecretPersistence persistence) {
        this.persistence = persistence;
    }

    /**
     * Initializes the salt. Called by VaultSecretProvider during startup, ONLY when
     * the vault is enabled (master key is set).
     * <p>
     * Logic:
     * <ol>
     * <li>Try to load persisted salt from DB</li>
     * <li>If found → use it</li>
     * <li>If not found AND no DEKs exist → fresh deployment → generate a random
     * salt and insert it if still absent, adopting whichever salt won</li>
     * <li>If not found BUT DEKs exist → upgrade from pre-6.0.2 → use legacy salt +
     * warn</li>
     * </ol>
     *
     * @throws IllegalStateException
     *             if the salt cannot be read or written. Deliberately fatal: every
     *             fall-back available here derives a KEK that may not be the one
     *             the stored DEKs are wrapped with
     */
    public void initialize() {
        try {
            String existingSalt = persistence.getMetaValue(SALT_META_KEY);
            if (existingSalt != null) {
                adopt(existingSalt);
                LOGGER.info("[VAULT] Per-deployment salt loaded (" + activeSalt.length + " bytes).");
                return;
            }

            // No salt persisted yet — check if this is a fresh install or an upgrade
            boolean hasDeks = !persistence.listAllDeks().isEmpty();

            // Read again AFTER listing the DEKs. A replica on a fresh database writes its
            // salt before it wraps its first DEK, so if the listing saw a DEK that
            // replica's salt is already visible now — and without this second read a
            // replica that booted a moment later would mistake a random-salt
            // deployment for a legacy one.
            existingSalt = persistence.getMetaValue(SALT_META_KEY);
            if (existingSalt != null) {
                adopt(existingSalt);
                LOGGER.info("[VAULT] Per-deployment salt loaded (" + activeSalt.length + " bytes).");
                return;
            }

            if (hasDeks) {
                // Upgrade: DEKs exist but were encrypted with the legacy fixed salt.
                // Use legacy salt for backward compatibility — do NOT generate a new one
                // or we'd fail to decrypt existing DEKs.
                this.activeSalt = LEGACY_SALT.clone();
                this.usingLegacySalt = true;
                LOGGER.warn("[VAULT] ⚠️  Existing DEKs found but no per-deployment salt. "
                        + "Using legacy fixed salt for backward compatibility. "
                        + "Run the KEK migration to generate a per-deployment salt.");
                if (persistence.getMetaValue(PENDING_SALT_META_KEY) != null) {
                    LOGGER.warn("[VAULT] A KEK rotation that was migrating to a per-deployment salt did not finish. Some DEKs may already be "
                            + "wrapped under the new master key. Re-run POST /secretstore/secrets/admin/rotate-kek with the same old and new "
                            + "master keys to complete it.");
                }
            } else {
                // Fresh deployment — generate a random salt, but keep whichever salt the
                // first replica managed to store.
                String candidate = Base64.getEncoder().encodeToString(randomSalt());
                String stored = persistence.putMetaValueIfAbsent(SALT_META_KEY, candidate);
                if (stored == null) {
                    throw new IllegalStateException("[VAULT] The secret persistence layer has no metadata store, so a per-deployment salt "
                            + "cannot be kept. Refusing to derive a KEK from a salt that would be lost at the next restart.");
                }
                adopt(stored);
                LOGGER.info(candidate.equals(stored)
                        ? "[VAULT] Fresh deployment — generated per-deployment salt (" + SALT_LENGTH + " bytes)."
                        : "[VAULT] Fresh deployment — another replica generated the per-deployment salt first; using it.");
            }
        } catch (PersistenceException e) {
            // Fail the start. Falling back to the legacy salt here used to be the
            // "safe" choice, and on any deployment that already has a random salt it
            // derived the wrong KEK: every DEK created until the next restart was
            // wrapped under a key the rest of the cluster could not open.
            throw new IllegalStateException("[VAULT] Could not read or create the per-deployment KEK salt: " + e.getMessage()
                    + ". The vault cannot start without it — check the database connection and restart.", e);
        }
    }

    private void adopt(String encodedSalt) {
        this.activeSalt = Base64.getDecoder().decode(encodedSalt);
        this.usingLegacySalt = false;
    }

    private static byte[] randomSalt() {
        byte[] salt = new byte[SALT_LENGTH];
        SECURE_RANDOM.nextBytes(salt);
        return salt;
    }

    /**
     * Returns the active salt for KEK derivation. Must be called AFTER
     * {@link #initialize()}.
     *
     * @return the salt bytes
     * @throws IllegalStateException
     *             if called before initialization
     */
    public byte[] getSalt() {
        byte[] salt = activeSalt;
        if (salt == null) {
            throw new IllegalStateException("VaultSaltManager has not been initialized. Call initialize() first.");
        }
        return salt.clone();
    }

    /**
     * The salt an unfinished legacy-salt migration left behind, or null when there
     * is none.
     *
     * @throws PersistenceException
     *             if it cannot be read
     */
    public byte[] getPendingSalt() {
        String pending = persistence.getMetaValue(PENDING_SALT_META_KEY);
        return pending != null ? Base64.getDecoder().decode(pending) : null;
    }

    /**
     * The salt a legacy-salt KEK rotation will migrate to, persisted before it is
     * used.
     * <p>
     * Reuses a pending salt an interrupted rotation already persisted — which is
     * what makes a retry derive the same new KEK the interrupted run re-wrapped
     * some DEKs under — and otherwise writes a fresh one, if still absent, so two
     * concurrent rotations agree on it too.
     *
     * @throws PersistenceException
     *             if it cannot be read or written; nothing has been re-wrapped yet
     *             at that point, so the rotation can simply be refused
     */
    public byte[] reservePendingSalt() {
        String stored = persistence.putMetaValueIfAbsent(PENDING_SALT_META_KEY, Base64.getEncoder().encodeToString(randomSalt()));
        if (stored == null) {
            throw new PersistenceException("The secret persistence layer has no metadata store; a salt migration cannot be made safe.");
        }
        return Base64.getDecoder().decode(stored);
    }

    /**
     * Removes the pending salt a legacy-salt rotation reserved, for a rotation that
     * was refused before it wrapped anything under it.
     * <p>
     * Only for a reservation the refused run created itself: a pending salt left by
     * an earlier, interrupted run may already have DEKs wrapped under it and must
     * stay. Left behind, a fresh one made every later boot and decrypt failure
     * report an unfinished rotation that never started.
     *
     * @throws PersistenceException
     *             if it cannot be deleted
     */
    public void discardPendingSalt() {
        persistence.deleteMetaValue(PENDING_SALT_META_KEY);
    }

    /**
     * Migrates from the legacy salt to {@code newSalt}: persists it as the
     * deployment's salt, clears the pending marker and updates the in-memory state.
     * <p>
     * Called by {@code VaultSecretProvider.rotateKek()} after every DEK has been
     * re-wrapped under the KEK derived from {@code newSalt} — which
     * {@link #reservePendingSalt()} persisted before the first of those writes, so
     * a failure here leaves nothing unrecoverable: re-running the rotation finds
     * every DEK already on the new KEK and only has this step left to do.
     *
     * @param newSalt
     *            the new salt to activate (must be at least 8 bytes)
     * @throws PersistenceException
     *             if the salt cannot be persisted
     * @throws IllegalStateException
     *             if a different salt was already persisted — a concurrent rotation
     *             finished first with another salt
     */
    public void migrateSalt(byte[] newSalt) {
        if (newSalt == null || newSalt.length < 8) {
            throw new IllegalArgumentException("Salt must be at least 8 bytes");
        }
        String encoded = Base64.getEncoder().encodeToString(newSalt);
        String stored = persistence.putMetaValueIfAbsent(SALT_META_KEY, encoded);
        if (stored != null && !stored.equals(encoded)) {
            throw new IllegalStateException("[VAULT] A different per-deployment salt was persisted while this KEK rotation was running. "
                    + "Another rotation finished first; restart every replica and verify the vault before rotating again.");
        }
        persistence.deleteMetaValue(PENDING_SALT_META_KEY);
        this.activeSalt = newSalt.clone();
        this.usingLegacySalt = false;
        LOGGER.info("[VAULT] Salt migration complete — per-deployment random salt activated (" + newSalt.length + " bytes).");
    }

    /**
     * Returns true if this deployment is using the legacy fixed salt (pre-6.0.2
     * backward compatibility mode).
     */
    public boolean isUsingLegacySalt() {
        return usingLegacySalt;
    }
}
