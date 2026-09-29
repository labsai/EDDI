/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Runs {@code scripts/bump-version.py} against a small synthetic repository and
 * checks what it rewrote.
 * <p>
 * The script is what CI runs unattended after every release
 * ({@code post-release.yml}), so its decisions — whether to move the release
 * pointers, whether to move the pom, by how much to bump the chart — have to be
 * right without a human looking. {@link ReleaseVersionSourceTest} checks the
 * repository's current state; this checks the transitions that produce it.
 * <p>
 * The fixture is synthetic rather than a copy of the real files so the
 * assertions do not move every release. It reads the real
 * {@code scripts/release-pointers.json}, so a pattern change is exercised here
 * too. It needs a Python 3 interpreter. CI's runners have one, and there a
 * missing interpreter FAILS the class: a guard that quietly turns into
 * "skipped" is not a guard. On a developer machine without Python it is
 * skipped.
 */
@DisplayName("scripts/bump-version.py")
class BumpVersionScriptTest {

    private static final Path SCRIPT = Path.of("scripts", "bump-version.py").toAbsolutePath();

    private static String python;

    @TempDir
    Path repo;

    @BeforeAll
    static void findPython() {
        for (String candidate : List.of("python3", "python")) {
            try {
                Process p = new ProcessBuilder(candidate, "--version").redirectErrorStream(true).start();
                String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                // Windows ships a python3.exe stub that opens the Store and exits
                // non-zero; only a real interpreter prints its version.
                if (p.waitFor(30, TimeUnit.SECONDS) && p.exitValue() == 0 && out.startsWith("Python 3")) {
                    python = candidate;
                    return;
                }
            } catch (IOException | InterruptedException ignored) {
                // try the next name
            }
        }
    }

    @BeforeEach
    void fixture() throws IOException {
        if (System.getenv("CI") != null) {
            assertNotNull(python, "no Python 3 interpreter on PATH. post-release.yml runs bump-version.py unattended"
                    + " after every release; on CI this test must run, not skip");
        }
        assumeTrue(python != null, "no Python 3 interpreter on PATH");

        write("pom.xml", """
                <project>
                    <artifactId>eddi</artifactId>
                    <version>6.4.0</version>
                    <dependencies><dependency><version>9.9.9</version></dependency></dependencies>
                </project>
                """);
        write("helm/eddi/Chart.yaml", """
                name: eddi
                version: 2.2.0
                appVersion: "6.4.0"
                """);
        write("k8s/base/kustomization.yaml", """
                images:
                  - name: labsai/eddi
                    newTag: "6.4.0"
                """);
        write("docs/quickstart.md", "EDDI_VERSION=6.4.0 docker compose up\ncrane digest labsai/eddi:6.4.0\n");
        // Every shape a pointer takes in prose and YAML: at the end of a sentence,
        // unquoted and single-quoted — beside things that only LOOK like one.
        write("docs/shapes.md", """
                Deploy labsai/eddi:6.4.0. Or `labsai/eddi:6.4.0`, then EDDI_VERSION=6.4.0.
                app.kubernetes.io/version: 6.4.0
                newTag: '6.4.0'
                Not pointers: labsai/eddi:6.4.0-RC1, labsai/eddi:6.4.0-b7, labsai/eddi:6.4.0.1
                """);
        // History: a release's own notes must keep naming that release.
        write("docs/release-notes-6.0.2.md", "docker pull labsai/eddi:6.0.2\n");
        write("docs/archive/old.md", "docker pull labsai/eddi:5.6.0\n");
        // A worked example of the tagging scheme — excluded, so it must survive.
        write("docs/release-versioning.md", "labsai/eddi:6.3.0-b42 and labsai/eddi:6.0.0\n");
        write("src/test/java/ai/labs/eddi/deploy/DeploymentManifestsTest.java",
                "private static final String EXPECTED_CHART_VERSION = \"2.2.0\";\n");
        write("scripts/release-pointers.json", Files.readString(ReleaseVersionSourceTest.RELEASE_POINTERS));
    }

    @Test
    @DisplayName("post-release of the current release only moves the pom, to the next minor")
    void postReleaseOfTheCurrentReleaseMovesOnlyThePom() throws Exception {
        Run run = run("post-release", "6.4.0");

        assertEquals(0, run.exit, run.output);
        assertTrue(read("pom.xml").contains("<version>6.5.0</version>"), "pom.xml should move to 6.5.0:\n" + run.output);
        assertTrue(read("pom.xml").contains("<version>9.9.9</version>"), "only the project's own <version> may move");
        assertTrue(read("helm/eddi/Chart.yaml").contains("version: 2.2.0"), "the chart did not change, so its version must not");
        assertTrue(read("docs/quickstart.md").contains("labsai/eddi:6.4.0"));
    }

    @Test
    @DisplayName("post-release of a new release moves the pointers, the chart, its test constant and the pom")
    void postReleaseOfANewReleaseMovesEverything() throws Exception {
        write("pom.xml", read("pom.xml").replace("<version>6.4.0</version>", "<version>6.5.0</version>"));

        Run run = run("post-release", "6.5.0");

        assertEquals(0, run.exit, run.output);
        assertTrue(read("pom.xml").contains("<version>6.6.0</version>"), run.output);
        assertTrue(read("helm/eddi/Chart.yaml").contains("appVersion: \"6.5.0\""));
        assertTrue(read("helm/eddi/Chart.yaml").contains("version: 2.3.0"), "a minor release bumps the chart's minor");
        assertTrue(read("src/test/java/ai/labs/eddi/deploy/DeploymentManifestsTest.java").contains("\"2.3.0\""),
                "EXPECTED_CHART_VERSION must move with the chart, or the next unit run fails");
        assertEquals("images:\n  - name: labsai/eddi\n    newTag: \"6.5.0\"\n", read("k8s/base/kustomization.yaml"));
        assertEquals("EDDI_VERSION=6.5.0 docker compose up\ncrane digest labsai/eddi:6.5.0\n", read("docs/quickstart.md"));
        assertEquals("labsai/eddi:6.3.0-b42 and labsai/eddi:6.0.0\n", read("docs/release-versioning.md"),
                "an excluded worked example must not be rewritten");
        assertEquals("""
                Deploy labsai/eddi:6.5.0. Or `labsai/eddi:6.5.0`, then EDDI_VERSION=6.5.0.
                app.kubernetes.io/version: 6.5.0
                newTag: '6.5.0'
                Not pointers: labsai/eddi:6.4.0-RC1, labsai/eddi:6.4.0-b7, labsai/eddi:6.4.0.1
                """, read("docs/shapes.md"));
        assertEquals("docker pull labsai/eddi:6.0.2\n", read("docs/release-notes-6.0.2.md"), "release notes are history");
        assertEquals("docker pull labsai/eddi:5.6.0\n", read("docs/archive/old.md"), "the archive is history");
        assertTrue(ReleaseVersionSourceTest.releasePointers(repo).stream().allMatch(p -> p.version().equals("6.5.0")));
    }

    @Test
    @DisplayName("a patch release off an older line moves the pointers but never pulls the pom back")
    void patchReleaseKeepsThePomAhead() throws Exception {
        write("pom.xml", read("pom.xml").replace("<version>6.4.0</version>", "<version>6.5.0</version>"));

        Run run = run("post-release", "6.4.1");

        assertEquals(0, run.exit, run.output);
        assertTrue(read("pom.xml").contains("<version>6.5.0</version>"), "pom.xml is already past 6.4.1:\n" + run.output);
        assertTrue(read("helm/eddi/Chart.yaml").contains("appVersion: \"6.4.1\""));
        assertTrue(read("helm/eddi/Chart.yaml").contains("version: 2.2.1"), "a patch release bumps the chart's patch");
    }

    /**
     * CI checks out main, not the tag. When the tag was cut somewhere main has not
     * caught up with — a release branch — main's pom is BEHIND it, and the job must
     * still move both, not fail red after a successful publish.
     */
    @Test
    @DisplayName("post-release of a tag main's pom has not reached moves the pom past it, then the pointers")
    void postReleaseWithThePomBehindTheTag() throws Exception {
        Run run = run("post-release", "6.5.0");

        assertEquals(0, run.exit, run.output);
        assertTrue(read("pom.xml").contains("<version>6.6.0</version>"), run.output);
        assertTrue(read("helm/eddi/Chart.yaml").contains("appVersion: \"6.5.0\""), run.output);
    }

    @Test
    @DisplayName("post-release compares a suffixed pom by its numbers, and never moves it backwards")
    void postReleaseLeavesASnapshotPomThatIsAhead() throws Exception {
        write("pom.xml", read("pom.xml").replace("<version>6.4.0</version>", "<version>7.0.0-SNAPSHOT</version>"));

        Run run = run("post-release", "6.4.0");

        assertEquals(0, run.exit, run.output);
        assertTrue(read("pom.xml").contains("<version>7.0.0-SNAPSHOT</version>"), "7.0.0-SNAPSHOT is past 6.4.0:\n"
                + run.output);
    }

    @Test
    @DisplayName("release --force of the current release repairs strays without bumping the chart")
    void forcedReleaseOfTheCurrentVersionIsARepair() throws Exception {
        write("docs/stale.md", "docker pull labsai/eddi:6.3.0\n");

        Run first = run("release", "6.4.0", "--force");
        Run second = run("release", "6.4.0", "--force");

        assertEquals(0, first.exit, first.output);
        assertEquals(0, second.exit, second.output);
        assertEquals("docker pull labsai/eddi:6.4.0\n", read("docs/stale.md"));
        assertTrue(read("helm/eddi/Chart.yaml").contains("version: 2.2.0"),
                "nothing about the chart changed, so a repair must not bump its version:\n" + second.output);
    }

    /**
     * The walk and the matching exist twice — here in Python, and in
     * ReleaseVersionSourceTest in Java — over one shared JSON. Run both over the
     * real repository and require the same answer, so the two cannot drift into
     * covering different files or lines.
     */
    @Test
    @DisplayName("the script and the Java sweep find exactly the same pointers in this repository")
    void scriptAndJavaSweepAgree() throws Exception {
        Run run = runIn(Path.of("").toAbsolutePath(), "show");
        assertEquals(0, run.exit, run.output);

        List<String> fromScript = run.output.lines()
                .filter(l -> l.startsWith("  "))
                .map(String::strip)
                .map(l -> l.replaceAll("\\s+", " "))
                .sorted()
                .toList();
        List<String> fromJava = ReleaseVersionSourceTest.releasePointers(Path.of("")).stream()
                .map(p -> p.version() + " " + p.file() + ":" + p.line())
                .sorted()
                .toList();
        assertFalse(fromJava.isEmpty(), "the Java sweep found no pointers at all");
        assertEquals(fromJava, fromScript);
    }

    @Test
    @DisplayName("post-release with --changelog writes a fragment ChangelogFragmentTest accepts")
    void postReleaseWritesAChangelogFragment() throws Exception {
        Run run = run("post-release", "6.4.0", "--changelog");

        assertEquals(0, run.exit, run.output);
        List<Path> fragments;
        try (Stream<Path> list = Files.list(repo.resolve("docs/changelog.d"))) {
            fragments = list.toList();
        }
        assertEquals(1, fragments.size(), "expected exactly one fragment, got " + fragments);
        String name = fragments.getFirst().getFileName().toString();
        assertTrue(name.matches("\\d{4}-\\d{2}-\\d{2}-post-release-6-4-0\\.md"), name);
        String fragment = Files.readString(fragments.getFirst());
        String firstLine = fragment.lines().findFirst().orElse("");
        assertTrue(firstLine.startsWith("## ") && firstLine.endsWith("(" + name.substring(0, 10) + ")"),
                "a fragment's heading must end with its date, matching the filename: " + firstLine);
        assertTrue(fragment.contains("`chore/post-release-6.4.0`"), "the Repo line must name the branch (AGENTS.md rule 8)");
        assertEquals(fragment.lines().filter(l -> l.startsWith("- `")).count(),
                fragment.lines().filter(l -> l.startsWith("- `")).distinct().count(), "a file is listed twice:\n" + fragment);

        // The collator is the real judge: it is what folds the fragment into the
        // changelog every night, and it exits non-zero on anything it would drop.
        String collate = Path.of("scripts", "collate-changelog.py").toAbsolutePath().toString().replace("\\", "/");
        Run parsed = runPython(repo, "-c", "import importlib.util, sys\n"
                + "sys.path.insert(0, '" + collate.substring(0, collate.lastIndexOf('/')) + "')\n"
                + "spec = importlib.util.spec_from_file_location('collate', '" + collate + "')\n"
                + "m = importlib.util.module_from_spec(spec); spec.loader.exec_module(m)\n"
                + "print(sum(len(m.entries_of(p)[0]) for p in m.fragments()), 'entries')\n");
        assertEquals(0, parsed.exit, parsed.output);
        assertTrue(parsed.output.contains("1 entries"), parsed.output);
    }

    @Test
    @DisplayName("release refuses to point readers at a version the pom has not reached")
    void releaseRefusesAVersionAheadOfThePom() throws Exception {
        Run run = run("release", "6.5.0");

        assertEquals(2, run.exit, run.output);
        assertTrue(read("helm/eddi/Chart.yaml").contains("appVersion: \"6.4.0\""), "a refused release must change nothing");
    }

    /**
     * post-release writes the pom before the pointers, so anything that would make
     * the pointer step refuse has to be caught before that first write — otherwise
     * a manual run exits with pom.xml moved and the chart not.
     */
    @Test
    @DisplayName("post-release refuses before its first write, leaving no half-applied bump")
    void postReleaseValidatesBeforeWriting() throws Exception {
        write("helm/eddi/Chart.yaml", read("helm/eddi/Chart.yaml").replace("version: 2.2.0", "version: 2.2"));

        Run run = run("post-release", "6.5.0");

        assertEquals(2, run.exit, run.output);
        assertTrue(read("pom.xml").contains("<version>6.4.0</version>"),
                "a refused post-release must not have moved pom.xml:\n" + run.output);
    }

    @Test
    @DisplayName("next refuses to move the build version backwards")
    void nextRefusesToGoBackwards() throws Exception {
        Run run = run("next", "6.3.0");

        assertEquals(2, run.exit, run.output);
        assertTrue(read("pom.xml").contains("<version>6.4.0</version>"));
    }

    @Test
    @DisplayName("check fails on a pointer that disagrees with the chart")
    void checkFailsOnAStrayPointer() throws Exception {
        write("docs/stale.md", "docker pull labsai/eddi:6.3.0\n");

        Run run = run("check");

        assertEquals(1, run.exit, run.output);
        assertTrue(run.output.contains("docs/stale.md:1"), run.output);
    }

    // ── plumbing ─────────────────────────────────────────────────────────────

    private record Run(int exit, String output) {
    }

    private Run run(String... args) throws IOException, InterruptedException {
        return runIn(repo, args);
    }

    private static Run runIn(Path root, String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of(SCRIPT.toString(), "--root", root.toString()));
        command.addAll(List.of(args));
        return runPython(root, command.toArray(String[]::new));
    }

    private static Run runPython(Path workingDirectory, String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of(python));
        command.addAll(List.of(args));
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true).directory(workingDirectory.toFile());
        builder.environment().put("PYTHONIOENCODING", "utf-8");
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!process.waitFor(1, TimeUnit.MINUTES)) {
            process.destroyForcibly();
            throw new AssertionError("bump-version.py did not finish:\n" + output);
        }
        return new Run(process.exitValue(), output);
    }

    private void write(String relative, String content) throws IOException {
        Path path = repo.resolve(relative);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }

    private String read(String relative) throws IOException {
        return Files.readString(repo.resolve(relative), StandardCharsets.UTF_8);
    }
}
