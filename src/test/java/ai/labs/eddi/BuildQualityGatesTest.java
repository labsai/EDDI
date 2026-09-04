/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the build's quality gates to the wiring that makes them gates.
 * <p>
 * Every rule here was, at some point, configured so it could not fail:
 * <ul>
 * <li><b>The coverage gate ran on the wrong data.</b> {@code merged-check}
 * grades {@code jacoco-merged.exec}, which is only a merge when the ITs ran —
 * but {@code skipITs} defaults to {@code true}, so a plain {@code mvn verify}
 * fed it unit-test coverage alone and failed a clean, all-green tree at 89.34%
 * against a 90/80 threshold that assumes both suites. CI never saw it: the
 * build job stops at {@code test} and the integration job passes
 * {@code -DskipITs=false}, so the breakage landed only on contributors
 * following AGENTS.md.</li>
 * <li><b>Checkstyle could not fail anything.</b> {@code failOnViolation=false}
 * plus {@code violationSeverity=warning} made {@code mvn validate} report and
 * exit 0, so the import rules AGENTS.md §4.7 calls mandatory were decorative
 * and three dead imports reached main.</li>
 * <li><b>The formatter rewrote instead of checking.</b> The {@code format} goal
 * defaults to {@code process-sources}, so every {@code compile} edited tracked
 * files — including ones the developer never touched, which then show up in
 * {@code git status} next to the real work — while CI reformatted its own
 * checkout and reported nothing, so unformatted code could merge.</li>
 * <li><b>{@code failsafe-plugin.version} was dead.</b> Failsafe read
 * {@code ${surefire-plugin.version}}, so bumping the property that exists for
 * exactly that purpose silently did nothing. The two values were equal, which
 * is what kept it invisible.</li>
 * </ul>
 * A build gate that cannot fail is indistinguishable from a passing build, so
 * none of these had a symptom until someone went looking. This test is the
 * symptom.
 */
@DisplayName("build quality gates")
class BuildQualityGatesTest {

    private static final Path POM = Path.of("pom.xml");
    private static final Path CHECKSTYLE = Path.of("checkstyle.xml");
    private static final Path CI_WORKFLOW = Path.of(".github", "workflows", "ci.yml");
    private static final Path AGENTS_MD = Path.of("AGENTS.md");

    /**
     * The flag that decides whether the coverage gate runs at all. Since the gate
     * carries {@code <skip>${skipITs}</skip>} it is inert in every build that does
     * not pass this, so a CI job has to.
     */
    private static final String SKIP_ITS_DISABLED = "-DskipITs=false";

    /**
     * The version {@code swagger-annotations} shipped in before the unused
     * <em>direct</em> dependency was removed. swagger-parser pulls the same
     * artifact transitively at 2.2.52, so dropping the declaration without managing
     * the version would have downgraded the jar that actually lands in the image
     * and the SBOM.
     */
    private static final String SWAGGER_ANNOTATIONS_VERSION = "2.2.54";

    /**
     * Files git must record as mode {@code 100755}. {@code mvnw} is invoked
     * directly by six CI jobs, and git runs a hook <em>only</em> if it is
     * executable — a 0644 {@code .githooks/pre-push} is skipped with a hint that is
     * easy to miss, which silently disarms the force-push guard AGENTS.md tells
     * contributors to activate.
     */
    private static final List<String> MUST_BE_EXECUTABLE = List.of("mvnw", ".githooks/pre-push");

    /** The property failsafe reads to decide whether the ITs run at all. */
    private static final String SKIP_ITS = "${skipITs}";

    /**
     * The JaCoCo executions that consume or produce integration-test coverage. Each
     * has to be skipped when the ITs are, or it grades (or merges) data that was
     * never produced.
     */
    private static final List<String> IT_COVERAGE_EXECUTIONS = List.of(
            "merge-it", "report-integration", "merge", "merged-report", "merged-check");

    /**
     * The two langchain4j release lines. Fifteen artifacts pin one of these, and a
     * bump has to move both together: a split leaves modules resolving different
     * {@code langchain4j-core} versions, which surfaces as a runtime
     * {@code NoSuchMethodError} rather than a build failure. There used to be four
     * properties for these two values.
     */
    private static final List<String> LANGCHAIN4J_VERSION_PROPERTIES = List.of(
            "${langchain4j.version}", "${langchain4j-beta.version}");

    /**
     * checkstyle.xml carries a DOCTYPE pointing at puppycrawl.com, so the doctype
     * cannot simply be disallowed — but this test must never reach the network (it
     * would make a build gate depend on a third-party host being up). External DTDs
     * and entities are switched off and any that survive resolve to nothing.
     */
    private static Document parse(Path path) throws IOException, ParserConfigurationException, SAXException {
        assertTrue(Files.isRegularFile(path),
                "expected the working directory to be the project root; " + path.toAbsolutePath() + " not found");
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        builder.setEntityResolver((publicId, systemId) -> new InputSource(new StringReader("")));
        return builder.parse(path.toFile());
    }

    /** Reads a build file as text, asserting the working directory first. */
    private static String read(Path path) throws IOException {
        assertTrue(Files.isRegularFile(path),
                "expected the working directory to be the project root; " + path.toAbsolutePath() + " not found");
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    /** Direct children of {@code parent} with the given tag name. */
    private static List<Element> children(Element parent, String tagName) {
        List<Element> found = new ArrayList<>();
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node instanceof Element element && element.getTagName().equals(tagName)) {
                found.add(element);
            }
        }
        return found;
    }

    private static Optional<Element> child(Element parent, String tagName) {
        return children(parent, tagName).stream().findFirst();
    }

    /** The text of a direct child element, or {@code null} if it is absent. */
    private static String childText(Element parent, String tagName) {
        return child(parent, tagName).map(Element::getTextContent).map(String::trim).orElse(null);
    }

    /** A plugin declared in {@code /project/build/plugins}. */
    private static Element buildPlugin(Document pom, String artifactId) {
        Element build = child(pom.getDocumentElement(), "build")
                .orElseThrow(() -> new AssertionError("no <build> in pom.xml"));
        Element plugins = child(build, "plugins")
                .orElseThrow(() -> new AssertionError("no <build><plugins> in pom.xml"));
        return children(plugins, "plugin").stream()
                .filter(plugin -> artifactId.equals(childText(plugin, "artifactId")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no <plugin> with artifactId " + artifactId + " in <build><plugins>"));
    }

    private static Element execution(Element plugin, String id) {
        Element executions = child(plugin, "executions")
                .orElseThrow(() -> new AssertionError("no <executions> in " + childText(plugin, "artifactId")));
        return children(executions, "execution").stream()
                .filter(execution -> id.equals(childText(execution, "id")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no <execution> with id '" + id + "' in " + childText(plugin, "artifactId")));
    }

    /**
     * build-ci-01. The gate's own data file only exists as a merge when the ITs
     * ran, so the gate has to be skipped in exactly the runs that skip them.
     */
    @Test
    @DisplayName("the coverage gate is skipped together with the integration tests it grades")
    void coverageGateIsSkippedWithTheIntegrationTests() throws Exception {
        Element jacoco = buildPlugin(parse(POM), "jacoco-maven-plugin");

        for (String id : IT_COVERAGE_EXECUTIONS) {
            Element configuration = child(execution(jacoco, id), "configuration")
                    .orElseThrow(() -> new AssertionError("execution '" + id + "' has no <configuration>"));

            assertEquals(SKIP_ITS, childText(configuration, "skip"),
                    "jacoco execution '" + id + "' must carry <skip>" + SKIP_ITS + "</skip>. Without it, `mvn verify`"
                            + " (which defaults to skipITs=true) grades unit-test-only coverage against the merged"
                            + " UT+IT threshold and fails a clean tree with every test green.");
        }
    }

    /**
     * The other half of build-ci-01: skipping the gate must not become a way to
     * quietly lower it. 90/80 is the OpenSSF Gold target the CI integration job
     * still enforces.
     */
    @Test
    @DisplayName("the coverage gate still demands 90% instructions and 80% branches")
    void coverageGateStillDemandsNinetyEighty() throws Exception {
        Element check = execution(buildPlugin(parse(POM), "jacoco-maven-plugin"), "merged-check");
        Element configuration = child(check, "configuration").orElseThrow();
        Element rule = child(child(configuration, "rules").orElseThrow(), "rule").orElseThrow();
        Element limits = child(rule, "limits").orElseThrow();

        assertEquals("verify", childText(check, "phase"), "the gate must run at verify");
        for (Element limit : children(limits, "limit")) {
            String counter = childText(limit, "counter");
            String minimum = childText(limit, "minimum");
            if ("INSTRUCTION".equals(counter)) {
                assertEquals("0.90", minimum, "the instruction threshold is the OpenSSF Gold target");
            } else if ("BRANCH".equals(counter)) {
                assertEquals("0.80", minimum, "the branch threshold is the OpenSSF Gold target");
            }
        }
    }

    /**
     * build-ci-05. Both flags false made the whole ruleset advisory;
     * {@code violationSeverity=error} is what keeps that from swinging the other
     * way and making {@code FileLength}/{@code LineLength} blocking too.
     */
    @Test
    @DisplayName("Checkstyle can fail the build, on the error-severity rules only")
    void checkstyleCanFailTheBuild() throws Exception {
        Element checkstyle = buildPlugin(parse(POM), "maven-checkstyle-plugin");
        Element configuration = child(checkstyle, "configuration").orElseThrow();

        assertEquals("true", childText(configuration, "failOnViolation"),
                "failOnViolation=false makes `mvn validate` report violations and exit 0, so the import rules"
                        + " AGENTS.md 4.7 calls mandatory cannot block anything");
        assertEquals("error", childText(configuration, "violationSeverity"),
                "violationSeverity decides WHICH checkstyle.xml modules can fail; 'error' limits it to the ones"
                        + " marked severity=\"error\" and keeps FileLength/LineLength advisory");

        Element validate = execution(checkstyle, "validate");
        assertEquals("validate", childText(validate, "phase"));
        assertEquals(List.of("check"),
                children(child(validate, "goals").orElseThrow(), "goal").stream().map(Element::getTextContent).map(String::trim).toList());
    }

    /**
     * build-ci-06. {@code format} defaults to {@code process-sources}, so binding
     * it means every build mutates tracked sources instead of checking them.
     */
    @Test
    @DisplayName("the formatter checks sources rather than rewriting them")
    void formatterChecksRatherThanRewrites() throws Exception {
        Element formatter = buildPlugin(parse(POM), "formatter-maven-plugin");
        List<String> goals = new ArrayList<>();
        for (Element execution : children(child(formatter, "executions").orElseThrow(), "execution")) {
            children(child(execution, "goals").orElseThrow(), "goal")
                    .forEach(goal -> goals.add(goal.getTextContent().trim()));
        }

        assertTrue(goals.contains("validate"),
                "the formatter must run its validate goal so unformatted sources FAIL the build");
        assertFalse(goals.contains("format"),
                "the format goal rewrites tracked files on every compile/test/package — including files the developer"
                        + " never touched, which then land in `git status` next to the real work (AGENTS.md rule 5)."
                        + " `./mvnw formatter:format` stays the explicit developer command.");
    }

    /**
     * build-ci-11. Failsafe read {@code ${surefire-plugin.version}}, so the
     * property named for it was dead and a bump to it changed nothing. The two
     * values were identical, which is precisely what hid the bug.
     */
    @Test
    @DisplayName("failsafe is pinned by its own version property")
    void failsafeUsesItsOwnVersionProperty() throws Exception {
        assertEquals("${failsafe-plugin.version}", childText(buildPlugin(parse(POM), "maven-failsafe-plugin"), "version"),
                "failsafe pinned to ${surefire-plugin.version} means bumping failsafe-plugin.version does nothing,"
                        + " silently, until the two values diverge");
    }

    /**
     * build-ci-12 (the property half). Fifteen artifacts, two release lines, and
     * previously four properties — two pairs holding identical strings, so the
     * natural half-finished bump left modules on different {@code langchain4j-core}
     * versions.
     */
    @Test
    @DisplayName("every langchain4j artifact pins one of the two version properties")
    void langchain4jArtifactsUseOneOfTwoProperties() throws Exception {
        Document pom = parse(POM);
        Element dependencies = child(pom.getDocumentElement(), "dependencies").orElseThrow();
        List<String> offenders = new ArrayList<>();

        for (Element dependency : children(dependencies, "dependency")) {
            if (!"dev.langchain4j".equals(childText(dependency, "groupId"))) {
                continue;
            }
            String version = childText(dependency, "version");
            if (version != null && !LANGCHAIN4J_VERSION_PROPERTIES.contains(version)) {
                offenders.add(childText(dependency, "artifactId") + " -> " + version);
            }
        }

        assertEquals(List.of(), offenders,
                "langchain4j artifacts must pin " + LANGCHAIN4J_VERSION_PROPERTIES + " and nothing else — a third"
                        + " property is a value that can drift out of step, and the mismatch surfaces as a runtime"
                        + " NoSuchMethodError rather than a build failure");
    }

    /**
     * build-ci-05, checkstyle.xml half. {@code violationSeverity=error} in the pom
     * only blocks on modules that declare that severity; without these the gate
     * exists but grades nothing.
     */
    @Test
    @DisplayName("the import rules carry the error severity the gate blocks on")
    void importRulesAreErrorSeverity() throws Exception {
        Document checkstyle = parse(CHECKSTYLE);
        NodeList modules = checkstyle.getElementsByTagName("module");

        for (String name : List.of("UnusedImports", "RedundantImport")) {
            Element module = null;
            for (int i = 0; i < modules.getLength(); i++) {
                Element candidate = (Element) modules.item(i);
                if (name.equals(candidate.getAttribute("name"))) {
                    module = candidate;
                    break;
                }
            }
            assertNotNull(module, "checkstyle.xml declares no " + name + " module");

            String severity = children(module, "property").stream()
                    .filter(property -> "severity".equals(property.getAttribute("name")))
                    .map(property -> property.getAttribute("value"))
                    .findFirst()
                    .orElse(null);
            assertEquals("error", severity,
                    name + " must be severity=\"error\": the pom blocks at violationSeverity=error, so a module left"
                            + " at the Checker's default \"warning\" is reported and ignored");
        }
    }

    /**
     * build-ci-20. The upstream default alternation is {@code a href|href}; this
     * copy had {@code a]href}, and {@code ]} outside a character class is a
     * literal, so the alternative matched text that appears in no Java file.
     * Harmless while Checkstyle could not fail, noise the moment it can.
     */
    @Test
    @DisplayName("the LineLength ignorePattern exempts anchors as intended")
    void lineLengthIgnorePatternIsNotTypoed() throws Exception {
        Document checkstyle = parse(CHECKSTYLE);
        NodeList modules = checkstyle.getElementsByTagName("module");
        String ignorePattern = null;

        for (int i = 0; i < modules.getLength(); i++) {
            Element module = (Element) modules.item(i);
            if ("LineLength".equals(module.getAttribute("name"))) {
                ignorePattern = children(module, "property").stream()
                        .filter(property -> "ignorePattern".equals(property.getAttribute("name")))
                        .map(property -> property.getAttribute("value"))
                        .findFirst()
                        .orElse(null);
            }
        }

        assertNotNull(ignorePattern, "checkstyle.xml declares no LineLength ignorePattern");
        assertFalse(ignorePattern.contains("a]href"),
                "'a]href' is a typo of the upstream 'a href'; ']' outside a character class is a literal, so the"
                        + " alternative matches nothing and Javadoc anchors are reported as violations");
        assertTrue(ignorePattern.contains("a href"), "the upstream alternation exempts HTML anchors in Javadoc");
    }

    /**
     * The other half of build-ci-05, and the one deliberate judgement call on this
     * branch. {@code failsOnError} covers Checkstyle <em>processing</em> errors —
     * an unparseable source, a rule that blows up on a new language construct —
     * which are not violations and so are not covered by
     * {@code failOnViolation}/{@code violationSeverity} at all. Left false, a
     * Checkstyle that cannot read a file reports nothing and passes, which is the
     * same "gate that cannot fail" shape as everything else in this class: the
     * import rules would go ungraded on exactly the file that broke the parser.
     */
    @Test
    @DisplayName("a Checkstyle processing error fails the build rather than being swallowed")
    void checkstyleProcessingErrorsFailTheBuild() throws Exception {
        Element configuration = child(buildPlugin(parse(POM), "maven-checkstyle-plugin"), "configuration").orElseThrow();

        assertEquals("true", childText(configuration, "failsOnError"),
                "failsOnError=false swallows Checkstyle PROCESSING errors (an unparseable source, a rule that throws"
                        + " on a new language construct). They are not violations, so failOnViolation does not cover"
                        + " them: the file goes ungraded and the build stays green.");
    }

    /**
     * U10. The blocking import rules grade {@code src/main/java} only —
     * {@code includeTestSourceDirectory} defaults to false — while
     * {@code formatter:validate} grades both source roots. AGENTS.md §4.7 has to
     * say which is which, because a contributor who reads "any compile, test or
     * verify will stop on either" and finds an unused test import merging anyway
     * concludes the gate is broken rather than narrower than advertised.
     * <p>
     * The flag is written out explicitly rather than left to its default so that
     * this assertion has something to read, and so that turning it on is a visible
     * edit. Flip it and this test fails until the sentence in AGENTS.md is updated
     * too — which is the point: the two must move together.
     */
    @Test
    @DisplayName("the Checkstyle import gate's scope is stated in the pom and matches AGENTS.md")
    void checkstyleImportGateScopeMatchesTheDocs() throws Exception {
        Element configuration = child(buildPlugin(parse(POM), "maven-checkstyle-plugin"), "configuration").orElseThrow();
        String includeTests = childText(configuration, "includeTestSourceDirectory");
        String agents = read(AGENTS_MD);

        assertNotNull(includeTests,
                "state includeTestSourceDirectory explicitly — the default is false, and a scope nobody wrote down"
                        + " is a scope nobody can check against the documentation");

        if ("true".equals(includeTests)) {
            assertFalse(agents.contains("`src/main/java` only"),
                    "Checkstyle now grades test sources too, so AGENTS.md §4.7 must stop scoping the import gate to"
                            + " src/main/java");
        } else {
            assertEquals("false", includeTests, "includeTestSourceDirectory must be true or false");
            assertTrue(agents.contains("`src/main/java` only"),
                    "the Checkstyle import gate does NOT grade src/test/java, so AGENTS.md §4.7 must say so — the"
                            + " unqualified promise that any compile/test/verify stops on an unused import is true"
                            + " for formatter:validate (both roots) and false for Checkstyle");
        }
    }

    /**
     * U5. Making the coverage gate conditional on {@code skipITs} fixed a build
     * that failed on a clean tree, but it moved the enforcement out of the pom and
     * into a single CI command line. Delete or typo {@code -DskipITs=false} there
     * and the OpenSSF Gold 90/80 threshold is off everywhere, with every test still
     * green and nothing to see — which is the invisible-disabled-gate shape the
     * rest of this class exists to eliminate, reintroduced one layer up.
     */
    @Test
    @DisplayName("a CI job actually passes the flag that arms the coverage gate")
    void theCoverageGateIsArmedByCi() throws Exception {
        List<String> verifyInvocations = read(CI_WORKFLOW).lines()
                .map(String::trim)
                .filter(line -> line.contains("mvnw verify"))
                .toList();

        assertFalse(verifyInvocations.isEmpty(), CI_WORKFLOW + " runs no `mvnw verify` at all, so nothing reaches the"
                + " verify-phase coverage gate");
        assertTrue(verifyInvocations.stream().anyMatch(line -> line.contains(SKIP_ITS_DISABLED)),
                "no `mvnw verify` in " + CI_WORKFLOW + " passes " + SKIP_ITS_DISABLED + ". The 90/80 jacoco gate"
                        + " carries <skip>${skipITs}</skip>, so that flag is the ONLY thing that runs it anywhere."
                        + " Found: " + verifyInvocations);
    }

    /**
     * U4. Removing the unused {@code swagger-annotations} <em>direct</em>
     * dependency is right — no source imports {@code io.swagger.v3.oas.annotations}
     * — but the artifact does not leave the build with it: swagger-parser, which
     * {@code McpApiToolBuilder} genuinely uses through
     * {@code io.swagger.v3.oas.models}, pulls the same jar transitively two patch
     * versions older. So the declaration has to move to
     * {@code dependencyManagement} rather than disappear, or a cleanup silently
     * downgrades what ships in the image, the SBOM and the Trivy scan.
     */
    @Test
    @DisplayName("swagger-annotations keeps the version it shipped, without a direct dependency")
    void swaggerAnnotationsKeepsItsShippedVersion() throws Exception {
        Document pom = parse(POM);
        Element root = pom.getDocumentElement();

        Element managed = child(child(root, "dependencyManagement").orElseThrow(
                () -> new AssertionError("no <dependencyManagement> in pom.xml")), "dependencies").orElseThrow();
        String managedVersion = children(managed, "dependency").stream()
                .filter(dependency -> "io.swagger.core.v3".equals(childText(dependency, "groupId"))
                        && "swagger-annotations".equals(childText(dependency, "artifactId")))
                .map(dependency -> childText(dependency, "version"))
                .findFirst()
                .orElse(null);

        assertEquals(SWAGGER_ANNOTATIONS_VERSION, managedVersion,
                "io.swagger.core.v3:swagger-annotations must stay managed at " + SWAGGER_ANNOTATIONS_VERSION
                        + ". Unmanaged it resolves to whatever swagger-parser drags in (2.2.52), so removing the"
                        + " declaration is not a removal — the jar still ships, still lands in the CycloneDX SBOM"
                        + " and is still scanned by Trivy, just older, silently reversing a deliberate bump.");

        boolean declaredDirectly = children(child(root, "dependencies").orElseThrow(), "dependency").stream()
                .anyMatch(dependency -> "io.swagger.core.v3".equals(childText(dependency, "groupId"))
                        && "swagger-annotations".equals(childText(dependency, "artifactId")));
        assertFalse(declaredDirectly,
                "swagger-annotations is a version pin, not a usage: nothing imports io.swagger.v3.oas.annotations,"
                        + " so it belongs in <dependencyManagement> and not on the compile classpath");
    }

    /**
     * build-ci-02 / U7. Git stores the executable bit in the tree, and it is what a
     * fresh clone materialises — {@code .githooks/pre-push} was committed 0644, so
     * the force-push guard AGENTS.md tells contributors to activate was skipped
     * outright on Linux and macOS with only an easy-to-miss hint. Windows hid it
     * (core.filemode is false there), and {@code Files.isExecutable} would hide it
     * again, which is why this asks git for the recorded mode instead of the
     * filesystem.
     * <p>
     * CI asserts the same thing, but only in the installer job, which the
     * {@code scripts} paths filter skips on a PR that touches only {@code src/**}
     * and {@code pom.xml} — i.e. on most PRs. This runs in every {@code mvn test}.
     */
    @Test
    @DisplayName("mvnw and the pre-push hook are recorded executable in git")
    void executableBitsAreRecordedInGit() throws Exception {
        TreeMap<String, String> modes = gitFileModes();
        Assumptions.assumeTrue(modes != null,
                "git is not available on PATH, or this is not a git checkout — nothing to read a recorded mode from");

        List<String> offenders = new ArrayList<>();
        for (String path : MUST_BE_EXECUTABLE) {
            String mode = modes.get(path);
            assertNotNull(mode, path + " is not tracked by git");
            if (!"100755".equals(mode)) {
                offenders.add(path + " is " + mode);
            }
        }

        assertEquals(List.of(), offenders,
                "these files must be mode 100755 in the git tree — fix with `git update-index --chmod=+x <path>`."
                        + " A 0644 hook is ignored by git with a hint that scrolls past in push output, so the guard"
                        + " fails silently for exactly the people who opted into it");
    }

    /**
     * {@code git ls-files -s} for the paths under test, as
     * {@code path -> recorded mode}. Returns {@code null} when git cannot be run,
     * so the caller can skip rather than fail on a source tree exported without
     * history.
     */
    private static TreeMap<String, String> gitFileModes() throws Exception {
        ProcessBuilder builder = new ProcessBuilder("git", "ls-files", "-s", "--", "mvnw", ".githooks/");
        builder.directory(Path.of("").toAbsolutePath().toFile());
        builder.redirectErrorStream(true);

        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            return null;
        }

        TreeMap<String, String> modes = new TreeMap<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                // "<mode> <sha> <stage>\t<path>"
                int tab = line.indexOf('\t');
                int space = line.indexOf(' ');
                if (tab > 0 && space > 0) {
                    modes.put(line.substring(tab + 1).trim(), line.substring(0, space));
                }
            }
        }
        if (!process.waitFor(60, TimeUnit.SECONDS) || process.exitValue() != 0) {
            process.destroyForcibly();
            return null;
        }
        return modes.isEmpty() ? null : modes;
    }
}
