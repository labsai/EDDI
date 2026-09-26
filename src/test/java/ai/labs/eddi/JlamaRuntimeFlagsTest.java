/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi;

import ai.labs.eddi.modules.llm.impl.builder.JlamaRuntimeSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Keeps {@code --add-modules=jdk.incubator.vector} on every JVM this repository
 * starts.
 *
 * <p>
 * The flag has to be set in five places across four files — the production
 * image's {@code JDK_JAVA_OPTIONS}, the demo image's bare {@code java}
 * ENTRYPOINT, surefire's {@code argLine}, and both mise tasks that fork a dev
 * JVM — and losing it from any one of them fails <em>nothing</em>. Jlama
 * catches the resulting {@link NoClassDefFoundError} internally, logs one line
 * through its own logger and runs at scalar speed instead, so the only symptom
 * is an agent that answers too slowly to use. That is a bad way to find out,
 * and it is how the flag came to be missing in the first place: the provider
 * was registered, documented, counted among the supported providers and offered
 * by the setup wizard, without any JVM in the project ever resolving the
 * module.
 * <p>
 * Five is also why the check is mechanical rather than a review convention. Two
 * of the five were missed on successive passes of the very change that
 * introduced this test — the demo image, then {@code mise run debug} — and in
 * both cases the check as written at the time could not see the omission.
 *
 * <p>
 * These are plain file reads — no Docker, no Quarkus — so they run in the
 * ordinary {@code mvn test} pass, where the person who changed the file will
 * see them.
 *
 * <p>
 * The companion check lives in
 * {@code JlamaRuntimeSupportTest#vectorApiIsReachableInThisJvm}: this class
 * proves the flag is <em>written down</em>, that one proves it actually took
 * effect in a running JVM. Both are needed — a typo like
 * {@code --add-module=...} would satisfy neither, but a flag placed in a
 * surefire configuration block that Maven never applies would satisfy only this
 * one.
 */
@DisplayName("Jlama runtime flags")
class JlamaRuntimeFlagsTest {

    private static final Path DOCKERFILE = Path.of("src", "main", "docker", "Dockerfile");
    private static final Path DEMO_DOCKERFILE = Path.of("src", "main", "docker", "Dockerfile.demo");
    private static final Path POM = Path.of("pom.xml");
    private static final Path MISE = Path.of("mise.toml");
    private static final Path README = Path.of("README.md");

    /** The flag, taken from production code so the two cannot drift apart. */
    private static final String FLAG = JlamaRuntimeSupport.REQUIRED_JVM_FLAG;

    /**
     * {@code <argLine>} bodies in {@code pom.xml}. Matched rather than searching
     * the whole file so that the flag has to be somewhere Maven will actually pass
     * to a forked JVM — a mention in a comment, a property or a dependency would
     * otherwise satisfy a naive {@code contains}.
     */
    private static final Pattern ARG_LINE = Pattern.compile("<argLine>(.*?)</argLine>", Pattern.DOTALL);

    private static String read(Path path) {
        assertTrue(Files.isRegularFile(path),
                "expected the working directory to be the project root; " + path.toAbsolutePath() + " not found");
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + path.toAbsolutePath(), e);
        }
    }

    /**
     * The flag must ride on {@code JDK_JAVA_OPTIONS}, not on {@code JAVA_OPTS} or
     * {@code JAVA_OPTS_APPEND}.
     * <p>
     * The java launcher reads {@code JDK_JAVA_OPTIONS} itself, so it survives an
     * operator overriding either of the other two — and operators do override them:
     * {@code docs/setup-eddi-on-aws-with-mongodb-atlas.md} sets
     * {@code JAVA_OPTS_APPEND} to a MongoDB connection string and nothing else,
     * which replaces the image's value wholesale. A flag parked there would be
     * silently dropped by exactly the deployments most likely to run a local model.
     */
    @Test
    @DisplayName("the container image passes the flag on JDK_JAVA_OPTIONS, which survives overrides")
    void containerImagePassesTheFlag() {
        String dockerfile = read(DOCKERFILE);

        String jdkJavaOptions = dockerfile.lines()
                .filter(line -> line.startsWith("ENV JDK_JAVA_OPTIONS="))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "no active 'ENV JDK_JAVA_OPTIONS=' line in " + DOCKERFILE + ". The flag must be there rather"
                                + " than on JAVA_OPTS_APPEND: an operator who sets JAVA_OPTS_APPEND at runtime"
                                + " replaces the image's value and would silently lose it."));

        assertTrue(jdkJavaOptions.contains(FLAG),
                "JDK_JAVA_OPTIONS must carry '" + FLAG + "' or the published image runs Jlama on scalar tensor"
                        + " operations. Line was: " + jdkJavaOptions);
    }

    /**
     * The demo image ships a second, unrelated way of starting EDDI: a bare
     * {@code java} ENTRYPOINT, with no {@code run-java.sh} and no
     * {@code JAVA_OPTS_APPEND} to inherit the production image's options from. It
     * was missed on the first pass of this change for exactly that reason — "the
     * Dockerfile" is not one file.
     */
    @Test
    @DisplayName("the demo image's bare java ENTRYPOINT passes the flag")
    void demoImageEntrypointPassesTheFlag() {
        String demo = read(DEMO_DOCKERFILE);

        String entrypoint = demo.lines()
                .filter(line -> line.startsWith("ENTRYPOINT"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no ENTRYPOINT in " + DEMO_DOCKERFILE));

        assertTrue(entrypoint.contains(FLAG),
                "the demo image starts EDDI with a bare `java` command, so it cannot inherit the production"
                        + " image's JAVA_OPTS_APPEND — the flag must appear in the ENTRYPOINT array itself."
                        + " Line was: " + entrypoint);
    }

    /**
     * Surefire, and <em>only</em> surefire.
     * <p>
     * The flag must not be added to failsafe. Failsafe's {@code argLine}
     * <em>parameter</em> defaults to the {@code ${argLine}} property, which both
     * {@code jacoco:prepare-agent-integration} and the Quarkus Maven extension
     * populate — so the absence of an {@code <argLine>} element is not the absence
     * of an agent. Declaring one replaces the lot: the JaCoCo IT agent (feeding the
     * merged 90/80 coverage gate), the add-opens/add-exports Quarkus injects, and
     * the serialized app-model path. This test therefore asserts the flag is on
     * exactly one {@code <argLine>}, so a well-meaning "make the ITs match the
     * image" change fails here rather than quietly costing the IT coverage data.
     */
    @Test
    @DisplayName("the surefire fork passes the flag, and no other argLine does")
    void onlyTheSurefireForkPassesTheFlag() {
        Matcher argLines = ARG_LINE.matcher(read(POM));

        int carryingTheFlag = 0;
        int total = 0;
        StringBuilder seen = new StringBuilder();
        while (argLines.find()) {
            total++;
            seen.append("\n  ").append(argLines.group(1).trim());
            if (argLines.group(1).contains(FLAG)) {
                carryingTheFlag++;
            }
        }

        assertEquals(1, carryingTheFlag,
                "exactly one <argLine> in " + POM + " must carry '" + FLAG + "' — surefire's. Adding it to"
                        + " failsafe replaces the implicit ${argLine} default and drops the JaCoCo IT agent and"
                        + " Quarkus's module opens. Found " + carryingTheFlag + " of " + total + ":" + seen);
    }

    /**
     * Every mise task that starts a dev JVM, not just the first one found.
     * {@code dev} and {@code debug} both run {@code quarkus:dev}, and a
     * {@code findFirst} here would have let {@code debug} drift silently — which it
     * had.
     */
    @Test
    @DisplayName("every mise task that starts quarkus:dev passes the flag")
    void everyMiseDevTaskPassesTheFlag() {
        List<String> devRuns = read(MISE).lines()
                .filter(line -> line.startsWith("run = ") && line.contains("quarkus:dev"))
                .toList();

        assertTrue(devRuns.size() >= 2,
                "expected at least the 'dev' and 'debug' tasks to invoke quarkus:dev in " + MISE + "; found "
                        + devRuns.size() + ". If a task was renamed or removed, update this check rather than"
                        + " letting it grade fewer lines than it used to");

        for (String devRun : devRuns) {
            assertTrue(devRun.contains(FLAG),
                    "every mise task that forks a dev JVM must forward '" + FLAG + "' (via -Djvm.args), or a Jlama"
                            + " agent behaves differently in dev than in the image. Line was: " + devRun);
        }
    }

    /**
     * The README's dev-mode commands are what most contributors copy, and they
     * started {@code quarkus:dev} without the flag while {@code mise run dev}
     * passed it — so the same checkout ran Jlama at full speed or at scalar speed
     * depending on which instructions its owner followed.
     */
    @Test
    @DisplayName("every README command that starts quarkus:dev passes the flag")
    void readmeDevCommandsPassTheFlag() {
        List<String> devCommands = read(README).lines()
                .filter(line -> line.contains("mvnw") && line.contains("quarkus:dev"))
                .toList();

        assertTrue(devCommands.size() >= 3,
                "expected README.md's Linux and Windows dev-mode commands and its command table to start quarkus:dev;"
                        + " found " + devCommands.size());
        for (String command : devCommands) {
            assertTrue(command.contains("-Djvm.args=" + FLAG),
                    "README.md must pass -Djvm.args=" + FLAG + " wherever it starts quarkus:dev. Line was: " + command);
        }
    }

    /**
     * The files carry the flag for the same reason, and a future reader changing
     * one needs to find the others. Each therefore names this test next to the
     * flag; pinning that cross-reference is what stops them rotting into four
     * unexplained JVM flags that the next person deletes as cargo cult.
     */
    @Test
    @DisplayName("each file explains why the flag is there")
    void eachFileExplainsTheFlag() {
        assertTrue(read(DOCKERFILE).contains("JlamaRuntimeFlagsTest"),
                DOCKERFILE + " must name this test next to the flag, so whoever edits JAVA_OPTS_APPEND learns the"
                        + " flag is load-bearing before the build tells them");
        assertTrue(read(DEMO_DOCKERFILE).contains("JlamaRuntimeFlagsTest"),
                DEMO_DOCKERFILE + " must name this test next to its ENTRYPOINT for the same reason");
        assertTrue(read(POM).contains("JlamaRuntimeSupportTest"),
                POM + " must name the test that proves the surefire flag took effect");
        assertTrue(read(MISE).contains("JlamaRuntimeFlagsTest"),
                MISE + " must name this test next to the dev task's -Djvm.args");
    }
}
