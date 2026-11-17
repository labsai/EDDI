/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health;

import ai.labs.eddi.secrets.persistence.ISecretPersistence;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Hashes business references for agents with {@code healthConfig.hashRefs}:
 * HMAC-SHA256 of {@code name NUL value} under a per-deployment random salt,
 * hex-encoded.
 * <p>
 * The salt is kept in the vault's metadata store the way
 * {@code VaultSaltManager} keeps the KEK salt and {@code GdprPseudonymizer} its
 * pseudonym salt — an insert-if-absent under {@value #SALT_META_KEY}, so every
 * replica agrees on one salt — and is read once per node. Without it (no
 * metadata store, or it cannot be read) {@link #hash} answers {@code null}: the
 * caller then records nothing rather than the plain value (fail closed), and
 * tries again on the next turn. The salt is never exported or logged; a
 * deployment whose metadata store is reset gets a new salt, after which
 * references hashed before no longer match (counted as unmatched events).
 */
@ApplicationScoped
public class HealthRefHasher {

    private static final Logger LOGGER = Logger.getLogger(HealthRefHasher.class);

    /** Metadata key of the per-deployment reference salt. */
    static final String SALT_META_KEY = "health-ref-salt";

    private static final int SALT_LENGTH = 32;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final long RETRY_AFTER_MS = 60_000L;

    private final Instance<ISecretPersistence> persistence;
    private volatile byte[] salt;
    private volatile long lastFailure;

    @Inject
    public HealthRefHasher(Instance<ISecretPersistence> persistence) {
        this.persistence = persistence;
    }

    /** With a fixed salt — for tests. */
    HealthRefHasher(byte[] salt) {
        this.persistence = null;
        this.salt = salt == null ? null : salt.clone();
    }

    /** For tests: a hasher with the given salt. */
    public static HealthRefHasher withSalt(byte[] salt) {
        return new HealthRefHasher(salt);
    }

    /**
     * The hex HMAC of a reference, or {@code null} when no salt is available.
     */
    public String hash(String name, String value) {
        if (name == null || value == null) {
            return null;
        }
        byte[] key = salt();
        if (key == null) {
            return null;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            mac.update(name.getBytes(StandardCharsets.UTF_8));
            mac.update((byte) 0);
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is not available", e);
        }
    }

    private byte[] salt() {
        byte[] cached = salt;
        if (cached != null) {
            return cached;
        }
        if (System.currentTimeMillis() - lastFailure < RETRY_AFTER_MS) {
            return null;
        }
        synchronized (this) {
            if (salt != null) {
                return salt;
            }
            try {
                if (persistence == null || !persistence.isResolvable()) {
                    fail("no metadata store to keep the reference salt in");
                    return null;
                }
                byte[] candidate = new byte[SALT_LENGTH];
                SECURE_RANDOM.nextBytes(candidate);
                String stored = persistence.get().setMetaValueIfAbsent(SALT_META_KEY, Base64.getEncoder().encodeToString(candidate));
                if (stored == null) {
                    fail("the metadata store kept no reference salt");
                    return null;
                }
                salt = Base64.getDecoder().decode(stored);
                return salt;
            } catch (RuntimeException e) {
                fail("the reference salt could not be read or created (" + e.getMessage() + ")");
                return null;
            }
        }
    }

    private void fail(String why) {
        lastFailure = System.currentTimeMillis();
        LOGGER.warnf("Agent Health: %s — hashed business references (healthConfig.hashRefs) are not recorded until it is available", why);
    }
}
