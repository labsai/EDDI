/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.audit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Config-hygiene guard for the audit ledger.
 * <p>
 * The audit ledger is <strong>append-only by design</strong>:
 * {@link IAuditStore} deliberately exposes no update or delete operation (GDPR
 * Art. 17(3)(e), EU AI Act Arts. 17/19), so no application-level retention
 * sweep can ever exist. Shipping an {@code eddi.audit.*} property that no code
 * reads is therefore not a harmless leftover — it advertises behaviour that is
 * impossible, which is exactly what {@code eddi.audit.retentionDays} did until
 * it was removed. Its sibling {@code eddi.usermemories.deleteOlderThanDays}
 * <em>is</em> read and drives a real scheduled sweep, which made the asymmetry
 * a silent trap.
 * <p>
 * These tests fail if anyone re-adds an unread {@code eddi.audit.*} property,
 * or adds a delete-capable method to the ledger contract.
 */
class AuditRetentionConfigTest {

    private static final String AUDIT_PREFIX = "eddi.audit.";

    /**
     * A key that production code demonstrably reads (an {@code @ConfigProperty}
     * name in {@code AuditLedgerService}). Used as a vacuity guard: if the source
     * scanner cannot find even this, the scan is broken and every other assertion
     * below would pass for the wrong reason.
     */
    private static final String SENTINEL_READ_KEY = "eddi.audit.enabled";

    @Test
    @DisplayName("every eddi.audit.* key declared in application.properties is read by production code")
    void auditPropertiesDeclaredInApplicationProperties_areAllReadByProductionCode() {
        Set<String> declared = declaredAuditKeys();

        Set<String> probe = new LinkedHashSet<>(declared);
        probe.add(SENTINEL_READ_KEY);
        Set<String> referenced = literalsFoundInMainSources(probe);

        assertTrue(referenced.contains(SENTINEL_READ_KEY),
                "Vacuity guard failed: the src/main/java scan did not find the literal \"" + SENTINEL_READ_KEY
                        + "\", which AuditLedgerService definitely declares. The scanner is broken, so this test proves nothing.");

        List<String> unread = new ArrayList<>(declared);
        unread.removeAll(referenced);

        assertTrue(unread.isEmpty(),
                "application.properties declares eddi.audit.* properties that no code in src/main/java reads: " + unread
                        + ". A property nobody reads is a silent no-op for operators. Either wire it up, or delete it. "
                        + "Note that a retention/purge property specifically CANNOT be wired up: IAuditStore is append-only "
                        + "and exposes no delete operation (EU AI Act Arts. 17/19) — see docs/gdpr-compliance.md.");
    }

    @Test
    @DisplayName("eddi.audit.retentionDays is not declared — it was never readable and can never be implemented")
    void retentionDaysProperty_isNotDeclared() {
        assertFalse(declaredAuditKeys().contains("eddi.audit.retentionDays"),
                "eddi.audit.retentionDays is back in application.properties. It was removed because no code read it "
                        + "and none can: IAuditStore forbids delete operations. Audit archival belongs at the "
                        + "operational/database layer, not in application config.");
    }

    @Test
    @DisplayName("IAuditStore declares no delete-capable method — the ledger stays append-only")
    void auditStore_declaresNoDeleteCapableMethod() {
        List<String> offenders = new ArrayList<>();
        for (Method method : IAuditStore.class.getDeclaredMethods()) {
            String name = method.getName().toLowerCase(Locale.ROOT);
            if (name.startsWith("delete") || name.startsWith("remove") || name.startsWith("purge") || name.startsWith("drop")
                    || name.startsWith("truncate") || name.startsWith("expire")) {
                offenders.add(method.getName());
            }
        }

        assertTrue(offenders.isEmpty(),
                "IAuditStore gained delete-capable method(s) " + offenders
                        + ". The ledger is append-only for EU AI Act Arts. 17/19 compliance; pseudonymizeByUserId is the "
                        + "sole permitted mutation. If this is a deliberate policy change, update docs/gdpr-compliance.md "
                        + "and this test together.");
    }

    // --- helpers ---

    /**
     * All {@code eddi.audit.*} keys declared in {@code application.properties},
     * with any {@code %profile.} prefix stripped.
     */
    private static Set<String> declaredAuditKeys() {
        Path properties = projectRoot().resolve("src").resolve("main").resolve("resources").resolve("application.properties");
        Set<String> keys = new LinkedHashSet<>();
        for (String line : readLines(properties)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("!")) {
                continue;
            }
            int separator = trimmed.indexOf('=');
            if (separator < 0) {
                continue;
            }
            String key = trimmed.substring(0, separator).trim();
            if (key.startsWith("%")) {
                int dot = key.indexOf('.');
                if (dot < 0) {
                    continue;
                }
                key = key.substring(dot + 1);
            }
            if (key.startsWith(AUDIT_PREFIX)) {
                keys.add(key);
            }
        }
        return keys;
    }

    /**
     * Subset of {@code candidates} that appears as a quoted string literal
     * somewhere under {@code src/main/java} — i.e. is actually read by production
     * code (typically via {@code @ConfigProperty(name = "...")}).
     */
    private static Set<String> literalsFoundInMainSources(Set<String> candidates) {
        Path sourceRoot = projectRoot().resolve("src").resolve("main").resolve("java");
        Set<String> found = new LinkedHashSet<>();
        try (Stream<Path> files = Files.walk(sourceRoot)) {
            files.filter(Files::isRegularFile).filter(file -> file.getFileName().toString().endsWith(".java")).forEach(file -> {
                String content = String.join("\n", readLines(file));
                for (String candidate : candidates) {
                    if (content.contains("\"" + candidate + "\"")) {
                        found.add(candidate);
                    }
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to scan " + sourceRoot, e);
        }
        return found;
    }

    private static List<String> readLines(Path path) {
        try {
            return Files.readAllLines(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read " + path, e);
        }
    }

    private static Path projectRoot() {
        Path candidate = Path.of("").toAbsolutePath();
        for (int depth = 0; depth < 6 && candidate != null; depth++) {
            if (Files.isDirectory(candidate.resolve("src").resolve("main").resolve("java"))
                    && Files.isRegularFile(candidate.resolve("src").resolve("main").resolve("resources").resolve("application.properties"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException(
                "Could not locate the project root (a directory containing src/main/java and src/main/resources/application.properties) "
                        + "from working directory " + Path.of("").toAbsolutePath());
    }
}
