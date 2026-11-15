/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl.oversight.samples;

import ai.labs.eddi.engine.audit.AuditHmac;
import ai.labs.eddi.engine.audit.AuditKeyring;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;

/**
 * Decides deterministically and fairly whether a call is sampled:
 * {@code u = HMAC-SHA256(key, agentId|conversationId|stepIndex|callId|toolName)},
 * first 8 bytes as a fraction of 2^64, sampled when {@code u < rate}.
 * <p>
 * The key is derived from the audit signing key under its own label
 * ({@value #LABEL}), so an auditor holding the key can show why a call was or
 * was not sampled, and neither the model nor the agent can predict or steer it.
 * Without a signing key a random per-node key is used: still unpredictable,
 * still uniform, but not reproducible across nodes.
 *
 * @since 6.7.0
 */
@ApplicationScoped
public class SamplingKey {

    private static final Logger LOGGER = Logger.getLogger(SamplingKey.class);

    /** Derivation label of the sampling key. */
    public static final String LABEL = "eddi-hitl-sampling-v1";
    private static final String HMAC = "HmacSHA256";

    private final Instance<AuditKeyring> keyring;
    private volatile byte[] key;

    @Inject
    public SamplingKey(Instance<AuditKeyring> keyring) {
        this.keyring = keyring;
    }

    /** Test seam: a fixed key. */
    public SamplingKey(byte[] key) {
        this.keyring = null;
        this.key = key.clone();
    }

    /** The fraction {@code [0, 1)} of one call. */
    public double fraction(String agentId, String conversationId, int stepIndex, String callId, String toolName) {
        return fraction(key(), agentId + "|" + conversationId + "|" + stepIndex + "|" + callId + "|" + toolName);
    }

    /** Whether a call with this fraction is sampled at {@code rate}. */
    public static boolean sampled(double fraction, double rate) {
        return fraction < rate;
    }

    static double fraction(byte[] key, String input) {
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(key, HMAC));
            byte[] digest = mac.doFinal(input.getBytes(StandardCharsets.UTF_8));
            long bits = 0;
            for (int i = 0; i < 8; i++) {
                bits = (bits << 8) | (digest[i] & 0xFF);
            }
            // Unsigned 64-bit value / 2^64, via the top 53 bits for an exact double.
            return (bits >>> 11) * 0x1.0p-53;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    private byte[] key() {
        byte[] current = key;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (key == null) {
                key = derive();
            }
            return key;
        }
    }

    private byte[] derive() {
        try {
            if (keyring != null && keyring.isResolvable()) {
                AuditHmac.SigningKey signing = keyring.get().signingKey();
                if (signing != null && signing.hmacKey() != null) {
                    Mac mac = Mac.getInstance(HMAC);
                    mac.init(new SecretKeySpec(signing.hmacKey(), HMAC));
                    return mac.doFinal(LABEL.getBytes(StandardCharsets.UTF_8));
                }
            }
        } catch (GeneralSecurityException | RuntimeException e) {
            LOGGER.warnf("Could not derive the sampling key from the audit key (%s); using a random per-node key", e.getMessage());
        }
        byte[] random = new byte[32];
        new SecureRandom().nextBytes(random);
        return random;
    }
}
