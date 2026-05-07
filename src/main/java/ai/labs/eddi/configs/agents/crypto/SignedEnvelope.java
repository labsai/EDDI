/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.agents.crypto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Immutable signed envelope for inter-agent communication.
 * <p>
 * Lifecycle:
 * <ol>
 * <li>{@link #forSigning(String, String, Map)} creates an unsigned envelope
 * with a fresh nonce and timestamp</li>
 * <li>Compute canonical form via {@link JacksonCanonicalizer} for signing</li>
 * <li>{@link #withSignature(String, int)} attaches the signature and key
 * version</li>
 * </ol>
 *
 * @param senderId
 *            the agent ID of the sender
 * @param recipientId
 *            the agent ID of the intended recipient
 * @param payload
 *            the message payload (arbitrary key-value pairs)
 * @param nonce
 *            unique nonce for replay protection
 * @param timestampMs
 *            epoch milliseconds when the envelope was created
 * @param signature
 *            Base64-encoded Ed25519 signature (null before signing)
 * @param keyVersion
 *            the version of the key used for signing (0 before signing)
 *
 * @since 6.0.0
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SignedEnvelope(
        String senderId,
        String recipientId,
        Map<String, Object> payload,
        String nonce,
        long timestampMs,
        String signature,
        int keyVersion) {

    /**
     * Create an unsigned envelope ready for signing.
     *
     * @param senderId
     *            the sender agent ID
     * @param recipientId
     *            the recipient agent ID
     * @param payload
     *            the message payload
     * @return an unsigned envelope with a fresh nonce and current timestamp
     */
    public static SignedEnvelope forSigning(String senderId, String recipientId, Map<String, Object> payload) {
        return new SignedEnvelope(
                senderId,
                recipientId,
                payload,
                UUID.randomUUID().toString(),
                Instant.now().toEpochMilli(),
                null, // no signature yet
                0);
    }

    /**
     * Attach a signature to this envelope.
     *
     * @param signature
     *            Base64-encoded Ed25519 signature
     * @param keyVersion
     *            the version of the key used
     * @return a new envelope with the signature attached
     */
    public SignedEnvelope withSignature(String signature, int keyVersion) {
        return new SignedEnvelope(senderId, recipientId, payload, nonce, timestampMs, signature, keyVersion);
    }

    /**
     * Get the canonical form of this envelope for signing/verification.
     * <p>
     * The canonical form includes all fields except {@code signature} and
     * {@code keyVersion} to prevent circular dependency.
     *
     * @return canonical JSON string
     * @throws JsonProcessingException
     *             if canonicalization fails
     */
    public String canonicalForm() throws JsonProcessingException {
        // Create a copy without signature fields for canonical form
        var forCanon = new SignedEnvelope(senderId, recipientId, payload, nonce, timestampMs, null, 0);
        return JacksonCanonicalizer.canonicalize(forCanon);
    }
}
