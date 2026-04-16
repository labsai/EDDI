package ai.labs.eddi.utils;

import org.apache.commons.codec.digest.DigestUtils;

import javax.security.auth.Subject;
import java.security.Principal;
import java.security.SecureRandom;
import java.util.Set;

/**
 * Security utility methods for password hashing and salt generation.
 *
 * @author ginccc
 */
public class SecurityUtilities {
    private static final int SALT_LENGTH = 64;
    private static final char[] ALLOWED_CHARS = "1234567890abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ".toCharArray();

    /**
     * Thread-safe CSPRNG — reused across calls. SecureRandom is thread-safe per its
     * Javadoc, so a single static instance is sufficient.
     */
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    public static String hashPassword(String password, String salt) {
        String unencryptedBytes = salt + password + salt;
        return DigestUtils.sha512Hex(unencryptedBytes);
    }

    public static String generateSalt() {
        return generateSalt(SALT_LENGTH);
    }

    private static String generateSalt(int length) {
        return generateSalt(length, ALLOWED_CHARS);
    }

    private static String generateSalt(int length, char[] allowedChars) {
        StringBuilder finalSalt = new StringBuilder(length);

        for (int i = 0; i < length; i++) {
            // SECURITY FIX: was `new Random().nextInt(length - 1)` which
            // (a) created a new weak PRNG per iteration, and
            // (b) never selected the last character in allowedChars (off-by-one)
            int random = SECURE_RANDOM.nextInt(allowedChars.length);
            finalSalt.append(allowedChars[random]);
        }

        return finalSalt.toString();
    }

    /**
     * Calculates a SHA-256 hex digest of the given content.
     * <p>
     * Note: was MD5 prior to 6.0.2. SHA-256 is preferred for integrity checks — MD5
     * has known collision attacks.
     */
    public static String calculateHash(String content) {
        return DigestUtils.sha256Hex(content);
    }

    public static Principal getPrincipal(Subject subject) {
        if (subject == null) {
            return null;
        }

        Set<Principal> principals = subject.getPrincipals();
        if (principals != null && !principals.isEmpty()) {
            return principals.toArray(new Principal[0])[0];
        } else {
            return null;
        }
    }
}
