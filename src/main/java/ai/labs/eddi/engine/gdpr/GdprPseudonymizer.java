/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.gdpr;

import ai.labs.eddi.engine.audit.AuditHmac;
import ai.labs.eddi.engine.audit.AuditLedgerService;
import ai.labs.eddi.secrets.persistence.ISecretPersistence;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * The pseudonym GDPR erasure writes in place of a user id outside the audit
 * ledger — into the database logs — and uses to name the user in its own log
 * lines and compliance records.
 * <p>
 * It used to be {@link AuditHmac#pseudonymFor}, an unsalted SHA-256: anyone
 * holding a list of candidate ids (every e-mail address of a company, say)
 * could hash them and tell whose log rows had been erased. It is now keyed, in
 * this order of preference:
 * <ol>
 * <li><b>The audit ledger's keyed pseudonym</b> ({@code gdpr-erased:k1:…},
 * {@link AuditLedgerService#pseudonymOf}) whenever the ledger signs — the same
 * value the erased user's ledger rows carry, so logs and ledger still name one
 * person with one token.</li>
 * <li><b>A per-deployment random salt</b> ({@code gdpr-erased:s1:…}) when the
 * ledger has no key (no vault master key and no {@code eddi.audit.hmac-key}).
 * The salt is kept in the vault's metadata store the way
 * {@code VaultSaltManager} keeps the KEK salt — an insert-if-absent under
 * {@value #SALT_META_KEY}, so every replica adopts the first one written — and
 * that store exists whether or not the vault itself is switched on.</li>
 * <li><b>The unsalted hash</b>, only when neither is available (the metadata
 * store cannot be read or written). Logged at WARN; erasure still completes,
 * because an erasure that fails for want of a salt helps nobody.</li>
 * </ol>
 * <p>
 * <b>Migration:</b> none, deliberately. Rows an earlier release pseudonymised
 * keep their unsalted {@code gdpr-erased:<sha256>} value — the raw id is
 * already gone from them, and re-keying would need the raw id back. Erasures
 * from this release on write the keyed form. Re-running an erasure is harmless:
 * it matches on the raw id only, which no erased row still carries.
 * <p>
 * The audit ledger's own fallback rows (erasure step 7b) are <em>not</em> moved
 * to this pseudonym: rows signed under the v3/v4 canonical form sign the
 * unsalted token and stop verifying under any other, and v5 rows already
 * receive the keyed pseudonym inside the audit store.
 */
@ApplicationScoped
public class GdprPseudonymizer {

    private static final Logger LOGGER = Logger.getLogger(GdprPseudonymizer.class);

    /** Metadata key of the per-deployment pseudonym salt. */
    static final String SALT_META_KEY = "gdpr-pseudonym-salt";

    private static final int SALT_LENGTH = 32;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final AuditLedgerService auditLedgerService;
    private final Instance<ISecretPersistence> persistence;
    private volatile byte[] salt;

    @Inject
    public GdprPseudonymizer(AuditLedgerService auditLedgerService, Instance<ISecretPersistence> persistence) {
        this.auditLedgerService = auditLedgerService;
        this.persistence = persistence;
    }

    /**
     * The keyed pseudonym of {@code userId} — see the class comment for which key.
     *
     * @throws IllegalArgumentException
     *             if {@code userId} is null
     */
    public String pseudonymFor(String userId) {
        if (userId == null) {
            throw new IllegalArgumentException("userId must not be null when deriving a GDPR pseudonym");
        }
        if (auditLedgerService != null) {
            String ledgerPseudonym = auditLedgerService.pseudonymOf(userId);
            if (ledgerPseudonym != null && ledgerPseudonym.startsWith(AuditHmac.KEYED_PSEUDONYM_PREFIX)) {
                return ledgerPseudonym;
            }
        }
        byte[] deploymentSalt = deploymentSalt();
        if (deploymentSalt != null) {
            return AuditHmac.saltedPseudonymFor(userId, deploymentSalt);
        }
        return AuditHmac.pseudonymFor(userId);
    }

    /**
     * The persisted salt, created on first use. Null — and retried on the next call
     * — when the metadata store cannot provide one.
     */
    private byte[] deploymentSalt() {
        byte[] cached = salt;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (salt != null) {
                return salt;
            }
            try {
                if (persistence == null || !persistence.isResolvable()) {
                    LOGGER.warn("[GDPR] No metadata store to keep a pseudonym salt in; erasure falls back to the unsalted "
                            + "pseudonym, which a list of candidate user ids can reverse.");
                    return null;
                }
                byte[] candidate = new byte[SALT_LENGTH];
                SECURE_RANDOM.nextBytes(candidate);
                String stored = persistence.get().setMetaValueIfAbsent(SALT_META_KEY, Base64.getEncoder().encodeToString(candidate));
                if (stored == null) {
                    LOGGER.warn("[GDPR] The metadata store kept no pseudonym salt; erasure falls back to the unsalted pseudonym.");
                    return null;
                }
                salt = Base64.getDecoder().decode(stored);
                return salt;
            } catch (RuntimeException e) {
                LOGGER.warnf("[GDPR] Could not read or create the pseudonym salt (%s); erasure falls back to the unsalted "
                        + "pseudonym this time.", e.getMessage());
                return null;
            }
        }
    }
}
