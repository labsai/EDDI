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
import java.util.Locale;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

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

    @Test
    @DisplayName("every EDDI_* environment variable named in the docs is a real property's mapping")
    void documentedEnvironmentVariablesMapToRealProperties() {
        Path root = repoRoot();

        // MicroProfile Config derives the environment variable by uppercasing the
        // property and replacing every character that is not a letter or digit
        // with '_'. BOTH '.' and '-' are replaced.
        var valid = new TreeSet<String>();
        for (String property : collectProperties(root).keySet()) {
            valid.add(property.replaceAll("[^A-Za-z0-9]", "_").toUpperCase(Locale.ROOT));
        }

        var wrong = new TreeSet<String>();
        for (Path file : documentationFiles(root)) {
            String relative = root.relativize(file).toString().replace('\\', '/');
            if (isChangelog(relative)) {
                continue; // historical entries quote whatever was true at the time
            }
            String[] lines = read(file).split("\r?\n", -1);
            for (int i = 0; i < lines.length; i++) {
                Matcher m = ENV_VAR.matcher(lines[i]);
                while (m.find()) {
                    String name = m.group(1);
                    if (valid.contains(name) || DOCUMENTED_NON_PROPERTY_ENV.contains(name)
                            || COUNTER_EXAMPLES.contains(name)) {
                        continue;
                    }
                    wrong.add(String.format("%s:%d %s", relative, i + 1, name));
                }
            }
        }

        assertTrue(wrong.isEmpty(),
                "These EDDI_* names do not map to any property the code reads. Setting one is not an "
                        + "error — the property keeps its default and the service starts normally — so the "
                        + "operator believes the deployment is configured when it is not. The rule is: "
                        + "uppercase, and replace every non-alphanumeric character (both '.' AND '-') with "
                        + "'_'.\n  " + String.join("\n  ", wrong));
    }

    /** An {@code EDDI_*} token, however it is quoted in the prose. */
    private static final Pattern ENV_VAR = Pattern.compile("\\b(EDDI_[A-Z0-9_]+)\\b");

    /**
     * {@code EDDI_*} names that are not Quarkus property mappings at all, and so
     * are outside what this test can check: Compose and installer variables
     * ({@code EDDI_VERSION} selects the image tag in {@code docker-compose.yml}),
     * shell locals in {@code install.sh}, and NATS stream names that merely look
     * like environment variables.
     */
    private static final Set<String> DOCUMENTED_NON_PROPERTY_ENV = Set.of(
            "EDDI_VERSION",
            "EDDI_CONVERSATIONS", "EDDI_DEAD_LETTERS", "EDDI_IT_CONVERSATIONS", "EDDI_IT_DEAD_LETTERS",
            "EDDI_API_KEY", "EDDI_API_URL", "EDDI_URL", "EDDI_PORT", "EDDI_HTTPS_PORT", "EDDI_DOMAIN",
            "EDDI_SCHEME", "EDDI_DIR", "EDDI_CONFIG", "EDDI_NAMESPACE", "EDDI_BRANCH", "EDDI_RELEASE",
            "EDDI_CLI", "EDDI_REPO_ROOT", "EDDI_ALREADY_RUNNING", "EDDI_URI_PATTERN", "EDDI_AUTH",
            "EDDI_DEMO_LLM_API_KEY", "EDDI_DEMO_LLM_MODEL", "EDDI_DEMO_LLM_TYPE");

    /**
     * Names a document quotes deliberately because they do <em>not</em> work.
     * <p>
     * {@code configuration-reference.md} warns that {@code EDDI_VAULT_MASTERKEY} —
     * the dash deleted rather than replaced — binds nothing and leaves the vault
     * inactive, and {@code gdpr-compliance.md} tells operators to remove
     * {@code EDDI_AUDIT_RETENTIONDAYS}, whose property was deleted after it turned
     * out nothing ever read it. Both warnings have to be able to name the thing
     * they are about, so this set is where "documented because it is wrong" lives —
     * it is not a place to park a name you have not checked.
     */
    private static final Set<String> COUNTER_EXAMPLES = Set.of("EDDI_VAULT_MASTERKEY", "EDDI_AUDIT_RETENTIONDAYS");

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

    /**
     * The changelog — the live file or an archive — and nothing that merely starts
     * with the same letters.
     * <p>
     * A plain {@code startsWith("docs/changelog")} prefix would also skip a
     * {@code docs/changelog-notes.md}, an ordinary page that should be checked like
     * any other. Exempting more than intended is the failure mode that matters for
     * an exemption, because the test goes on passing.
     */
    private static boolean isChangelog(String relative) {
        return relative.equals("docs/changelog.md") || relative.startsWith("docs/changelog/");
    }

    /**
     * Every markdown file under {@code docs/}, plus <em>every</em> markdown file at
     * the repository root.
     * <p>
     * Naming {@code README.md} and {@code AGENTS.md} explicitly left
     * {@code PRIVACY.md}, {@code CONTRIBUTING.md}, {@code SECURITY.md} and the rest
     * unchecked — and {@code PRIVACY.md} is a 30 KB operator-facing document,
     * exactly the sort of page that quotes a configuration name. None of them names
     * an {@code EDDI_*} variable today, which is precisely why the allow-list would
     * have gone on looking correct indefinitely.
     */
    private static List<Path> documentationFiles(Path root) {
        List<Path> found = new ArrayList<>(markdownUnder(root.resolve("docs")));
        try (Stream<Path> rootFiles = Files.list(root)) {
            rootFiles.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".md"))
                    .forEach(found::add);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return found;
    }

    private static List<Path> markdownUnder(Path base) {
        List<Path> found = new ArrayList<>();
        if (!Files.isDirectory(base)) {
            return found;
        }
        try {
            Files.walkFileTree(base, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (attrs.isRegularFile() && file.getFileName().toString().endsWith(".md")) {
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
