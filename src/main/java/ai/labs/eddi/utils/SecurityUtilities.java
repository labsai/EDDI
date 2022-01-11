package ai.labs.eddi.utils;

import org.apache.commons.codec.digest.DigestUtils;

import javax.security.auth.Subject;
import java.security.Principal;
import java.util.Random;
import java.util.Set;

/**
 * @author ginccc
 */
public class SecurityUtilities {
    private static final int SALT_LENGTH = 64;
    private static final char[] ALLOWED_CHARS = "1234567890abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ".toCharArray();

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
        StringBuilder finalSalt = new StringBuilder();
        int random;

        for (int i = 0; i < length; i++) {
            random = new Random().nextInt(allowedChars.length - 1);
            finalSalt.append(allowedChars[random]);
        }

        return finalSalt.toString();
    }

    public static String calculateHash(String content) {
        return DigestUtils.md5Hex(content);
    }

    public static Principal getPrincipal(Subject subject) {
        if (subject == null) {
            return null;
        }

        Set<Principal> principals = subject.getPrincipals();
        if (principals != null && !principals.isEmpty()) {
            return principals.toArray(new Principal[principals.size()])[0];
        } else {
            return null;
        }
    }
}
