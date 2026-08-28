/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.docs;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Keeps {@code docs/configuration-reference.md} in step with the properties the
 * code actually reads — in both directions.
 * <p>
 * Configuration rots differently from prose. A renamed class breaks the build;
 * a renamed property does not, because {@code @ConfigProperty} resolves by
 * string at startup and an operator setting the old name simply gets the
 * default with no warning anywhere. An audit found the failure mode in both
 * directions at once: 61 of 118 properties were documented nowhere at all —
 * including {@code eddi.security.ssrf-protection.enabled}, which is off by
 * default, and every {@code eddi.schedule.*} knob, on a page that otherwise
 * explains scheduling in detail — while {@code hipaa-compliance.md} confidently
 * told operators to configure {@code eddi.usermemory.auto-purge-days}, which
 * has never existed. A compliance checklist naming a property that does nothing
 * is worse than one that says nothing.
 * <p>
 * So this test asserts the reference is exhaustive <em>and</em> that it invents
 * nothing.
 *
 * @see ai.labs.eddi.docs.MetricsDashboardCoverageTest
 */
@DisplayName("configuration reference coverage")
class ConfigurationReferenceCoverageTest {

    private static final Path REFERENCE = Path.of("docs", "configuration-reference.md");
    private static final Path APPLICATION_PROPERTIES = Path.of("src", "main", "resources", "application.properties");

    /** A declared property, optionally under a {@code %profile.} prefix. */
    private static final Pattern DECLARED = Pattern.compile("^(?:%[\\w,]+\\.)?(eddi\\.[\\w.\\-]+)\\s*=", Pattern.MULTILINE);

    /** {@code @ConfigProperty(name = "eddi.…")}. */
    private static final Pattern INJECTED = Pattern.compile("@ConfigProperty\\(\\s*name\\s*=\\s*\"(eddi\\.[\\w.\\-]+)\"");

    /**
     * Programmatic lookup, e.g.
     * {@code config.getOptionalValue("eddi.deployment.env", …)}.
     */
    private static final Pattern LOOKED_UP = Pattern.compile("getOptionalValue\\(\\s*\"(eddi\\.[\\w.\\-]+)\"");

    /**
     * Expression form, e.g. {@code @Scheduled(every =
     * "${eddi.schedule.poll-interval:15s}")}.
     */
    private static final Pattern EXPRESSION = Pattern.compile("\\$\\{(eddi\\.[\\w.\\-]+):");

    /**
     * Any {@code `eddi.…`} code span in the reference — how every property is
     * written there.
     */
    private static final Pattern REFERENCED = Pattern.compile("`(eddi\\.[\\w.\\-]+)`");

    /**
     * Property names the reference may mention without the code reading them.
     * <p>
     * Empty. The one candidate for an entry — a removed property an operator might
     * still have set — belongs in the page that explains the removal (see
     * {@code gdpr-compliance.md} on {@code eddi.audit.retentionDays}), not in a
     * reference table where it reads as something to configure.
     */
    private static final Set<String> ALLOWED_WITHOUT_CODE = Set.of();

    @Test
    @DisplayName("every eddi.* property the code reads is in the configuration reference")
    void referenceIsExhaustive() {
        Path root = repoRoot();
        var declared = collectProperties(root);
        String reference = read(root.resolve(REFERENCE));

        var undocumented = new TreeSet<String>();
        for (var entry : declared.entrySet()) {
            if (!reference.contains("`" + entry.getKey() + "`")) {
                undocumented.add(String.format("%s (read in %s)", entry.getKey(), entry.getValue()));
            }
        }

        assertTrue(undocumented.isEmpty(),
                "These properties change how a deployment behaves and appear nowhere in "
                        + REFERENCE + ". An operator cannot set what is not written down, and a "
                        + "property nobody documents is one nobody reviews:\n  "
                        + String.join("\n  ", undocumented));
    }

    @Test
    @DisplayName("the configuration reference names no property the code does not read")
    void referenceInventsNothing() {
        Path root = repoRoot();
        var declared = collectProperties(root).keySet();

        var invented = new TreeSet<String>();
        Matcher m = REFERENCED.matcher(read(root.resolve(REFERENCE)));
        while (m.find()) {
            String name = m.group(1);
            if (!declared.contains(name) && !ALLOWED_WITHOUT_CODE.contains(name)) {
                invented.add(name);
            }
        }

        assertTrue(invented.isEmpty(),
                "These are documented as configuration but nothing reads them, so setting one is a "
                        + "silent no-op — the operator believes the deployment is configured and it is not:\n  "
                        + String.join("\n  ", invented));
    }

    /** Property name → a short note on where the code reads it. */
    private static TreeMap<String, String> collectProperties(Path root) {
        var found = new TreeMap<String, String>();

        Matcher declared = DECLARED.matcher(read(root.resolve(APPLICATION_PROPERTIES)));
        while (declared.find()) {
            found.putIfAbsent(declared.group(1), "application.properties");
        }

        for (Path file : javaSources(root.resolve(Path.of("src", "main", "java")))) {
            String body = read(file);
            String relative = root.relativize(file).toString().replace('\\', '/');
            record(INJECTED.matcher(body), found, relative);
            record(LOOKED_UP.matcher(body), found, relative);
            record(EXPRESSION.matcher(body), found, relative);
        }

        assertTrue(found.size() > 80,
                "expected to find the project's configuration properties; found only " + found.size()
                        + ". The extraction patterns have probably drifted from how properties are read.");
        return found;
    }

    private static void record(Matcher matcher, TreeMap<String, String> into, String source) {
        while (matcher.find()) {
            into.putIfAbsent(matcher.group(1), source);
        }
    }

    private static Path repoRoot() {
        // Surefire runs with the project basedir as the working directory.
        Path root = Path.of("").toAbsolutePath();
        assertTrue(Files.isRegularFile(root.resolve("pom.xml")),
                "expected the working directory to be the project root, was " + root);
        return root;
    }

    private static List<Path> javaSources(Path base) {
        List<Path> found = new ArrayList<>();
        try {
            Files.walkFileTree(base, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (attrs.isRegularFile() && file.getFileName().toString().endsWith(".java")) {
                        found.add(file);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return found;
    }

    private static String read(Path file) {
        try {
            return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
