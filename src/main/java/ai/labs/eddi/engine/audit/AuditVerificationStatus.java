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
    SIGNING_DISABLED
}
