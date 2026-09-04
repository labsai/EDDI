/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the one property the container ITs depend on: that the image they
 * build is the production image.
 * <p>
 * A duplicate Dockerfile lived in {@code ContainerBaseIT} as a text block, and
 * it had drifted three ways from {@code src/main/docker/Dockerfile} without
 * anything noticing — most consequentially by dropping the {@code @sha256}
 * digest from the base image, so the ITs could validate a different base layer
 * than the release is pinned to. Nothing in the build could see that, because
 * the two files were only ever compared by eye.
 * <p>
 * These are plain unit tests: they read files and never start Docker, so they
 * run in the ordinary {@code mvn test} pass rather than only in the IT job that
 * the drift was invisible to.
 */
@DisplayName("container IT image derivation")
class EddiImageDockerfileTest {

    private static final Pattern FROM_LINE = Pattern.compile("(?m)^FROM\\s+(\\S+)");

    /**
     * The first argument of a Testcontainers
     * {@code withFileFromPath}/{@code withFileFromString} call — the path the entry
     * takes inside the build context.
     */
    private static final Pattern CONTEXT_ENTRY = Pattern.compile("withFileFrom(?:Path|String)\\(\\s*\"([^\"]+)\"");

    private static String productionDockerfile() throws IOException {
        Path path = EddiImageDockerfile.PRODUCTION_DOCKERFILE;
        assertTrue(Files.isRegularFile(path),
                "expected the working directory to be the project root; " + path.toAbsolutePath() + " not found");
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("every FROM in the production Dockerfile is digest-pinned")
    void productionBaseImagesAreDigestPinned() throws IOException {
        List<String> images = FROM_LINE.matcher(productionDockerfile()).results()
                .map(m -> m.group(1)).toList();

        assertFalse(images.isEmpty(), "no FROM instruction found");
        for (String image : images) {
            assertTrue(image.contains("@sha256:"),
                    "FROM " + image + " is not digest-pinned — OpenSSF supply-chain compliance requires the digest,"
                            + " and base-image-check.yml rewrites it in place (see AGENTS.md, Trivy CVE Remediation)");
        }
    }

    @Test
    @DisplayName("the ITs' Dockerfile keeps the production base images, digest and all")
    void derivedDockerfileKeepsTheProductionBaseImages() throws IOException {
        List<String> production = FROM_LINE.matcher(productionDockerfile()).results()
                .map(m -> m.group(1)).toList();
        List<String> derived = FROM_LINE.matcher(EddiImageDockerfile.forTestContext()).results()
                .map(m -> m.group(1)).toList();

        assertEquals(production, derived,
                "the ITs must start from exactly the base image the release is pinned to");
    }

    @Test
    @DisplayName("the ITs' Dockerfile keeps the production runtime contract")
    void derivedDockerfileKeepsTheRuntimeContract() throws IOException {
        String production = productionDockerfile();
        String derived = EddiImageDockerfile.forTestContext();

        // The lines that decide whether the container comes up at all. They were
        // byte-identical across the two hand-maintained files by coincidence,
        // which is what made the rest of the drift easy to miss.
        for (String instruction : List.of("ENV JAVA_OPTS_APPEND=", "ENV JAVA_APP_JAR=", "ENTRYPOINT ", "HEALTHCHECK ")) {
            assertEquals(lineStartingWith(production, instruction), lineStartingWith(derived, instruction),
                    instruction + " differs between the production Dockerfile and the one the ITs build");
        }
    }

    @Test
    @DisplayName("only the build-context prefix is rewritten")
    void rewriteTouchesOnlyTheBuildContextPrefix() throws IOException {
        String production = productionDockerfile();
        String derived = EddiImageDockerfile.forTestContext();

        assertFalse(derived.contains(EddiImageDockerfile.PRODUCTION_APP_PREFIX),
                "the ITs' context maps target/quarkus-app in as quarkus-app, so no target/ path may survive");
        assertEquals(production.replace(EddiImageDockerfile.PRODUCTION_APP_PREFIX, EddiImageDockerfile.TEST_APP_PREFIX),
                derived, "the derivation must be that single substitution and nothing else");
    }

    @Test
    @DisplayName("a production Dockerfile that stops matching fails loudly")
    void aProductionDockerfileThatStopsMatchingFailsLoudly() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> EddiImageDockerfile.forTestContext("FROM scratch\nCOPY build/app /deployments/\n"));
        assertTrue(e.getMessage().contains(EddiImageDockerfile.PRODUCTION_APP_PREFIX), e.getMessage());
    }

    /**
     * U1. The highest-consequence untested path on this branch: the derived
     * Dockerfile is now handed to a real builder, and since this change the
     * production file is also MULTI-STAGE ({@code FROM … AS docs}, then
     * {@code COPY --from=docs}). Nothing here can start Docker, but the one thing
     * that would break every container IT at once — a COPY whose source is not in
     * the context {@code buildEddiImage} assembles, or a {@code --from} naming a
     * stage that does not exist — is decidable from the text.
     * <p>
     * Without this the failure surfaces as {@code COPY failed: file not found}
     * during the image build in the CI integration job, which reads as an
     * infrastructure problem rather than as "someone added a COPY to the production
     * Dockerfile and did not extend the ITs' context".
     */
    @Test
    @DisplayName("every COPY in the derived Dockerfile resolves inside the ITs' build context")
    void everyCopyResolvesInsideTheItsBuildContext() {
        String derived = EddiImageDockerfile.forTestContext();

        Set<String> stages = new LinkedHashSet<>();
        List<String> offenders = new ArrayList<>();
        int copies = 0;

        for (String raw : derived.lines().toList()) {
            String line = raw.trim();
            if (line.regionMatches(true, 0, "FROM ", 0, 5)) {
                String[] parts = line.split("\\s+");
                for (int i = 1; i < parts.length - 1; i++) {
                    if ("AS".equalsIgnoreCase(parts[i])) {
                        stages.add(parts[i + 1].toLowerCase(Locale.ROOT));
                    }
                }
                continue;
            }
            if (!line.regionMatches(true, 0, "COPY ", 0, 5)) {
                continue;
            }
            copies++;

            List<String> args = new ArrayList<>();
            String stageRef = null;
            for (String token : line.substring(5).trim().split("\\s+")) {
                if (token.startsWith("--from=")) {
                    stageRef = token.substring("--from=".length()).toLowerCase(Locale.ROOT);
                } else if (!token.startsWith("--")) {
                    args.add(token);
                }
            }

            if (stageRef != null) {
                // A --from copies out of an earlier stage's filesystem, never out of
                // the build context, so its sources are not this test's business —
                // but the stage has to exist, or the builder reads it as an image
                // name and tries to pull it.
                if (!stages.contains(stageRef)) {
                    offenders.add(line + "  (--from=" + stageRef + " names no earlier stage; declared: " + stages + ")");
                }
                continue;
            }

            // The last argument is the destination; everything before it is a
            // context source.
            for (String source : args.subList(0, Math.max(args.size() - 1, 0))) {
                String root = source.replace('\\', '/').split("/")[0];
                if (!EddiImageDockerfile.CONTEXT_ROOTS.contains(root)) {
                    offenders.add(line + "  (context root '" + root + "' is not supplied by ContainerBaseIT#buildEddiImage)");
                }
            }
        }

        assertTrue(copies > 0, "no COPY instruction found — the parse is wrong, not the Dockerfile");
        assertEquals(List.of(), offenders,
                "the ITs' build context holds exactly " + EddiImageDockerfile.CONTEXT_ROOTS + ". Anything else fails"
                        + " the image build inside Testcontainers on the first CI run — add the directory to"
                        + " ContainerBaseIT#buildEddiImage, or the missing stage to the Dockerfile.");
    }

    /**
     * U1, the other half: the duplicate must not come back. A second Dockerfile
     * embedded as a Java text block is invisible to every gate in the build — it
     * compiles, it runs, and it silently starts a different image from the one the
     * release ships. That is precisely how the base-image digest, the pruned docs
     * tree and the HEALTHCHECK drifted apart unnoticed.
     */
    @Test
    @DisplayName("the container ITs embed no second Dockerfile")
    void theContainerItsEmbedNoSecondDockerfile() throws IOException {
        Path source = Path.of("src", "test", "java", "ai", "labs", "eddi", "integration", "ContainerBaseIT.java");
        assertTrue(Files.isRegularFile(source), source.toAbsolutePath() + " not found");
        String text = Files.readString(source, StandardCharsets.UTF_8);

        for (String instruction : List.of("FROM ", "ENTRYPOINT ", "HEALTHCHECK ", "ENV JAVA_")) {
            assertFalse(text.contains(instruction),
                    source + " contains the Dockerfile instruction '" + instruction.trim() + "'. The ITs must build"
                            + " the production Dockerfile through EddiImageDockerfile#forTestContext(); a copy kept"
                            + " here drifts from src/main/docker/Dockerfile with nothing able to notice, so the"
                            + " image the ITs prove bootable stops being the image that gets pushed.");
        }
    }

    /**
     * U1, the third half. {@link #everyCopyResolvesInsideTheItsBuildContext} grades
     * the Dockerfile against {@link EddiImageDockerfile#CONTEXT_ROOTS}, and that
     * list is only as good as its agreement with the context {@code buildEddiImage}
     * actually assembles. Adding a root there and not here merely makes this class
     * stricter than reality — harmless. Dropping one (the {@code docs/} block is
     * the obvious candidate: it looks like an optional convenience and its fallback
     * makes it look safe to delete) leaves the sweep grading a root the builder no
     * longer receives, so {@code COPY docs/*.md /docs/} fails inside Testcontainers
     * with the sweep still green.
     */
    @Test
    @DisplayName("the ITs' build context supplies exactly the roots the sweep grades against")
    void theItsContextSuppliesExactlyTheDeclaredRoots() throws IOException {
        Path source = Path.of("src", "test", "java", "ai", "labs", "eddi", "integration", "ContainerBaseIT.java");
        assertTrue(Files.isRegularFile(source), source.toAbsolutePath() + " not found");
        String text = Files.readString(source, StandardCharsets.UTF_8);

        Set<String> supplied = new TreeSet<>();
        Matcher entries = CONTEXT_ENTRY.matcher(text);
        while (entries.find()) {
            String root = entries.group(1).split("/")[0];
            // The Dockerfile itself is content, not a context directory.
            if (!"Dockerfile".equals(root)) {
                supplied.add(root);
            }
        }

        assertEquals(new TreeSet<>(EddiImageDockerfile.CONTEXT_ROOTS), supplied,
                "ContainerBaseIT#buildEddiImage and EddiImageDockerfile.CONTEXT_ROOTS have to name the same"
                        + " directories: the first is what Testcontainers receives, the second is what"
                        + " everyCopyResolvesInsideTheItsBuildContext checks the production Dockerfile against."
                        + " While they disagree, that sweep is grading a context nobody builds.");
    }

    private static String lineStartingWith(String dockerfile, String prefix) {
        return dockerfile.lines()
                .filter(line -> line.startsWith(prefix))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no line starting with '" + prefix + "' in the Dockerfile"));
    }
}
