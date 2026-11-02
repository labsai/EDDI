/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Keeps {@code docs/cryptography.md} true: every cryptographic algorithm the
 * production code asks the JCA for must be one the inventory lists.
 * <p>
 * The post-quantum migration depends on knowing exactly where public-key
 * cryptography is used, and the only cheap way to keep that knowledge current
 * is to fail the build when it changes. So this scans {@code src/main/java} for
 * {@code getInstance(...)} on the JCA engine classes and for
 * {@code new SecretKeySpec(..., alg)}, resolves the algorithm argument — a
 * string literal, or a {@code static final String} constant declared in the
 * same file — and checks it against {@link #ALLOWED}.
 * <p>
 * It fails on:
 * <ul>
 * <li>an algorithm not in {@link #ALLOWED} — add it to the inventory doc and
 * here, deliberately;</li>
 * <li>an algorithm argument it cannot resolve (a local variable, a method call,
 * a constant from another file) — an inventory that can be bypassed by moving a
 * string into a variable guards nothing;</li>
 * <li>a provider argument ({@code getInstance(alg, "BC")}) — EDDI uses the JDK
 * providers; BouncyCastle is on the classpath only transitively;</li>
 * <li>an {@link #ALLOWED} entry no longer used, or one the inventory doc does
 * not name — so the list cannot drift away from the code or the doc.</li>
 * </ul>
 * TLS key exchange is not visible here — it is negotiated by JSSE and the TLS
 * stack's configuration, not chosen in code — and is covered in the doc.
 */
@DisplayName("crypto inventory (docs/cryptography.md)")
class CryptoInventoryTest {

    private static final String INVENTORY_DOC = "docs/cryptography.md";

    /**
     * Allowed algorithm per JCA engine class. Changing an entry is a deliberate act
     * that must be mirrored in {@value #INVENTORY_DOC}.
     */
    private static final Map<String, Set<String>> ALLOWED = Map.of(
            "Cipher", Set.of("AES/GCM/NoPadding"),
            "KeyGenerator", Set.of("AES"),
            "SecretKeySpec", Set.of("AES", "HmacSHA256"),
            "SecretKeyFactory", Set.of("PBKDF2WithHmacSHA256"),
            "Mac", Set.of("HmacSHA256"),
            "MessageDigest", Set.of("SHA-256"),
            "Signature", Set.of("Ed25519", "SHA256withRSA"),
            "KeyPairGenerator", Set.of("Ed25519"),
            "KeyFactory", Set.of("Ed25519", "RSA"),
            "SSLContext", Set.of("TLS"));

    /**
     * Engine classes whose algorithm argument must be one of {@link #ALLOWED}.
     * {@code TrustManagerFactory}, {@code KeyManagerFactory} and {@code KeyStore}
     * are left out on purpose: they name trust-store formats and PKIX algorithms,
     * not the primitives the inventory is about.
     */
    private static final Pattern GET_INSTANCE = Pattern.compile(
            "\\b(Cipher|Signature|KeyPairGenerator|KeyFactory|Mac|SecretKeyFactory|KeyAgreement|KEM|MessageDigest|KeyGenerator|SSLContext)"
                    + "\\s*\\.\\s*getInstance\\s*\\(");

    private static final Pattern NEW_SECRET_KEY_SPEC = Pattern.compile("\\bnew\\s+SecretKeySpec\\s*\\(");

    private static final Pattern STRING_LITERAL = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"");

    /**
     * A bare identifier only. A qualified reference such as {@code Other.ALGO}
     * names a constant in another class, which this file's constants cannot
     * resolve; looking up its last segment here could pick up an unrelated
     * same-named constant, so it is reported as unresolvable instead.
     */
    private static final Pattern IDENTIFIER = Pattern.compile("([A-Za-z_][A-Za-z0-9_]*)");

    /** One resolved use: engine class, algorithm, and where it is. */
    record Use(String engine, String algorithm, String location) {
    }

    @Test
    @DisplayName("every JCA algorithm in src/main/java is in the inventory, and every inventory entry is still used")
    void inventoryMatchesCode() throws IOException {
        Path root = repoRoot();
        List<Use> uses = new ArrayList<>();
        List<String> offenders = new ArrayList<>();
        scan(root, uses, offenders);

        // Not vacuous: the vault, the audit ledger and agent signing all live here.
        assertTrue(uses.size() >= 10, "expected the scan to find the existing crypto call sites, found " + uses);

        for (Use use : uses) {
            Set<String> allowed = ALLOWED.getOrDefault(use.engine(), Set.of());
            if (!allowed.contains(use.algorithm())) {
                offenders.add(use.location() + "  " + use.engine() + " \"" + use.algorithm()
                        + "\" is not in the crypto inventory");
            }
        }

        Map<String, Set<String>> used = new TreeMap<>();
        uses.forEach(u -> used.computeIfAbsent(u.engine(), k -> new TreeSet<>()).add(u.algorithm()));
        ALLOWED.forEach((engine, algorithms) -> algorithms.forEach(alg -> {
            if (!used.getOrDefault(engine, Set.of()).contains(alg)) {
                offenders.add("ALLOWED lists " + engine + " \"" + alg + "\" but nothing in src/main/java uses it - remove it here and in "
                        + INVENTORY_DOC);
            }
        }));

        assertEquals(List.of(), offenders,
                "Cryptographic algorithms must be listed in " + INVENTORY_DOC + " and in CryptoInventoryTest.ALLOWED. "
                        + "Pass the algorithm as a literal or a static final String constant in the same file, and use the JDK "
                        + "providers.\n  " + String.join("\n  ", offenders));
    }

    @Test
    @DisplayName("the inventory doc names every allowed algorithm")
    void docNamesEveryAllowedAlgorithm() throws IOException {
        Path doc = repoRoot().resolve(INVENTORY_DOC);
        assertTrue(Files.isRegularFile(doc), INVENTORY_DOC + " is missing");
        String text = Files.readString(doc, StandardCharsets.UTF_8);

        List<String> missing = ALLOWED.values().stream()
                .flatMap(Set::stream)
                .distinct()
                .filter(alg -> !text.contains(alg))
                .sorted()
                .toList();
        assertEquals(List.of(), missing, INVENTORY_DOC + " must name every algorithm CryptoInventoryTest allows");
    }

    private static void scan(Path root, List<Use> uses, List<String> offenders) throws IOException {
        try (Stream<Path> paths = Files.walk(root.resolve("src/main/java"))) {
            for (Path file : paths.filter(p -> p.toString().endsWith(".java")).toList()) {
                String relative = root.relativize(file).toString().replace('\\', '/');
                String code = stripComments(Files.readString(file, StandardCharsets.UTF_8));
                Map<String, String> constants = stringConstants(code);

                Matcher m = GET_INSTANCE.matcher(code);
                while (m.find()) {
                    List<String> args = arguments(code, m.end());
                    String location = relative + ":" + lineOf(code, m.start());
                    if (args.size() > 1) {
                        offenders.add(location + "  " + m.group(1) + ".getInstance pins a provider (" + args.get(1) + ")");
                    }
                    record(m.group(1), args.isEmpty() ? "" : args.get(0), constants, location, uses, offenders);
                }

                Matcher s = NEW_SECRET_KEY_SPEC.matcher(code);
                while (s.find()) {
                    List<String> args = arguments(code, s.end());
                    String location = relative + ":" + lineOf(code, s.start());
                    // SecretKeySpec(byte[] key, String alg) or (byte[] key, int offset, int len,
                    // String alg)
                    record("SecretKeySpec", args.isEmpty() ? "" : args.get(args.size() - 1), constants, location, uses, offenders);
                }
            }
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    private static void record(String engine, String argument, Map<String, String> constants, String location,
                               List<Use> uses, List<String> offenders) {
        String algorithm = resolve(argument, constants);
        if (algorithm == null) {
            offenders.add(location + "  " + engine + " algorithm argument '" + argument
                    + "' cannot be resolved - use a literal or a static final String in this file");
        } else {
            uses.add(new Use(engine, algorithm, location));
        }
    }

    /** A literal's value, or a same-file {@code static final String}'s value. */
    private static String resolve(String argument, Map<String, String> constants) {
        Matcher literal = STRING_LITERAL.matcher(argument);
        if (literal.matches()) {
            return literal.group(1);
        }
        Matcher identifier = IDENTIFIER.matcher(argument);
        if (identifier.matches()) {
            return constants.get(identifier.group(1));
        }
        return null;
    }

    private static Map<String, String> stringConstants(String code) {
        Map<String, String> constants = new TreeMap<>();
        Matcher m = Pattern.compile("\\bstatic\\s+final\\s+String\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*\"((?:[^\"\\\\]|\\\\.)*)\"\\s*;")
                .matcher(code);
        while (m.find()) {
            constants.put(m.group(1), m.group(2));
        }
        return constants;
    }

    /**
     * The top-level, trimmed arguments of the call whose '(' ends at {@code start}.
     */
    private static List<String> arguments(String code, int start) {
        List<String> args = new ArrayList<>();
        int depth = 0;
        StringBuilder current = new StringBuilder();
        for (int i = start; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '"') {
                int j = i + 1;
                while (j < code.length() && code.charAt(j) != '"') {
                    j += code.charAt(j) == '\\' ? 2 : 1;
                }
                current.append(code, i, Math.min(j + 1, code.length()));
                i = j;
                continue;
            }
            if (c == '(' || c == '[' || c == '{') {
                depth++;
            } else if (c == ')' || c == ']' || c == '}') {
                if (depth == 0) {
                    if (!current.toString().isBlank()) {
                        args.add(current.toString().trim());
                    }
                    return args;
                }
                depth--;
            } else if (c == ',' && depth == 0) {
                args.add(current.toString().trim());
                current.setLength(0);
                continue;
            }
            current.append(c);
        }
        return args;
    }

    /** Blanks out comments, keeping string literals and line numbers intact. */
    private static String stripComments(String source) {
        StringBuilder out = new StringBuilder(source.length());
        int i = 0;
        int n = source.length();
        while (i < n) {
            char c = source.charAt(i);
            if (source.startsWith("\"\"\"", i)) {
                int end = source.indexOf("\"\"\"", i + 3);
                int j = end < 0 ? n : end + 3;
                out.append(source, i, j);
                i = j;
            } else if (c == '"') {
                int j = i + 1;
                while (j < n && source.charAt(j) != '"') {
                    j += source.charAt(j) == '\\' ? 2 : 1;
                }
                j = Math.min(j + 1, n);
                out.append(source, i, j);
                i = j;
            } else if (c == '\'') {
                int j = i + 1;
                while (j < n && source.charAt(j) != '\'') {
                    j += source.charAt(j) == '\\' ? 2 : 1;
                }
                j = Math.min(j + 1, n);
                out.append(source, i, j);
                i = j;
            } else if (source.startsWith("//", i)) {
                int j = source.indexOf('\n', i);
                j = j < 0 ? n : j;
                out.append(" ".repeat(j - i));
                i = j;
            } else if (source.startsWith("/*", i)) {
                int j = source.indexOf("*/", i);
                j = j < 0 ? n : j + 2;
                for (int k = i; k < j; k++) {
                    out.append(source.charAt(k) == '\n' ? '\n' : ' ');
                }
                i = j;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    private static int lineOf(String code, int index) {
        int line = 1;
        for (int i = 0; i < index; i++) {
            if (code.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    private static Path repoRoot() {
        Path root = Path.of("").toAbsolutePath();
        assertTrue(Files.isRegularFile(root.resolve("pom.xml")),
                "expected the working directory to be the project root, was " + root);
        return root;
    }
}
