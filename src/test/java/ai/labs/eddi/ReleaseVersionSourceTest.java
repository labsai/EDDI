/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

    // ─────────────────────────────────────────────────────────────
    // Release pointers — the PUBLISHED version a reader deploys
    // ─────────────────────────────────────────────────────────────

    private static final Path CHART = Path.of("helm", "eddi", "Chart.yaml");
    private static final Path HELM_VALUES = Path.of("helm", "eddi", "values.yaml");
    private static final Path HELM_DEPLOYMENT = Path.of("helm", "eddi", "templates", "deployment.yaml");
    private static final Path K8S_KUSTOMIZATION = Path.of("k8s", "base", "kustomization.yaml");
    private static final Path K8S_DEPLOYMENT = Path.of("k8s", "base", "eddi-deployment.yaml");
    private static final Path MANAGER_PACKAGE_JSON = Path.of("ui", "manager", "package.json");

    /**
     * Where the release pointers are and what they look like — the same file
     * {@code scripts/bump-version.py} rewrites from, so the two cannot disagree
     * about scope.
     */
    static final Path RELEASE_POINTERS = Path.of("scripts", "release-pointers.json");

    /** A pointer match: where it is and the version it names. */
    record ReleasePointer(String file, int line, String version) {
    }

    /**
     * Every release pointer in the repository, found exactly as the bump script
     * finds them: each file under a configured root with a configured extension and
     * not excluded, each line, each pattern's first capture group.
     */
    static List<ReleasePointer> releasePointers(Path root) throws IOException {
        JsonNode config = new ObjectMapper().readTree(read(root.resolve(RELEASE_POINTERS)));
        List<String> extensions = new ArrayList<>();
        config.path("extensions").forEach(e -> extensions.add(e.asText()));
        List<String> excludes = new ArrayList<>();
        config.path("exclude").forEach(e -> excludes.add(e.asText()));
        List<Pattern> patterns = new ArrayList<>();
        config.path("patterns").forEach(p -> patterns.add(Pattern.compile(p.asText())));

        List<Path> files = new ArrayList<>();
        for (JsonNode entry : config.path("roots")) {
            Path base = root.resolve(entry.asText());
            if (Files.isRegularFile(base)) {
                files.add(base);
            } else if (Files.isDirectory(base)) {
                try (Stream<Path> walk = Files.walk(base)) {
                    walk.filter(Files::isRegularFile).sorted().forEach(files::add);
                }
            }
        }

        List<ReleasePointer> found = new ArrayList<>();
        for (Path file : files) {
            String rel = root.relativize(file).toString().replace('\\', '/');
            if (extensions.stream().noneMatch(rel::endsWith)
                    || excludes.stream().anyMatch(rel::startsWith)) {
                continue;
            }
            List<String> lines = read(file).lines().toList();
            for (int i = 0; i < lines.size(); i++) {
                for (Pattern pattern : patterns) {
                    Matcher m = pattern.matcher(lines.get(i));
                    while (m.find()) {
                        found.add(new ReleasePointer(rel, i + 1, m.group(1)));
                    }
                }
            }
        }
        return found;
    }

    private static String chartAppVersion() throws IOException {
        Matcher m = Pattern.compile("(?m)^appVersion:\\s*[\"']?([^\"'\\s]+)[\"']?\\s*$").matcher(read(CHART));
        assertTrue(m.find(), CHART + " has no appVersion: line");
        return m.group(1);
    }

    private static int[] semver(String version) {
        Matcher m = Pattern.compile("^(\\d+)\\.(\\d+)\\.(\\d+)").matcher(version);
        assertTrue(m.find(), "'" + version + "' does not start with MAJOR.MINOR.PATCH");
        return new int[]{Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3))};
    }

    /**
     * The drift this replaces: each release hand-edited a dozen lines across the
     * chart, the k8s manifests and four docs, and whichever one was missed stayed
     * wrong — {@code docs/kubernetes.md} named 6.3.0 through the whole 6.4.0 cycle.
     * Now every such line is found by one definition
     * ({@code scripts/release-pointers.json}), moved by one command
     * ({@code scripts/bump-version.py release}) and checked here, including in a
     * doc nobody thought to list.
     */
    @Test
    @DisplayName("every release pointer names the chart's appVersion")
    void releasePointersAgreeWithTheChart() throws IOException {
        String appVersion = chartAppVersion();
        List<ReleasePointer> pointers = releasePointers(Path.of(""));

        List<String> stray = pointers.stream()
                .filter(p -> !p.version().equals(appVersion))
                .map(p -> p.file() + ":" + p.line() + " names " + p.version())
                .toList();
        assertEquals(List.of(), stray,
                "these release pointers disagree with helm/eddi/Chart.yaml's appVersion " + appVersion
                        + ". Run `python scripts/bump-version.py release <version>` rather than editing them by"
                        + " hand; it rewrites every one of them");

        // Not vacuous: a pattern or a root that silently stopped matching would
        // leave the list above empty and this test green.
        List<String> files = pointers.stream().map(ReleasePointer::file).distinct().toList();
        for (Path required : List.of(CHART, K8S_KUSTOMIZATION, Path.of("k8s", "quickstart.yaml"),
                Path.of("docs", "developer-quickstart.md"))) {
            assertTrue(files.contains(required.toString().replace('\\', '/')),
                    required + " must carry a release pointer that " + RELEASE_POINTERS
                            + " matches; found pointers only in " + files);
        }
    }

    /**
     * The pointers name what a reader should deploy, so they may only name a
     * release that exists. pom.xml is the version main is BUILDING — equal to the
     * pointers on the release commit, ahead of them the rest of the cycle, never
     * behind.
     */
    @Test
    @DisplayName("the release pointers are never ahead of the build version")
    void releasePointersAreNotAheadOfThePom() throws IOException {
        String appVersion = chartAppVersion();
        String pomVersion = projectVersion();
        int[] app = semver(appVersion);
        int[] pom = semver(pomVersion);

        assertTrue(Arrays.compare(app, pom) <= 0,
                "helm/eddi/Chart.yaml's appVersion " + appVersion + " is ahead of pom.xml's " + pomVersion
                        + ": the docs and manifests would send readers to an image that has not been built");
    }

    /**
     * The chart's image tag used to be a second copy of appVersion; now it defaults
     * to it, so a release moves one line.
     */
    @Test
    @DisplayName("the Helm image tag defaults to appVersion instead of repeating it")
    void helmImageTagDefaultsToAppVersion() throws IOException {
        JsonNode values = new YAMLMapper().readTree(read(HELM_VALUES));
        assertEquals("", values.path("eddi").path("image").path("tag").asText("<missing>"),
                HELM_VALUES + " sets eddi.image.tag. Leave it empty: the chart falls back to Chart.yaml's"
                        + " appVersion, so a release has one line to move, not two that can disagree");
        assertTrue(read(HELM_DEPLOYMENT).contains("include \"eddi.imageTag\""),
                HELM_DEPLOYMENT + " must take the tag from the eddi.imageTag helper, or an empty"
                        + " eddi.image.tag renders the image as `labsai/eddi:`");
    }

    /**
     * The same for kustomize: the base Deployment names no tag, the kustomization's
     * {@code images:} entry does. The placeholder is a tag that does not exist, so
     * applying the file without kustomize fails loudly instead of pulling
     * {@code latest}.
     */
    @Test
    @DisplayName("the k8s base pins its image in the kustomization, not the Deployment")
    void k8sBasePinsTheImageInTheKustomization() throws IOException {
        List<String> images = read(K8S_DEPLOYMENT).lines()
                .map(String::strip)
                .filter(l -> l.startsWith("image:"))
                .toList();
        assertEquals(List.of("image: labsai/eddi:pinned-by-kustomization"), images,
                K8S_DEPLOYMENT + " must leave the tag to kustomization.yaml's images: entry");

        JsonNode kustomization = new YAMLMapper().readTree(read(K8S_KUSTOMIZATION));
        JsonNode eddiImage = null;
        for (JsonNode image : kustomization.path("images")) {
            if ("labsai/eddi".equals(image.path("name").asText())) {
                eddiImage = image;
            }
        }
        assertNotNull(eddiImage, K8S_KUSTOMIZATION + " has no images: entry for labsai/eddi");
        assertEquals(chartAppVersion(), eddiImage.path("newTag").asText(),
                K8S_KUSTOMIZATION + " must pin labsai/eddi to the chart's appVersion");
    }

    /**
     * The Manager's sidebar reads the EDDI version from pom.xml (vite.config.ts). A
     * version in its package.json would be one more copy to move every release, and
     * a wrong one the moment it was not.
     */
    @Test
    @DisplayName("the Manager's package.json carries no version of its own")
    void managerPackageJsonCarriesNoVersion() throws IOException {
        JsonNode pkg = new ObjectMapper().readTree(read(MANAGER_PACKAGE_JSON));
        assertFalse(pkg.has("version"),
                MANAGER_PACKAGE_JSON + " declares \"version\": " + pkg.path("version")
                        + ". The Manager is a private package inside this repository and takes its version from"
                        + " pom.xml; remove the field (and the matching root entries in package-lock.json)");
    }

    /**
     * Three readers take "the first {@code <version>} in pom.xml" to be the
     * project's version: {@code ci.yml} ({@code grep -m1 '<version>'}, which names
     * the image and gates the release tag), the Manager's {@code vite.config.ts},
     * and {@code scripts/bump-version.py}, which also WRITES it. A {@code <parent>}
     * block — or a commented-out version — ahead of the project's own would make
     * all three silently read, and the script rewrite, somebody else's version.
     */
    @Test
    @DisplayName("the first <version> in pom.xml is the project's own")
    void firstPomVersionIsTheProjects() throws IOException {
        String pom = read(POM);
        assertFalse(pom.contains("<parent>"),
                "pom.xml has a <parent>. ci.yml, ui/manager/vite.config.ts and scripts/bump-version.py all take the"
                        + " FIRST <version> element as the project's; move them to a rule that skips the parent's"
                        + " before adding one");
        Matcher m = Pattern.compile("<artifactId>eddi</artifactId>\\s*<version>([^<]+)</version>").matcher(pom);
        assertTrue(m.find() && m.group(1).equals(projectVersion()),
                "the first <version> in pom.xml must be the one directly after <artifactId>eddi</artifactId>");
    }

    /**
     * The sweep above lives in Build &amp; Test, which a docs-only pull request
     * skips — and a skipped required check still satisfies branch protection. So
     * {@code ci.yml} runs {@code bump-version.py check} in its own cheap job, gated
     * on the {@code release_pointers} path filter, and that filter has to cover
     * every root the JSON sweeps, or a stale pointer in the uncovered root merges
     * and turns main red on somebody else's PR. The script and the JSON are graded
     * by Java tests, so they must also trigger Build &amp; Test.
     */
    @Test
    @DisplayName("CI checks the release pointers on every change that can break them")
    void ciRunsThePointerCheckOnEveryRoot() throws IOException {
        String filters = "";
        JsonNode workflow = new YAMLMapper().readTree(read(CI_WORKFLOW));
        for (JsonNode step : workflow.path("jobs").path("detect-changes").path("steps")) {
            if (step.path("with").has("filters")) {
                filters = step.path("with").path("filters").asText();
            }
        }
        JsonNode parsed = new YAMLMapper().readTree(filters);

        List<String> pointerFilter = new ArrayList<>();
        parsed.path("release_pointers").forEach(p -> pointerFilter.add(p.asText()));
        JsonNode config = new ObjectMapper().readTree(read(RELEASE_POINTERS));
        for (JsonNode root : config.path("roots")) {
            String r = root.asText();
            assertTrue(pointerFilter.contains(r) || pointerFilter.contains(r + "/**"),
                    CI_WORKFLOW + "'s release_pointers filter must cover " + r + ", a root " + RELEASE_POINTERS
                            + " sweeps. It lists: " + pointerFilter);
        }

        List<String> operatorDocs = new ArrayList<>();
        parsed.path("operator_docs").forEach(p -> operatorDocs.add(p.asText()));
        for (String graded : List.of("scripts/bump-version.py", "scripts/release-pointers.json")) {
            assertTrue(operatorDocs.contains(graded),
                    CI_WORKFLOW + "'s operator_docs filter must list " + graded + ": Java tests grade it, and"
                            + " `scripts/**` alone triggers only Shell Lint. It lists: " + operatorDocs);
        }

        JsonNode job = workflow.path("jobs").path("release-pointers");
        assertTrue(job.path("if").asText().contains("release_pointers"),
                CI_WORKFLOW + " needs a release-pointers job gated on the release_pointers filter");
        assertTrue(job.toString().contains("scripts/bump-version.py check"),
                CI_WORKFLOW + "'s release-pointers job must run `python3 scripts/bump-version.py check`");
    }
}
