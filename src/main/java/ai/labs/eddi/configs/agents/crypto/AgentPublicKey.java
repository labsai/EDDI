/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.agents.crypto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Versioned public key for an agent. Supports key rotation with overlapping
 * validity windows — both old and new keys are valid during the overlap period.
 *
 * <p>
 * Engine-owned: these entries are written only by the admin-only signing-key
 * endpoints ({@code POST /agentstore/agents/{id}/signing-keys} and its
 * {@code /{keyVersion}/revoke}), never from an agent saved through {@code PUT}.
 * </p>
 *
 * @param version
 *            monotonically increasing key version, 1 to {@link #MAX_VERSION}
 * @param publicKeyB64
 *            Base64-encoded Ed25519 public key
 * @param validFromMs
 *            epoch milliseconds when this key becomes valid
 * @param validUntilMs
 *            epoch milliseconds when this key expires (0 = no expiry)
 * @param revoked
 *            whether an admin revoked the key. A revoked key is valid at no
 *            time, whatever its window says: it may be compromised, so a
 *            signature under it proves nothing, however old it claims to be
 *
 * @since 6.0.0
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AgentPublicKey(
        int version,
        String publicKeyB64,
        long validFromMs,
        long validUntilMs,
        boolean revoked) {

    /**
     * The highest key version the engine generates or accepts as well-formed. Far
     * above any real rotation count, and low enough that "one more" can never
     * overflow and that every consumer can spell the number in a fixed width.
     */
    public static final int MAX_VERSION = 1_000_000;

    /** A key that is not revoked. */
    public AgentPublicKey(int version, String publicKeyB64, long validFromMs, long validUntilMs) {
        this(version, publicKeyB64, validFromMs, validUntilMs, false);
    }

    /**
     * Check if this key is valid at the given epoch milliseconds. A revoked key is
     * valid at no time.
     *
     * @param epochMs
     *            the point in time to check
     * @return true if the key is valid at that time
     */
    public boolean isValidAt(long epochMs) {
        if (revoked || epochMs < validFromMs) {
            return false;
        }
        // validUntilMs == 0 means no expiry
        return validUntilMs == 0 || epochMs <= validUntilMs;
    }

    /**
     * Create a key that is valid indefinitely from now.
     */
    public static AgentPublicKey createCurrent(int version, String publicKeyB64) {
        return new AgentPublicKey(version, publicKeyB64, System.currentTimeMillis(), 0);
    }

    /**
     * Create a copy of this key with an expiry set.
     *
     * @param validUntilMs
     *            when the key expires
     * @return a new key record with the expiry
     */
    public AgentPublicKey withExpiry(long validUntilMs) {
        return new AgentPublicKey(version, publicKeyB64, validFromMs, validUntilMs, revoked);
    }

    /** A copy of this key, revoked. */
    public AgentPublicKey revoke() {
        return new AgentPublicKey(version, publicKeyB64, validFromMs, validUntilMs, true);
    }
}
