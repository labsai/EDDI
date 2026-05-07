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
 * @param version
 *            monotonically increasing key version (1-based)
 * @param publicKeyB64
 *            Base64-encoded Ed25519 public key
 * @param validFromMs
 *            epoch milliseconds when this key becomes valid
 * @param validUntilMs
 *            epoch milliseconds when this key expires (0 = no expiry)
 *
 * @since 6.0.0
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AgentPublicKey(
        int version,
        String publicKeyB64,
        long validFromMs,
        long validUntilMs) {

    /**
     * Check if this key is valid at the given epoch milliseconds.
     *
     * @param epochMs
     *            the point in time to check
     * @return true if the key is valid at that time
     */
    public boolean isValidAt(long epochMs) {
        if (epochMs < validFromMs) {
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
        return new AgentPublicKey(version, publicKeyB64, validFromMs, validUntilMs);
    }
}
