/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.audit;

/**
 * Outcome of re-checking one stored audit entry's HMAC.
 *
 * @since 6.2.0
 */
public enum AuditVerificationStatus {

    /** The stored HMAC matches a recomputation over the entry's fields. */
    VALID,

    /**
     * A pre-v4 entry that verified only after the timestamp precision its backend
     * could not store was reconstructed from the signature itself.
     * <p>
     * Integrity is proven exactly as strongly as {@link #VALID} — finding a
     * completion that reproduces the digest without the key is as hard as forging
     * it. Reported separately because it also says something operational: the row
     * predates the v4 canonical form, and only rows written before it need this.
     */
    VALID_RECOVERED,

    /**
     * The stored HMAC does not match — the entry was altered after it was written,
     * or it was signed with a different key.
     */
    INVALID,

    /**
     * The entry carries no HMAC. Written while no vault master key was configured,
     * so it proves nothing.
     */
    UNSIGNED,

    /**
     * This deployment has no signing key, so nothing can be verified. Distinct from
     * {@link #UNSIGNED}: the entry may well be signed, we just cannot check it
     * here.
     */
    SIGNING_DISABLED,

    /**
     * The entry names a key this deployment <em>recorded</em> as having signed
     * entries, and no longer holds — typically a retired audit key that is not
     * listed in {@code eddi.audit.hmac-previous-keys}. The entry cannot be checked
     * here, so it is not proven intact: counted with the invalid entries, and to be
     * treated as unverified until the key is supplied. A key id the deployment
     * never recorded is reported as {@link #INVALID} instead — the id is text in
     * the row, and anyone able to edit the row can write one.
     */
    UNKNOWN_KEY
}
