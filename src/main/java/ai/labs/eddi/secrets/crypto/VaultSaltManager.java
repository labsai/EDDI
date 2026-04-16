package ai.labs.eddi.secrets.crypto;

import ai.labs.eddi.secrets.persistence.ISecretPersistence;
import ai.labs.eddi.secrets.persistence.PersistenceException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

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
 *
 * @since 6.0.2
 */
@ApplicationScoped
public class VaultSaltManager {

    private static final Logger LOGGER = Logger.getLogger(VaultSaltManager.class);

    /** Key used to store/retrieve the salt in the persistence layer. */
    static final String SALT_META_KEY = "vault-kek-salt";

    /** Salt length in bytes — 16 bytes (128 bits) is the NIST recommendation. */
    private static final int SALT_LENGTH = 16;

    private final ISecretPersistence persistence;

    /** The active salt — either random (new deployment) or legacy (upgrade). */
    private byte[] activeSalt;

    /** True if this deployment is using the legacy fixed salt. */
    private boolean usingLegacySalt = false;

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
     * <li>If not found AND no DEKs exist → fresh deployment → generate + persist
     * random salt</li>
     * <li>If not found BUT DEKs exist → upgrade from pre-6.0.2 → use legacy salt +
     * warn</li>
     * </ol>
     */
    public void initialize() {
        try {
            String existingSalt = persistence.getMetaValue(SALT_META_KEY);
            if (existingSalt != null) {
                this.activeSalt = Base64.getDecoder().decode(existingSalt);
                LOGGER.info("[VAULT] Per-deployment salt loaded (" + activeSalt.length + " bytes).");
                return;
            }

            // No salt persisted yet — check if this is a fresh install or an upgrade
            boolean hasDeks = !persistence.listAllDeks().isEmpty();

            if (hasDeks) {
                // Upgrade: DEKs exist but were encrypted with the legacy fixed salt.
                // Use legacy salt for backward compatibility — do NOT generate a new one
                // or we'd fail to decrypt existing DEKs.
                this.activeSalt = "eddi-vault-kek-v1".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                this.usingLegacySalt = true;
                LOGGER.warn("[VAULT] ⚠️  Existing DEKs found but no per-deployment salt. "
                        + "Using legacy fixed salt for backward compatibility. "
                        + "Run the KEK migration to generate a per-deployment salt.");
            } else {
                // Fresh deployment — generate and persist a random salt
                byte[] newSalt = new byte[SALT_LENGTH];
                new SecureRandom().nextBytes(newSalt);
                String encoded = Base64.getEncoder().encodeToString(newSalt);
                persistence.setMetaValue(SALT_META_KEY, encoded);
                this.activeSalt = newSalt;
                LOGGER.info("[VAULT] Fresh deployment — generated per-deployment salt (" + SALT_LENGTH + " bytes).");
            }
        } catch (PersistenceException e) {
            // If we can't read/write the salt, fall back to legacy salt and warn
            this.activeSalt = "eddi-vault-kek-v1".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            this.usingLegacySalt = true;
            LOGGER.error("[VAULT] Failed to initialize per-deployment salt — falling back to legacy salt. " + "Error: " + e.getMessage(), e);
        }
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
        if (activeSalt == null) {
            throw new IllegalStateException("VaultSaltManager has not been initialized. Call initialize() first.");
        }
        return activeSalt;
    }

    /**
     * Returns true if this deployment is using the legacy fixed salt (pre-6.0.2
     * backward compatibility mode).
     */
    public boolean isUsingLegacySalt() {
        return usingLegacySalt;
    }
}
