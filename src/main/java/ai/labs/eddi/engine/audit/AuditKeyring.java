/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.audit;

import ai.labs.eddi.engine.audit.AuditHmac.SigningKey;
import ai.labs.eddi.secrets.ISecretProvider;
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

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The audit ledger's signing keys: the one new entries are signed with, and
 * every key an older entry may have been signed with.
 * <p>
 * <b>Why it exists.</b> The ledger's HMAC key used to be derived from the vault
 * master key and nothing else. Rotating the master key — the documented KEK
 * rotation — therefore silently changed the audit key too, and after the
 * restart every entry ever written failed verification: an operator following
 * the key-rotation runbook turned the whole historic ledger into "tampered".
 * The key was also the only one there was, so a key rotated on purpose had the
 * same effect.
 * <p>
 * <b>Where the signing key comes from</b>, first match wins:
 * <ol>
 * <li>{@code eddi.audit.hmac-key} ({@code EDDI_AUDIT_HMAC_KEY}) — a secret for
 * the ledger alone, independent of the vault master key. Rotating the master
 * key never touches it.</li>
 * <li>The key <em>pinned in the vault</em>. When the vault is available, the
 * key derived from the master key at the time is stored there, sealed, the
 * first time the ledger starts (an insert-if-absent, so every replica pins the
 * same one). From then on that pinned key is used, whatever the master key is:
 * KEK rotation re-wraps the vault's keys, so the pinned value survives it and
 * the ledger's key does not change.</li>
 * <li>The key derived from the current vault master key — the behaviour before
 * this class existed, and what a deployment signs with until the vault is
 * up.</li>
 * </ol>
 * <p>
 * <b>What verifies.</b> Every v5 entry names the key that signed it (see
 * {@link AuditHmac#V5_PREFIX}), and is checked with exactly that key. The
 * verification set is the signing key plus every other key this deployment can
 * derive: the configured one, the pinned one, the one from the current master
 * key, and any retired keys listed in {@code eddi.audit.hmac-previous-keys}. An
 * entry naming a key outside that set is reported as signed with an unknown key
 * — never as tampered.
 */
@ApplicationScoped
public class AuditKeyring {

    private static final Logger LOGGER = Logger.getLogger(AuditKeyring.class);

    /** Name the master-derived key is pinned under in the vault. */
    static final String PINNED_KEY_NAME = "audit-hmac-key";

    private final Optional<String> masterKey;
    private final Optional<String> configuredKey;
    private final List<String> previousKeys;
    private final Instance<ISecretProvider> secretProvider;

    private SigningKey masterDerived;
    private SigningKey configured;
    private final List<SigningKey> retired = new ArrayList<>();
    private volatile SigningKey pinned;
    private volatile SigningKey signing;
    private volatile List<SigningKey> verification = List.of();
    private boolean initialized;

    @Inject
    public AuditKeyring(@ConfigProperty(name = "eddi.vault.master-key") Optional<String> masterKey,
            @ConfigProperty(name = "eddi.audit.hmac-key") Optional<String> configuredKey,
            @ConfigProperty(name = "eddi.audit.hmac-previous-keys") Optional<List<String>> previousKeys,
            Instance<ISecretProvider> secretProvider) {
        this.masterKey = masterKey.filter(key -> !key.isBlank());
        this.configuredKey = configuredKey.filter(key -> !key.isBlank());
        this.previousKeys = previousKeys.map(keys -> keys.stream().filter(key -> key != null && !key.isBlank()).toList()).orElse(List.of());
        this.secretProvider = secretProvider;
    }

    /**
     * A keyring derived from a master key alone, with no vault to pin in — the
     * shape every deployment had before this class, and what a service built
     * outside CDI uses.
     */
    public static AuditKeyring fromMasterKey(String masterKey) {
        AuditKeyring keyring = new AuditKeyring(Optional.ofNullable(masterKey), Optional.empty(), Optional.empty(), null);
        keyring.initialize();
        return keyring;
    }

    /**
     * Derives every configured key. PBKDF2 at 600,000 iterations per key, so this
     * runs once.
     */
    @PostConstruct
    synchronized void initialize() {
        if (initialized) {
            return;
        }
        masterDerived = masterKey.map(key -> AuditHmac.signingKey(AuditHmac.deriveHmacKey(key))).orElse(null);
        configured = configuredKey.map(key -> AuditHmac.signingKey(AuditHmac.deriveHmacKey(key))).orElse(null);
        for (String previous : previousKeys) {
            retired.add(AuditHmac.signingKey(AuditHmac.deriveHmacKey(previous)));
        }
        signing = configured != null ? configured : masterDerived;
        rebuildVerificationSet();
        initialized = true;
        if (configured != null) {
            LOGGER.infof("Audit Ledger: signing with the independent audit key (eddi.audit.hmac-key, key id %s).", configured.id());
        }
    }

    /**
     * Pins the master-derived key in the vault, or adopts the one pinned earlier.
     * <p>
     * Runs after the vault's own startup observer, which is what makes the vault's
     * availability settled here. Best-effort: when the vault is unavailable, or the
     * pin cannot be read, the ledger keeps signing with the master-derived key —
     * the entries it signs name that key, so they stay verifiable for as long as
     * the master key does not change.
     */
    void onStartup(@Observes
    @Priority(Interceptor.Priority.APPLICATION + 100) StartupEvent event) {
        pinWithVault();
    }

    void pinWithVault() {
        initialize();
        if (masterDerived == null || secretProvider == null || secretProvider.isUnsatisfied()) {
            return;
        }
        try {
            ISecretProvider provider = secretProvider.get();
            if (!provider.isAvailable()) {
                return;
            }
            String stored = provider.pinSystemValue(PINNED_KEY_NAME, Base64.getEncoder().encodeToString(masterDerived.hmacKey()));
            SigningKey pinnedKey = AuditHmac.signingKey(Base64.getDecoder().decode(stored));
            synchronized (this) {
                pinned = pinnedKey;
                if (configured == null) {
                    signing = pinnedKey;
                }
                rebuildVerificationSet();
            }
            if (!pinnedKey.id().equals(masterDerived.id())) {
                LOGGER.infof("Audit Ledger: using the audit key pinned in the vault (key id %s); the vault master key has been rotated since "
                        + "it was pinned, and the ledger's key deliberately has not.", pinnedKey.id());
            }
        } catch (Exception e) {
            LOGGER.warnf("Audit Ledger: could not pin the audit key in the vault (%s). Signing with the key derived from the current "
                    + "master key, id %s; entries signed now name it, so they stay verifiable while that master key is in use.",
                    e.getMessage(), masterDerived.id());
        }
    }

    private void rebuildVerificationSet() {
        Map<String, SigningKey> byId = new LinkedHashMap<>();
        for (SigningKey key : new SigningKey[]{signing, configured, pinned, masterDerived}) {
            if (key != null) {
                byId.putIfAbsent(key.id(), key);
            }
        }
        for (SigningKey key : retired) {
            byId.putIfAbsent(key.id(), key);
        }
        verification = List.copyOf(byId.values());
    }

    /** The key new entries are signed with, or null when signing is disabled. */
    public SigningKey signingKey() {
        initialize();
        return signing;
    }

    /** Every key an entry may be verified with, the signing key first. */
    public List<SigningKey> verificationKeys() {
        initialize();
        return verification;
    }

    /**
     * The keyed pseudonym of {@code userId} under every verification key, by key id
     * — what GDPR erasure writes into the v5 rows each key signed, so that erasing
     * a user leaves those signatures intact.
     */
    public Map<String, String> keyedPseudonymsFor(String userId) {
        Map<String, String> pseudonyms = new LinkedHashMap<>();
        for (SigningKey key : verificationKeys()) {
            pseudonyms.put(key.id(), AuditHmac.keyedPseudonymFor(userId, key.pseudonymKey()));
        }
        return pseudonyms;
    }
}
