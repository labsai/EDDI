package io.sls.utilities;

import org.apache.commons.codec.binary.Hex;
import org.apache.commons.codec.digest.DigestUtils;

import javax.security.auth.Subject;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.Principal;
import java.util.Random;
import java.util.Set;

/**
 * @author ginccc
 */
public class SecurityUtilities {
    private static final String UTF_8 = "UTF-8";
    private static final int SALT_LENGTH = 64;
    private static final char[] ALLOWED_CHARS = "1234567890abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ".toCharArray();

    public static String hashPassword(String password, String salt) {
        String unencryptedBytes = new StringBuilder().append(salt).append(password).append(salt).toString();

        String encryptedPassword = DigestUtils.sha512Hex(unencryptedBytes);

        return encryptedPassword;
    }

    public static String generateSalt() {
        return generateSalt(SALT_LENGTH);
    }

    public static String generateSalt(int length) {
        return generateSalt(length, ALLOWED_CHARS);
    }

    public static String generateSalt(int length, char[] allowedChars) {
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

    public static Subject createSubject(final String username, String password) {
        Subject subject = new Subject();

        subject.getPrincipals().add(new Principal() {
            @Override
            public String getName() {
                return username;
            }

            @Override
            public String toString() {
                return username;
            }
        });

        subject.getPrivateCredentials().add(password);

        return subject;
    }

    public static String calculateSHA1(String value) throws UnsupportedEncodingException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-1");
        digest.reset();
        digest.update(value.getBytes("UTF-8"));
        return Hex.encodeHexString(digest.digest());
    }

    public static void main(String[] args) throws IOException, NoSuchAlgorithmException {
        BufferedReader rd = new BufferedReader(new InputStreamReader(System.in));
        do {
            System.out.print("pw: ");
            String pw = rd.readLine();

            if ("".equals(pw)) {
                break;
            }
            String salt = generateSalt();
            System.out.println("pw-hashed: " + hashPassword(pw, salt));
            System.out.println("salt: " + salt);
        } while (true);
    }
}
