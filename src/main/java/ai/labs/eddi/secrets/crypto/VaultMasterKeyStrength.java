/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.secrets.crypto;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Startup strength gate for the vault master key (KEK passphrase).
 * <p>
 * The vault previously accepted <em>any</em> non-blank master key: the only
 * length check anywhere was on {@code rotate-kek} (≥ 8 characters), so a
 * deployment could boot with a one-character key or, worse, with the master key
 * that ships in {@code docker-compose.openwebui.yml} for the public demo — a
 * value anyone can read in the repository. Every secret in such a vault is then
 * decryptable by anyone who knows that published passphrase.
 * <p>
 * This class judges a candidate key and returns the reason it is weak, if any.
 * It does not decide what to do about it — {@code VaultSecretProvider} fails
 * startup in production ({@link io.quarkus.runtime.LaunchMode#NORMAL}) and only
 * warns in development and test, mirroring
 * {@link ai.labs.eddi.engine.security.AuthStartupGuard}. Pure and
 * package-visible so the decision is assertable without booting a container.
 *
 * @since 6.5.0
 */
public final class VaultMasterKeyStrength {

    /**
     * Minimum accepted length. Matches the installer's "custom passphrase" floor
     * (16 characters) rather than the far weaker {@code rotate-kek} floor of 8 —
     * eight characters is brute-forceable, and the installer already refuses fewer
     * than sixteen, so a hand-configured deployment should not be able to do what
     * the installer forbids.
     */
    public static final int MIN_LENGTH = 16;

    /**
     * A key with fewer distinct characters than this is treated as low-entropy
     * regardless of length, so {@code aaaaaaaaaaaaaaaaaaaa} does not pass on length
     * alone.
     */
    static final int MIN_DISTINCT_CHARS = 6;

    /**
     * Values that are publicly known and must never protect a real vault. Compared
     * case-insensitively on the trimmed key. The first entry is the exact master
     * key baked into {@code docker-compose.openwebui.yml}; the rest are the obvious
     * placeholders operators reach for.
     */
    static final Set<String> KNOWN_WEAK_KEYS = Set.of("demo-only-vault-master-key-not-for-production-use", "changeme", "change-me", "changemenow",
            "password", "passphrase", "secret", "test", "testing", "dev-passphrase", "your-strong-passphrase", "your-strong-passphrase-here");

    private VaultMasterKeyStrength() {
        // Utility class
    }

    /**
     * The reason {@code masterKey} is too weak to protect a vault, or
     * {@link Optional#empty()} when it is acceptable.
     * <p>
     * A blank key is reported as a weakness too, for completeness, though the
     * provider treats blank as "vault disabled" before this is ever consulted.
     *
     * @param masterKey
     *            the configured master key (may be null)
     * @return a human-readable weakness reason, or empty when the key is strong
     *         enough
     */
    public static Optional<String> weakness(String masterKey) {
        if (masterKey == null || masterKey.isBlank()) {
            return Optional.of("the vault master key is empty");
        }
        String key = masterKey.strip();
        if (KNOWN_WEAK_KEYS.contains(key.toLowerCase(Locale.ROOT))) {
            return Optional.of("the vault master key is a well-known demo/placeholder value that is publicly documented "
                    + "(e.g. the docker-compose.openwebui.yml demo key) — every secret it protects is readable by anyone who knows it");
        }
        if (key.length() < MIN_LENGTH) {
            return Optional.of("the vault master key is only " + key.length() + " characters; at least " + MIN_LENGTH + " are required");
        }
        long distinct = key.chars().distinct().count();
        if (distinct < MIN_DISTINCT_CHARS) {
            return Optional.of("the vault master key uses only " + distinct + " distinct character(s), which is too low-entropy to resist "
                    + "brute force regardless of its length");
        }
        return Optional.empty();
    }
}
