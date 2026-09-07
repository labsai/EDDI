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
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
    private static final Path BASE_IMAGE_WORKFLOW = Path.of(".github", "workflows", "base-image-check.yml");
    private static final Path WORKFLOWS = Path.of(".github", "workflows");
    private static final Path DEPENDABOT = Path.of(".github", "dependabot.yml");
    private static final Path PRE_PUSH_HOOK = Path.of(".githooks", "pre-push");

    /**
     * The one Dockerfile base-image-check.yml is responsible for keeping current.
     */
    private static final String PRODUCTION_DOCKERFILE_DIRECTORY = "/src/main/docker";

    /**
     * An {@code import com.fasterxml.jackson.dataformat.<module>.…} line. The
     * captured segment is the Jackson data-format module name, which is also the
     * artifactId suffix: {@code yaml} is shipped by
     * {@code jackson-dataformat-yaml}.
     */
    private static final Pattern JACKSON_DATAFORMAT_IMPORT = Pattern.compile("import\\s+com\\.fasterxml\\.jackson\\.dataformat\\.([a-z0-9]+)\\.");

    /**
     * A redirection into one of the runner-supplied environment files that does not
     * quote the path — {@code >> $GITHUB_STEP_SUMMARY} rather than
     * {@code >> "$GITHUB_STEP_SUMMARY"}. The quoted form cannot match: the
     * {@code $} is preceded by a double quote there, not by the redirection
     * operator and optional whitespace this looks for.
     */
    private static final Pattern UNQUOTED_GITHUB_FILE_REDIRECTION = Pattern.compile(">>?\\s*\\$GITHUB_(?:STEP_SUMMARY|OUTPUT|ENV|PATH)\\b");

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
     * A test opening a document that sits at the repository root, written as a
     * single-segment {@code Path.of} literal ending in {@code .md}. Separators are
     * excluded from the character class on purpose, so a multi-segment read (a file
     * under {@code docs/}) does not match: a docs-only PR deliberately skips the
     * build, and widening that is a policy decision rather than a filter oversight.
     */
    private static final Pattern ROOT_DOCUMENT_READ = Pattern.compile("Path\\.of\\(\"([^\"/\\\\]+\\.md)\"\\)");

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
     * <p>
     * Collected into a map and compared whole, deliberately. Walking the
     * {@code <limit>} elements and asserting inside an {@code if} on the counter
     * name grades only the limits that are still there, so deleting both blocks —
     * or renaming the counters to {@code LINE}/{@code METHOD} — leaves
     * {@code jacoco:check} with no rule to enforce and this test green: the exact
     * silently-disabled-gate shape the class exists to eliminate, one level up.
     */
    @Test
    @DisplayName("the coverage gate still demands 90% instructions and 80% branches")
    void coverageGateStillDemandsNinetyEighty() throws Exception {
        Element check = execution(buildPlugin(parse(POM), "jacoco-maven-plugin"), "merged-check");
        Element configuration = child(check, "configuration").orElseThrow();
        Element rule = child(child(configuration, "rules").orElseThrow(), "rule").orElseThrow();
        Element limits = child(rule, "limits").orElseThrow();

        assertEquals("verify", childText(check, "phase"), "the gate must run at verify");

        Map<String, String> thresholds = new TreeMap<>();
        for (Element limit : children(limits, "limit")) {
            thresholds.put(childText(limit, "counter"), childText(limit, "minimum"));
        }

        assertEquals(Map.of("INSTRUCTION", "0.90", "BRANCH", "0.80"), thresholds,
                "the merged-check rule must carry exactly the OpenSSF Gold limits. Absence fails here too: a rule"
                        + " with no <limit> (or with the counters renamed) enforces nothing while every test stays"
                        + " green, which is how a gate gets switched off without a diff that looks like switching a"
                        + " gate off.");
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
     * <p>
     * A missing {@code <version>} is an offender too, and it used to be the one
     * shape that slipped through: skipping the check when the element is absent
     * lets an artifact fall back to whatever a BOM or another dependency's
     * transitive tree supplies, which is precisely the "not pinned to one of these
     * two coordinated properties" state this test claims to forbid — arrived at
     * without a value for anyone to notice.
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
            if (version == null) {
                offenders.add(childText(dependency, "artifactId") + " -> no <version>");
            } else if (!LANGCHAIN4J_VERSION_PROPERTIES.contains(version)) {
                offenders.add(childText(dependency, "artifactId") + " -> " + version);
            }
        }

        assertEquals(List.of(), offenders,
                "langchain4j artifacts must pin " + LANGCHAIN4J_VERSION_PROPERTIES + " and nothing else — a third"
                        + " property is a value that can drift out of step, and the mismatch surfaces as a runtime"
                        + " NoSuchMethodError rather than a build failure. An artifact with no <version> at all is"
                        + " the same failure with nothing to read: it resolves through a BOM or a transitive tree,"
                        + " so the two release lines can part company without either property changing.");
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
     * The other half of build-ci-05, corrected. This assertion used to demand
     * {@code failsOnError=true} on the premise that it covers Checkstyle
     * <em>processing</em> errors — an unparseable source, a rule that throws on a
     * new language construct — which {@code failOnViolation} would not reach. The
     * plugin's own descriptor (maven-checkstyle-plugin 3.6.0,
     * {@code META-INF/maven/plugin.xml}, the authoritative source AGENTS.md rule 7
     * points at) says the opposite: <em>"If this is true, and Checkstyle reported
     * any violations or errors, the build fails immediately after running
     * Checkstyle, before checking the log for logViolationsToConsole. If you want
     * to use logViolationsToConsole, use failOnViolation instead of this."</em>
     * <p>
     * So it is a second gate over the same error-severity set — the executor counts
     * ERROR-severity events and throws before the mojo's own violation summary runs
     * — reached earlier and reported worse, on a build that sets
     * {@code consoleOutput}. Genuine processing errors need no flag at all:
     * Checkstyle's {@code Checker.haltOnException} defaults to true, so an
     * unparseable source aborts the run regardless.
     * <p>
     * The flag is therefore not configured, and this test exists so it cannot come
     * back carrying the justification that was just disproved.
     */
    @Test
    @DisplayName("Checkstyle blocks through failOnViolation, not through failsOnError")
    void checkstyleBlocksThroughFailOnViolation() throws Exception {
        Element configuration = child(buildPlugin(parse(POM), "maven-checkstyle-plugin"), "configuration").orElseThrow();

        assertNull(childText(configuration, "failsOnError"),
                "failsOnError buys nothing here: per the plugin's own descriptor it fails on the same violations"
                        + " failOnViolation+violationSeverity already block, only earlier and with the worse"
                        + " \"Failed during checkstyle execution\" message, and it is explicitly documented as the"
                        + " wrong choice when violations are logged to the console. It is NOT a processing-error"
                        + " guard — Checker.haltOnException already is one. Leave it unset.");
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
     * The release half of the version single-source-of-truth work. The PR preflight
     * dry-run builds the image with {@code EDDI_VERSION=$POM_VERSION} and then
     * asserts the Red Hat {@code version} label equals the pom version, while the
     * release build labels the image with the git tag
     * ({@code ${GITHUB_REF#refs/tags/}}). Nothing reconciled the two, so a hot-fix
     * tagged {@code 6.3.1} on a tree whose pom still said {@code 6.3.0} would
     * publish an image LABELLED 6.3.1 whose running application reports 6.3.0 in
     * its banner, its User-Agent, {@code /openapi} and
     * {@code quarkus.container-image.additional-tags} — and the preflight job
     * cannot see it, because on a pull request both of its values come from the
     * same pom.
     * <p>
     * The parity check lives in the one place that knows the tag, and it is release
     * -only, which means it is also the path nobody dry-runs. So it is pinned here
     * rather than trusted.
     */
    @Test
    @DisplayName("the release build refuses a git tag that disagrees with pom.xml")
    void releaseTagMustMatchThePomVersion() throws Exception {
        String ci = read(CI_WORKFLOW);

        assertTrue(ci.contains("\"$PRIMARY_TAG\" != \"$POM_VERSION\""),
                CI_WORKFLOW + " does not compare the pushed tag against the pom version. Without that comparison the"
                        + " published image's `version` label (built from the tag) and everything the application"
                        + " reports about itself (derived from pom.xml) can disagree, and the PR preflight — which"
                        + " reads the pom for both sides — passes either way.");
        assertTrue(ci.contains("::error::Tag '${PRIMARY_TAG}' does not match pom.xml version"),
                "the tag/pom mismatch must fail the docker job with a ::error:: annotation, not just log");
    }

    /**
     * r8. The formatter and Checkstyle bind to {@code validate}, so every lifecycle
     * build in the workflow paid for them — five jobs grading the identical commit.
     * The downstream ones now pass {@code -Dformatter.skip=true
     * -Dcheckstyle.skip=true}, which is fine exactly as long as the job that OWNS
     * the style verdict still does not. Add those flags to
     * {@code ./mvnw clean test} as well and the gates this whole class exists to
     * arm are off everywhere, with a green pipeline and a diff that reads like a
     * performance tweak.
     */
    @Test
    @DisplayName("at least one CI job still runs the style gates it is allowed to skip elsewhere")
    void theStyleGatesAreStillArmedInCi() throws Exception {
        List<String> lifecycleBuilds = read(CI_WORKFLOW).lines()
                .map(String::trim)
                .filter(line -> line.contains("./mvnw"))
                .filter(line -> line.contains(" test") || line.contains(" verify") || line.contains(" package")
                        || line.contains(" compile") || line.contains(" install"))
                .toList();

        assertFalse(lifecycleBuilds.isEmpty(), CI_WORKFLOW + " runs no Maven lifecycle build at all");
        assertTrue(lifecycleBuilds.stream()
                .anyMatch(line -> !line.contains("-Dcheckstyle.skip") && !line.contains("-Dformatter.skip")),
                "every Maven lifecycle invocation in " + CI_WORKFLOW + " skips Checkstyle and/or the formatter, so"
                        + " neither gate can fail anything in CI. The downstream jobs may skip them (they are"
                        + " `needs: build-and-test`); the build-and-test job may not. Found: " + lifecycleBuilds);
    }

    /**
     * The {@code code} paths filter decides whether Build &amp; Test runs at all,
     * and a skipped required check still satisfies branch protection — so a file
     * the filter does not name can change with no job having graded it.
     * <p>
     * {@code README.md} and {@code AGENTS.md} are build inputs, not prose:
     * {@code ComposeStackTest} parses the README's compose commands and
     * {@link #checkstyleImportGateScopeMatchesTheDocs} reads AGENTS.md. Neither was
     * in the filter, so a PR that touched only one of those contracts skipped the
     * single test that grades it, and a stale compose command could merge green.
     * <p>
     * Derived from the test sources rather than listing the two by name: a
     * hard-coded pair stays green the day a third root document acquires a test and
     * misses the filter, which is the same ungraded-contract shape one document
     * along.
     */
    @Test
    @DisplayName("the CI paths filter covers every repo-root document a test grades")
    void ciCodeFilterCoversTheRootDocumentsTestsGrade() throws Exception {
        List<String> patterns = ciFilterPatterns("code");
        List<String> documents = rootDocumentsReadByTests();

        assertFalse(documents.isEmpty(),
                "found no repo-root document opened by any test, so this assertion grades nothing. The sweep looks"
                        + " for a single-segment Path.of literal ending in .md — if the tests now reach those files"
                        + " some other way, teach the sweep that shape rather than leaving it vacuous.");

        List<String> unfiltered = documents.stream().filter(document -> !patterns.contains(document)).toList();

        assertEquals(List.of(), unfiltered,
                "these repo-root documents are graded by a test but appear in no `code` path filter in " + CI_WORKFLOW
                        + ", so a PR that changes only one of them resolves code=false and skips Build & Test — and a"
                        + " skipped required check still satisfies branch protection, so it merges with the contract"
                        + " ungraded. Current filter: " + patterns);
    }

    /**
     * The globs listed under one named filter of the paths-filter step, unquoted.
     * The list ends at the next key, which is the only line inside the block that
     * is neither a comment nor a {@code - } item.
     */
    private static List<String> ciFilterPatterns(String filterName) throws IOException {
        List<String> patterns = new ArrayList<>();
        boolean inFilter = false;

        for (String line : read(CI_WORKFLOW).lines().toList()) {
            String stripped = line.strip();
            if (!inFilter) {
                inFilter = stripped.equals(filterName + ":");
                continue;
            }
            if (stripped.isEmpty() || stripped.startsWith("#")) {
                continue;
            }
            if (!stripped.startsWith("- ")) {
                break;
            }
            patterns.add(stripped.substring(2).strip().replace("'", "").replace("\"", ""));
        }

        assertFalse(patterns.isEmpty(), CI_WORKFLOW + " declares no '" + filterName + ":' paths filter, so nothing"
                + " here can be checked against it");
        return patterns;
    }

    /** Repo-root documents the test sources open, sorted and de-duplicated. */
    private static List<String> rootDocumentsReadByTests() throws IOException {
        Path testSources = Path.of("src", "test", "java");
        assertTrue(Files.isDirectory(testSources),
                "expected the working directory to be the project root; " + testSources.toAbsolutePath() + " not found");

        TreeSet<String> documents = new TreeSet<>();
        try (Stream<Path> paths = Files.walk(testSources)) {
            for (Path file : paths.filter(path -> path.getFileName().toString().endsWith(".java")).toList()) {
                Matcher matcher = ROOT_DOCUMENT_READ.matcher(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
                while (matcher.find()) {
                    documents.add(matcher.group(1));
                }
            }
        }
        return new ArrayList<>(documents);
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
     * The other half of the executable-bit fix: arming a guard also arms its bugs.
     * git hands a pre-push hook the pushed-to remote as {@code $1} — a configured
     * name ({@code origin}, the {@code upstream} of AGENTS.md §2 rule 3's fork
     * layout, a second maintainer remote) or a bare URL when pushing to one — and
     * the shallow-clone recovery fetch named {@code origin} literally while
     * referencing neither {@code $1} nor {@code $2}. On any other remote it
     * therefore queried the wrong repository, the retry missed exactly as the first
     * check did, and a legitimate fast-forward push was refused with a "your local
     * branch has diverged" message that was not true. Latent for as long as the
     * hook was committed 0644 and skipped outright;
     * {@link #executableBitsAreRecordedInGit} is what makes it reachable.
     * <p>
     * Graded from both ends, so re-hardcoding the remote under a different spelling
     * fails too: every {@code git fetch} in the hook has to name the captured
     * {@code "$remote"} and none may name a remote literally. An empty fetch list
     * fails as well — the recovery this grades would then be gone rather than
     * fixed.
     */
    @Test
    @DisplayName("the pre-push hook's recovery fetch queries the remote git passed it")
    void prePushHookFetchesFromThePushedToRemote() throws Exception {
        String hook = read(PRE_PUSH_HOOK);

        assertTrue(hook.contains("remote=\"$1\""),
                PRE_PUSH_HOOK + " must capture the remote git passes as $1. Without it the hook has no idea which"
                        + " repository is being pushed to, and the only remaining way to write the recovery fetch is"
                        + " to guess one.");

        List<String> fetches = hook.lines()
                .map(String::strip)
                .filter(line -> line.startsWith("git fetch"))
                .toList();

        assertFalse(fetches.isEmpty(),
                PRE_PUSH_HOOK + " no longer fetches anything, so this assertion grades nothing. The fetch exists to"
                        + " rescue a truncated-history clone, where the remote commit is simply absent locally and"
                        + " every push then reads as a non-fast-forward — restore it rather than deleting this.");

        List<String> offenders = fetches.stream()
                .filter(line -> !line.contains("\"$remote\"") || line.contains(" origin"))
                .toList();

        assertEquals(List.of(), offenders,
                "the recovery fetch must use \"$remote\" — the name (or URL) git handed the hook — and never a"
                        + " hardcoded one. Fetching origin while the push goes to upstream, or to a second maintainer"
                        + " remote, asks a different repository for the commit, so the retry fails the same way the"
                        + " first check did and the hook blocks a push that was a clean fast-forward.");
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

    /**
     * {@code base-image-check.yml} skips raising its digest PR when Dependabot
     * already has one open for the production Dockerfile. It decided that by branch
     * name alone — the first open {@code dependabot/docker/*} PR counted — which
     * was sound only while {@code dependabot.yml} declared a single Docker
     * ecosystem. It now declares three (/src/main/docker, /mcp-sidecar,
     * /.clusterfuzzlite) and all of them push branches under that prefix, so an
     * open sidecar bump reads as "the base image is covered" and suppresses the
     * required production digest PR for as long as it lives — silently, because the
     * skip path reports a green summary line.
     * <p>
     * Graded from both ends: the premise (more than one Docker ecosystem) and the
     * consequence (the skip has to consult the candidate's changed files). Drop
     * either half and the assertion is not vacuous — it fails.
     */
    @Test
    @DisplayName("the base-image job's Dependabot skip identifies the PR by changed file, not branch prefix")
    void baseImageDependabotSkipMatchesTheProductionDockerfile() throws Exception {
        List<String> dockerDirectories = dependabotDockerDirectories();

        assertTrue(dockerDirectories.contains(PRODUCTION_DOCKERFILE_DIRECTORY),
                DEPENDABOT + " no longer watches " + PRODUCTION_DOCKERFILE_DIRECTORY + ", so the skip this test"
                        + " grades has nothing to be about. Found: " + dockerDirectories);
        assertTrue(dockerDirectories.size() > 1,
                "only one docker ecosystem is declared, so this assertion would pass for the wrong reason. It exists"
                        + " because sibling Docker ecosystems share the dependabot/docker/ branch prefix — if they"
                        + " are gone, revisit the skip rather than deleting this. Found: " + dockerDirectories);

        String skipBlock = dependabotSkipBlock();

        assertTrue(skipBlock.contains("--json files"),
                "the Dependabot skip in " + BASE_IMAGE_WORKFLOW + " must ask each candidate PR which files it"
                        + " changes. Selecting on the dependabot/docker/ branch prefix alone matches the"
                        + " mcp-sidecar and .clusterfuzzlite ecosystems too, so their PRs suppress the production"
                        + " base-image digest PR. Block was:\n" + skipBlock);
        assertTrue(skipBlock.contains("\"$DOCKERFILE\""),
                "the skip must compare those changed files against $DOCKERFILE (" + PRODUCTION_DOCKERFILE_DIRECTORY
                        + "/Dockerfile); fetching the file list and not matching on it decides nothing. Block was:\n"
                        + skipBlock);
        assertTrue(skipBlock.contains("--app dependabot"),
                "the candidate listing must select Dependabot's PRs with `gh pr list --app dependabot`, the App"
                        + " filter. `--author app/dependabot` is the user filter and is not guaranteed to return"
                        + " App-authored PRs; an empty candidate list is silent here — the skip simply never fires"
                        + " and the job raises a digest PR duplicating Dependabot's. Block was:\n" + skipBlock);
    }

    /**
     * {@code echo … >> $GITHUB_STEP_SUMMARY} is an unquoted expansion in a
     * redirection — shellcheck SC2086, which actionlint reports for every workflow
     * in this repository. The runner's own paths are space-free today, so nothing
     * has broken; that is exactly why 83 of these accumulated, and a linter with 83
     * standing diagnostics is a linter nobody reads the 84th line of. This branch
     * arms the build's gates, so the workflows get held to the same bar: quote them
     * once, and keep them quoted.
     */
    @Test
    @DisplayName("every GitHub environment-file redirection in the workflows quotes the path")
    void githubEnvironmentFileRedirectionsAreQuoted() throws Exception {
        List<Path> workflows;
        try (Stream<Path> paths = Files.list(WORKFLOWS)) {
            workflows = paths.filter(path -> path.getFileName().toString().endsWith(".yml")).sorted().toList();
        }
        assertFalse(workflows.isEmpty(),
                "found no workflow under " + WORKFLOWS.toAbsolutePath() + ", so this sweep grades nothing —"
                        + " teach it where the workflows moved rather than leaving it vacuous");

        List<String> unquoted = new ArrayList<>();
        for (Path workflow : workflows) {
            List<String> lines = read(workflow).lines().toList();
            for (int i = 0; i < lines.size(); i++) {
                if (UNQUOTED_GITHUB_FILE_REDIRECTION.matcher(lines.get(i)).find()) {
                    unquoted.add(workflow + ":" + (i + 1) + "  " + lines.get(i).strip());
                }
            }
        }

        assertEquals(List.of(), unquoted,
                "these redirections expand a runner-supplied path unquoted (shellcheck SC2086, via actionlint)."
                        + " Write >> \"$GITHUB_STEP_SUMMARY\", not >> $GITHUB_STEP_SUMMARY");
    }

    /**
     * The directory of every {@code package-ecosystem: docker} entry in
     * {@code dependabot.yml}, in file order.
     */
    private static List<String> dependabotDockerDirectories() throws IOException {
        List<String> directories = new ArrayList<>();
        boolean inDockerEcosystem = false;

        for (String line : read(DEPENDABOT).lines().toList()) {
            String stripped = line.strip();
            if (stripped.startsWith("- package-ecosystem:")) {
                inDockerEcosystem = stripped.endsWith("docker");
            } else if (inDockerEcosystem && stripped.startsWith("directory:")) {
                directories.add(stripped.substring("directory:".length()).strip().replace("\"", "").replace("'", ""));
                inDockerEcosystem = false;
            }
        }
        return directories;
    }

    /**
     * The shell lines of the Dependabot de-duplication in
     * {@code base-image-check.yml}: from the first {@code DEPENDABOT_PR=}
     * assignment through the guard that acts on it. Everything that decides the
     * skip is inside; a decision made outside it would leave the guard reading a
     * variable nothing set.
     */
    private static String dependabotSkipBlock() throws IOException {
        List<String> lines = read(BASE_IMAGE_WORKFLOW).lines().toList();
        int start = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).strip().startsWith("DEPENDABOT_PR=")) {
                start = i;
                break;
            }
        }
        assertTrue(start >= 0, BASE_IMAGE_WORKFLOW + " no longer sets DEPENDABOT_PR, so the skip this test grades"
                + " is gone — remove the test deliberately or restore the guard, do not leave it passing vacuously");

        boolean guarded = false;
        StringBuilder block = new StringBuilder();
        for (int i = start; i < lines.size(); i++) {
            block.append(lines.get(i)).append('\n');
            if (lines.get(i).strip().startsWith("if [ -n \"$DEPENDABOT_PR\" ]")) {
                guarded = true;
                break;
            }
        }
        // Without this the extraction degrades into "everything from DEPENDABOT_PR=
        // to end of file" when the guard is deleted, and the rest of the workflow
        // still contains both `--json files` and `"$DOCKERFILE"` further down — so
        // the caller's assertions would go on passing while the skip they grade no
        // longer exists. A sweep that cannot find its end marker has to say so.
        assertTrue(guarded, BASE_IMAGE_WORKFLOW + " sets DEPENDABOT_PR but nothing guards on it any more:"
                + " no `if [ -n \"$DEPENDABOT_PR\" ]` follows the assignment, so the de-duplication decides"
                + " nothing and the digest PR is raised on top of Dependabot's");
        return block.toString();
    }

    /**
     * A Jackson data-format module used by the sources but only reachable through
     * somebody else's dependency tree is a compile that breaks on an unrelated
     * upgrade. {@code jackson-dataformat-yaml} was exactly that: {@code
     * ComposeStackTest} and {@code InfrastructureIT} parse the compose files with
     * {@code YAMLMapper} while the pom declared only the CSV and XML modules —
     * {@code quarkus-jackson} does not supply YAML, so the classpath entry came
     * from {@code json-schema-validator}'s transitive tree and would have vanished
     * with it.
     */
    @Test
    @DisplayName("every Jackson data-format module the sources import is declared in pom.xml")
    void jacksonDataFormatModulesAreDeclaredDirectly() throws Exception {
        TreeSet<String> imported = new TreeSet<>();
        for (Path sourceRoot : List.of(Path.of("src", "main", "java"), Path.of("src", "test", "java"))) {
            assertTrue(Files.isDirectory(sourceRoot),
                    "expected the working directory to be the project root; " + sourceRoot.toAbsolutePath() + " not found");
            try (Stream<Path> paths = Files.walk(sourceRoot)) {
                for (Path file : paths.filter(path -> path.getFileName().toString().endsWith(".java")).toList()) {
                    Matcher matcher = JACKSON_DATAFORMAT_IMPORT.matcher(Files.readString(file, StandardCharsets.UTF_8));
                    while (matcher.find()) {
                        imported.add("jackson-dataformat-" + matcher.group(1));
                    }
                }
            }
        }

        assertFalse(imported.isEmpty(),
                "found no com.fasterxml.jackson.dataformat import anywhere in the sources, so this assertion grades"
                        + " nothing — teach the sweep the shape the imports now take rather than leaving it vacuous");

        Element dependencies = child(parse(POM).getDocumentElement(), "dependencies").orElseThrow();
        TreeSet<String> declared = new TreeSet<>();
        for (Element dependency : children(dependencies, "dependency")) {
            if ("com.fasterxml.jackson.dataformat".equals(childText(dependency, "groupId"))) {
                declared.add(childText(dependency, "artifactId"));
            }
        }

        List<String> undeclared = imported.stream().filter(module -> !declared.contains(module)).toList();

        assertEquals(List.of(), undeclared,
                "these Jackson data-format modules are imported by the sources but declared by no direct dependency"
                        + " in pom.xml, so they are on the classpath only for as long as some other artifact keeps"
                        + " dragging them in — an unrelated dependency bump then breaks the compile. Declared: "
                        + declared);
    }
}
