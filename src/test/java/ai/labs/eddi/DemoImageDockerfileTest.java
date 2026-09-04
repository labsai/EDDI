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
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the demo image's runtime contract, because nothing builds it.
 * <p>
 * {@code src/main/docker/Dockerfile.demo} appears in no workflow — its only
 * consumer is {@code docker-compose.openwebui.yml}'s {@code build:} block,
 * which no CI job runs. So the three behavioural edits it just received are
 * otherwise verified by nobody, and a mistake in them shows up only when a
 * newcomer runs the documented Open WebUI demo, which is the worst possible
 * audience for it.
 * <p>
 * What was wrong: {@code licenses/} and {@code docs/} were staged into the
 * build stage and never copied out, so {@code /licenses} and
 * {@code /deployments/docs} did not exist in the final image at all.
 * {@code DocsService} fell back to its relative default, found nothing, logged
 * a single WARN, and every documentation surface in the demo — the REST
 * list/read endpoints plus the MCP doc resources and tools, which all delegate
 * there — reported zero documents. The demo exists to show the
 * OpenAI-compatible adapter off; an agent that answers "there are no docs" is a
 * poor first impression, and the two dead COPY lines made it look deliberate.
 * <p>
 * These are text assertions over a Dockerfile, deliberately: they run in the
 * ordinary {@code mvn test} pass, which is the only gate this file is ever
 * guaranteed to cross.
 */
@DisplayName("demo image contract")
class DemoImageDockerfileTest {

    private static final Path DEMO = Path.of("src", "main", "docker", "Dockerfile.demo");
    private static final Path PRODUCTION = Path.of("src", "main", "docker", "Dockerfile");

    /**
     * {@code COPY [--flags] <src>… <dest>} — group 1 is the whole argument list.
     */
    private static final Pattern COPY = Pattern.compile("(?mi)^COPY\\s+(.+)$");

    /**
     * {@code rm -f a.md b.md …} — group 1 is the argument list, matched after
     * {@link #joinContinuations(String)} has folded the instruction onto one line.
     */
    private static final Pattern RM = Pattern.compile("(?mi)^RUN\\s+rm\\s+-f\\s+(.*)$");

    /** A backslash line continuation and the indentation that follows it. */
    private static final Pattern CONTINUATION = Pattern.compile("\\\\\\s*\\n\\s*");

    /** {@code -Deddi.docs.path=/deployments/docs} inside the ENTRYPOINT array. */
    private static final Pattern DOCS_PATH = Pattern.compile("-Deddi\\.docs\\.path=([^\"\\s]+)");

    /**
     * Line endings are normalised to LF: a checkout on Windows can materialise
     * CRLF, and a backslash line continuation is only a continuation when the
     * backslash is the last character before the newline.
     */
    private static String read(Path path) throws IOException {
        assertTrue(Files.isRegularFile(path),
                "expected the working directory to be the project root; " + path.toAbsolutePath() + " not found");
        return Files.readString(path, StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    /** The portion of a Dockerfile from its LAST {@code FROM} onwards. */
    private static String runtimeStage(String dockerfile) {
        List<String> lines = dockerfile.lines().toList();
        int lastFrom = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).regionMatches(true, 0, "FROM ", 0, 5)) {
                lastFrom = i;
            }
        }
        assertTrue(lastFrom >= 0, "no FROM instruction found");
        return String.join("\n", lines.subList(lastFrom, lines.size()));
    }

    /** Every {@code COPY} destination in the given stage, as written. */
    private static List<String> copyDestinations(String stage) {
        return COPY.matcher(stage).results()
                .map(result -> {
                    String[] tokens = result.group(1).trim().split("\\s+");
                    return tokens[tokens.length - 1];
                })
                .toList();
    }

    /**
     * Folds backslash line continuations, so a multi-line {@code RUN} reads as the
     * single instruction the builder sees. The production prune spans four lines;
     * the demo's is one.
     */
    private static String joinContinuations(String dockerfile) {
        return CONTINUATION.matcher(dockerfile).replaceAll(" ");
    }

    /** Bare file names in every {@code RUN rm -f} of the given text. */
    private static TreeSet<String> prunedFileNames(String text) {
        TreeSet<String> pruned = new TreeSet<>();
        Matcher matcher = RM.matcher(joinContinuations(text));
        while (matcher.find()) {
            for (String token : matcher.group(1).trim().split("\\s+")) {
                if (token.endsWith(".md")) {
                    pruned.add(token.substring(token.lastIndexOf('/') + 1));
                }
            }
        }
        return pruned;
    }

    /**
     * The fix itself: the runtime stage has to receive both trees, and the
     * documentation path the app is told to read has to be the one they landed in.
     * A COPY without the matching {@code -Deddi.docs.path} leaves
     * {@code DocsService} on its relative default and the demo back to zero
     * documents, which is the failure that motivated the change.
     */
    @Test
    @DisplayName("the demo runtime stage receives licenses and docs, and is told where the docs are")
    void demoRuntimeStageShipsLicensesAndDocs() throws IOException {
        String demo = read(DEMO);
        String runtime = runtimeStage(demo);
        List<String> destinations = copyDestinations(runtime);

        assertTrue(destinations.stream().anyMatch(destination -> destination.startsWith("/licenses")),
                "the demo runtime stage copies nothing into /licenses — the build stage stages licenses/ and it has"
                        + " to be copied out again, or the directory does not exist in the image. Destinations: "
                        + destinations);

        String docsDestination = destinations.stream()
                .filter(destination -> destination.startsWith("/deployments/docs"))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "the demo runtime stage copies nothing into /deployments/docs, so every documentation surface"
                                + " reports zero documents with only a WARN in the log. Destinations: " + destinations));

        Matcher docsPath = DOCS_PATH.matcher(demo);
        assertTrue(docsPath.find(),
                "the demo ENTRYPOINT sets no -Deddi.docs.path, so DocsService falls back to its relative default"
                        + " `docs` and never finds the pages the runtime stage just copied in");
        assertEquals(trimTrailingSlash(docsDestination), trimTrailingSlash(docsPath.group(1)),
                "-Deddi.docs.path must name the directory the docs were copied into; a mismatch is the same"
                        + " zero-documents failure with an extra step");
    }

    /**
     * The demo prunes the internal pages in its build stage, the production image
     * in its {@code docs} stage. Two files, one policy: the 1.1 MB engineering
     * changelog, the breach-response runbook with its detection indicators and
     * escalation contacts, the internal code-review process, and a table of
     * contents whose links point at directories that are not in either image.
     * Nothing but this couples them, and the demo is the copy nobody builds.
     */
    @Test
    @DisplayName("the demo prunes exactly the internal pages the production image prunes")
    void demoPrunesTheSameInternalPagesAsProduction() throws IOException {
        TreeSet<String> production = prunedFileNames(read(PRODUCTION));
        TreeSet<String> demo = prunedFileNames(read(DEMO));

        assertTrue(production.contains("changelog.md") && production.contains("incident-response.md"),
                "the production Dockerfile no longer prunes the internal pages, or the parse missed it: " + production);
        assertEquals(production, demo,
                "the two images must drop the same internal pages. The demo is the one nothing builds, so a page"
                        + " added to the production prune and forgotten here ships the breach-response runbook and"
                        + " the engineering changelog to anyone who runs the documented Open WebUI demo.");
    }

    /**
     * New coupling introduced by this branch: Checkstyle and the formatter are both
     * bound to the {@code validate} phase and can now FAIL, so the demo's
     * {@code mvn package} dies before compiling anything unless their config files
     * are in the build context. They are — but the demo is built by no CI job, so
     * nothing else would notice if one of the two COPY lines were dropped as
     * "unused".
     */
    @Test
    @DisplayName("the demo build stage carries the config the blocking style gates need")
    void demoBuildStageCarriesTheStyleGateConfig() throws IOException {
        String demo = read(DEMO);
        int packageAt = demo.indexOf("mvn -B package");
        assertTrue(packageAt > 0, "the demo no longer runs `mvn -B package`; this test is reading the wrong file");
        String beforePackage = demo.substring(0, packageAt);

        for (String config : List.of("checkstyle.xml", "eclipse-formatter.xml")) {
            assertTrue(beforePackage.contains(config),
                    config + " must be COPYed into the demo build stage before `mvn package`. Checkstyle's import"
                            + " rules and formatter:validate are bound to the validate phase and now fail the build,"
                            + " so a missing config file kills the demo image build before a single class compiles.");
        }
    }

    private static String trimTrailingSlash(String path) {
        return path.endsWith("/") && path.length() > 1 ? path.substring(0, path.length() - 1) : path;
    }
}
