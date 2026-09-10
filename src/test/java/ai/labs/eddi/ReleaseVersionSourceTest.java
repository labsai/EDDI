/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Enforces AGENTS.md §1: "Versions live in {@code pom.xml} — treat it as the
 * single source of truth."
 * <p>
 * The release version used to be hand-copied into six places outside pom.xml —
 * three properties in {@code application.properties}, the Dockerfile's
 * {@code ARG EDDI_VERSION} default (which feeds the Red Hat {@code version}
 * LABEL), a {@code workflow_dispatch} default, and the {@code @Info} annotation
 * on {@code OpenApiConfig}. A release therefore meant remembering six edits in
 * the right combination, and missing the Dockerfile one shipped a certified
 * image labelled with the previous version while the tag said otherwise.
 * Nothing could catch it: the PR preflight job built the image without passing
 * {@code --build-arg EDDI_VERSION}, so it asserted the stale ARG default was
 * present and correct in every case.
 * <p>
 * This is a plain file sweep — no Quarkus boot, no Docker — so it runs in the
 * ordinary {@code mvn test} pass, which is the only gate a version bump is
 * guaranteed to cross.
 * <p>
 * <b>It reads every file it names.</b> An earlier draft narrated all six
 * sources and then swept three, leaving the two workflows — including the very
 * {@code workflow_dispatch} default that was deleted for this — free to drift
 * back in unnoticed. {@link #SWEPT} now covers both workflows as well, and the
 * certify job's {@code version} input gets its own assertion, because a stale
 * default stops being a literal sweep's business the moment {@code pom.xml} is
 * bumped past it.
 */
@DisplayName("release version single source of truth (AGENTS.md 1)")
class ReleaseVersionSourceTest {

    private static final Path POM = Path.of("pom.xml");
    private static final Path APPLICATION_PROPERTIES = Path.of("src", "main", "resources", "application.properties");
    private static final Path DOCKERFILE = Path.of("src", "main", "docker", "Dockerfile");
    private static final Path OPEN_API_CONFIG = Path.of("src", "main", "java", "ai", "labs", "eddi", "configs", "OpenApiConfig.java");
    private static final Path CI_WORKFLOW = Path.of(".github", "workflows", "ci.yml");
    private static final Path CERTIFY_WORKFLOW = Path.of(".github", "workflows", "redhat-certify.yml");

    /**
     * Every file the literal sweep covers.
     * <p>
     * The workflows are in the list because the drift this class documents included
     * a {@code workflow_dispatch} {@code default: "6.3.0"} in
     * {@code redhat-certify.yml}, deleted on the same branch — and a sweep that did
     * not read the file would have let it be re-added silently, which is the whole
     * failure mode. {@code ci.yml} joins it because the release path derives the
     * version from {@code pom.xml} in three places ({@code Compute Docker tags},
     * the preflight build-arg, the preflight label check) and a literal pasted into
     * any of them is the same bug.
     */
    private static final List<Path> SWEPT = List.of(
            APPLICATION_PROPERTIES, DOCKERFILE, OPEN_API_CONFIG, CI_WORKFLOW, CERTIFY_WORKFLOW);

    /**
     * A {@code version = "6.3.0"} style attribute — the shape an annotation is
     * forced into, because an annotation element can only hold a compile-time
     * constant. Deliberately does not match {@code @since} or
     * {@code @Deprecated(since = ...)}, which name the release a feature landed in
     * and must NOT move when the project version does.
     */
    private static final Pattern VERSION_ATTRIBUTE = Pattern.compile("version\\s*=\\s*\"(\\d+\\.\\d+[^\"]*)\"");

    /** The Maven expression every derived value must go through. */
    private static final String MAVEN_VERSION_EXPRESSION = "${quarkus.application.version}";

    /**
     * Properties that carry the release version and must derive it rather than
     * repeat it.
     */
    private static final List<String> DERIVED_PROPERTIES = List.of(
            "systemRuntime.projectVersion",
            "quarkus.smallrye-openapi.info-version",
            "quarkus.container-image.additional-tags");

    private static String read(Path path) throws IOException {
        assertTrue(Files.isRegularFile(path),
                "expected the working directory to be the project root; " + path.toAbsolutePath() + " not found");
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    private static String projectVersion() throws IOException {
        Matcher m = Pattern.compile("<version>([^<]+)</version>").matcher(read(POM));
        assertTrue(m.find(), "no <version> element in pom.xml");
        return m.group(1);
    }

    @Test
    @DisplayName("application.properties derives every version-bearing value from Maven")
    void applicationPropertiesDerivesTheVersion() throws IOException {
        List<String> lines = read(APPLICATION_PROPERTIES).lines().toList();

        for (String key : DERIVED_PROPERTIES) {
            String assignment = lines.stream()
                    .filter(line -> line.startsWith(key + "="))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("no '" + key + "=' line in " + APPLICATION_PROPERTIES));

            assertEquals(key + "=" + MAVEN_VERSION_EXPRESSION, assignment.trim(),
                    key + " must derive the release version from Maven, not repeat it — pom.xml is the single"
                            + " source of truth (AGENTS.md 1)");
        }
    }

    @Test
    @DisplayName("the Dockerfile's EDDI_VERSION default is a sentinel, not a release")
    void dockerfileVersionArgIsASentinel() throws IOException {
        String argDefault = read(DOCKERFILE).lines()
                .filter(line -> line.startsWith("ARG EDDI_VERSION="))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no 'ARG EDDI_VERSION=' line in " + DOCKERFILE))
                .substring("ARG EDDI_VERSION=".length())
                .trim();

        assertFalse(argDefault.matches("\\d+\\.\\d+.*"),
                "ARG EDDI_VERSION defaults to the release-shaped '" + argDefault + "'. It feeds the Red Hat `version`"
                        + " LABEL, and CI overrides it with --build-arg, so a release-shaped default is only ever"
                        + " visible when the build FORGOT to pass one — exactly the case that must be obvious."
                        + " Use a sentinel such as 'dev'.");
    }

    /**
     * The OpenAPI {@code @Info} block was the sixth copy of the release version,
     * and the only one in Java. It is dead rather than wrong —
     * {@code quarkus.smallrye-openapi.info-version} wins the SmallRye merge — but
     * it is the same drift class, and nothing would have caught it on the next
     * bump: the literal sweep below only reached the properties file and the
     * Dockerfile. {@code Info.version()} declares no default so the element cannot
     * be dropped, hence a sentinel; what this rejects is a <em>release-shaped</em>
     * value coming back.
     */
    @Test
    @DisplayName("the OpenAPI @Info annotation carries no hand-copied version")
    void openApiInfoCarriesNoVersionLiteral() throws IOException {
        Matcher m = VERSION_ATTRIBUTE.matcher(read(OPEN_API_CONFIG));
        String offender = m.find() ? m.group() : null;

        assertNull(offender,
                OPEN_API_CONFIG + " pins the release version in an annotation. An annotation element can only be a"
                        + " compile-time constant, so it can never derive from pom.xml — drop it and let"
                        + " quarkus.smallrye-openapi.info-version supply it (AGENTS.md 1).");
    }

    @Test
    @DisplayName("no swept build file repeats the pom version as a literal")
    void noBuildFileRepeatsThePomVersion() throws IOException {
        String version = projectVersion();
        List<String> offenders = new ArrayList<>();

        for (Path path : SWEPT) {
            List<String> lines = read(path).lines().toList();
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                // Prose may legitimately name a version ("the pre-6.3.0 behavior",
                // "@since 6.3.0" — which pins the release a feature landed in and
                // must NOT move); only live directives are in scope.
                if (isProse(line)) {
                    continue;
                }
                if (line.contains(version)) {
                    offenders.add(path + ":" + (i + 1) + " -> " + line.trim());
                }
            }
        }

        assertEquals(List.of(), offenders,
                "these lines hand-copy the pom version " + version + "; derive it instead ("
                        + MAVEN_VERSION_EXPRESSION + " in properties, --build-arg EDDI_VERSION for the image)");
    }

    /**
     * Lines that describe rather than configure. Properties, Dockerfiles and YAML
     * comment with a hash; Java with slashes or a star.
     * <p>
     * A workflow's {@code description:} counts too: it is the sentence GitHub shows
     * above the input box, so "(e.g. 6.3.0)" there is an operator-facing example of
     * a <em>published tag</em>, not a value the build consumes. The value on such
     * an input is {@code default:}, which is not exempt — that is precisely the
     * line this class exists to keep out of {@code redhat-certify.yml}.
     */
    private static boolean isProse(String line) {
        String stripped = line.stripLeading();
        return stripped.startsWith("#") || stripped.startsWith("//") || stripped.startsWith("*") || stripped.startsWith("/*")
                || stripped.startsWith("description:");
    }

    /**
     * The literal sweep above catches a version pasted into a workflow, but the
     * specific regression it is guarding against — {@code default: "6.3.0"} on the
     * certify job's {@code version} input — only shows up as a literal while
     * {@code pom.xml} still says 6.3.0. Bump the pom and the stale default becomes
     * invisible to a sweep for the CURRENT version, which is exactly the state in
     * which it does damage: dispatching the workflow then certifies the previous
     * release with nothing saying so.
     */
    @Test
    @DisplayName("the certify workflow's version input carries no default at all")
    void certifyWorkflowVersionInputHasNoDefault() throws IOException {
        List<String> lines = read(CERTIFY_WORKFLOW).lines().toList();
        List<String> offenders = new ArrayList<>();

        boolean inVersionInput = false;
        for (String line : lines) {
            String stripped = line.strip();
            if (stripped.equals("version:")) {
                inVersionInput = true;
                continue;
            }
            if (inVersionInput) {
                // The next input key at the same nesting level ends the block.
                if (stripped.endsWith(":") && !stripped.startsWith("#") && !line.startsWith("        ")) {
                    inVersionInput = false;
                } else if (stripped.startsWith("default:")) {
                    offenders.add(stripped);
                }
            }
        }

        assertEquals(List.of(), offenders,
                CERTIFY_WORKFLOW + " gives the `version` input a default. That input selects an ALREADY PUBLISHED"
                        + " tag to certify, so any default is a hand-copied release number that goes stale on the"
                        + " next pom bump — and dispatching with it certifies the wrong release silently. Require"
                        + " the operator to type the tag.");
    }
}
