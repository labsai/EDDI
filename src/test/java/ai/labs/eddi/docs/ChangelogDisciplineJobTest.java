/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.docs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the shell of ci.yml's {@code Changelog Discipline} job against synthetic
 * diffs, so the guard is graded by what it does rather than by what its
 * patterns look like.
 * <p>
 * The script is taken from the workflow itself and executed by bash with a stub
 * {@code git} first on {@code PATH} that prints a canned diff — nothing is
 * copied into this test, so it cannot drift from the job it grades. It needs
 * bash and a POSIX awk (the job runs on ubuntu-latest, whose awk is mawk), and
 * skips where there is none.
 * <p>
 * The guard used to count only {@code ## … (YYYY-MM-DD} headings, so an entry
 * added in place with no date, or with a setext {@code ---} underline, went
 * straight through; and the whole job was skipped for any head branch
 * <em>named</em> {@code chore/collate-changelog}. Widening the count must not
 * catch what the collator ignores, so lines inside a fenced code block — a
 * quoted {@code ## Example}, a YAML {@code ---} — are not counted either.
 */
@DisplayName("Changelog Discipline CI job")
class ChangelogDisciplineJobTest {

    private static final Path CI = Path.of(".github", "workflows", "ci.yml");
    private static final String JOB = "changelog-discipline";
    private static final String GUARD_STEP = "Check for entries added in place";
    private static final String COLLATION_STEP = "Collation PR touches only the changelog";
    private static final String HEADER = "diff --git a/docs/changelog.md b/docs/changelog.md\n"
            + "--- a/docs/changelog.md\n+++ b/docs/changelog.md\n@@ -10,6 +10,12 @@\n";

    @TempDir
    Path tmp;

    @Test
    @DisplayName("an entry added in place fails, dated or not, ATX or setext")
    void guardRejectsEveryShapeOfEntry() throws Exception {
        assertEquals(1, runGuard("+## New thing (2026-09-26)\n+\n+Body.\n+\n+---\n+\n"), "a dated entry");
        assertEquals(1, runGuard("+## New thing\n+\n+Body.\n"), "an undated entry");
        assertEquals(1, runGuard("+##\tTabbed title\n"), "a tab after the hashes is still a heading");
        assertEquals(1, runGuard(" \n+New thing\n+---------\n+\n+Body.\n"), "a setext h2");
        assertEquals(1, runGuard("+**New thing (2026-09-26)**\n+===\n"), "a setext h1 under emphasis");
        assertEquals(1, runGuard("+| 2026-09-26 | decided | why | rejected |\n"), "a register row");
        assertEquals(1, runGuard("+```text\n+example\n+```\n+## Real entry (2026-09-26)\n"),
                "a heading after a closed fence is structure again");
    }

    @Test
    @DisplayName("edits, rotations and ordinary markdown pass")
    void guardAllowsWhatDoesNotConflict() throws Exception {
        assertEquals(0, runGuard("-## Old title (2026-01-01)\n+## New title (2026-01-01)\n"), "a heading typo fix");
        assertEquals(0, runGuard("-## Rotated (2026-01-01)\n-\n-Body.\n-\n----\n"), "a rotation");
        assertEquals(0, runGuard(" Existing text.\n+\n+---\n"), "an entry separator under a blank line");
        assertEquals(0, runGuard("+- a list item\n+---\n"), "a rule under a list item");
        assertEquals(0, runGuard("+| a | b |\n+|---|---|\n"), "a table");
        assertEquals(0, runGuard("+### A sub-heading inside an existing entry\n"), "an h3");
        assertEquals(0, runGuard("-| 2026-01-01 | old |\n+| 2026-01-01 | fixed |\n"), "a register row correction");
        assertEquals(0, runGuard(""), "no change to the file");
        assertEquals(0, runGuard(" Existing text.\n+\n+```markdown\n+## Example (2026-01-01)\n+```\n"),
                "a heading quoted inside a fenced example");
        assertEquals(0, runGuard("+~~~yaml\n+a: 1\n+---\n+b: 2\n+~~~\n"), "a YAML document separator in a fence");
        assertEquals(0, runGuard("+````\n+```decision-log\n+| 2026-09-26 | a |\n+```\n+````\n"),
                "a register row inside a nested example fence");
        assertEquals(0, runGuard(" ```text\n+## not a heading\n ```\n"),
                "a line added inside a fence the context already opened");
    }

    /**
     * Against real git rather than a canned hunk: with git's default three lines of
     * context a hunk can begin inside a fenced block, and the guard, reading only
     * the hunk, lost the fence state — so a heading quoted in an example was
     * rejected, and a closing fence read as an opening one hid a real entry added
     * just below it. The job diffs with the whole file as context.
     */
    @Test
    @DisplayName("fence state is taken from the whole file, not from a hunk that starts inside a fence")
    void guardSeesFencesOpenedAboveTheHunk() throws Exception {
        String longFence = "# Changelog\n\n## Entry (2026-01-01)\n\nExample:\n\n```markdown\n"
                + "l1\nl2\nl3\nl4\nl5\nl6\n%sl7\nl8\nl9\nl10\n```\n\nTail.\n";
        assertEquals(0, runGuardOnRepo(longFence.formatted(""), longFence.formatted("## Example (2026-01-02)\n")),
                "a heading added deep inside an existing fenced example is not an entry");

        String closedFence = "# Changelog\n\n```text\na1\na2\na3\na4\na5\n```\n%s"
                + "t1\nt2\nt3\nt4\nt5\n";
        assertEquals(1, runGuardOnRepo(closedFence.formatted(""),
                closedFence.formatted("\n## Real entry (2026-09-26)\n\nBody.\n\n")),
                "an entry added right after an existing fence closes is still an entry");
    }

    @Test
    @DisplayName("the collation branch may carry only the collator's output")
    void collationBranchIsLimitedToChangelogFiles() throws Exception {
        assertEquals(0, runStep(COLLATION_STEP, "docs/changelog.md\ndocs/changelog.d/2026-09-26-x.md\n"
                + "docs/changelog/2026-08.md\ndocs/SUMMARY.md\n"));
        assertEquals(1, runStep(COLLATION_STEP, "docs/changelog.md\nsrc/main/java/Foo.java\n"));
        assertEquals(1, runStep(COLLATION_STEP, "docs/changelog.d/nested/x.md\n"),
                "the collator writes no subdirectories");
    }

    @Test
    @DisplayName("the job is not skipped by branch name and validates fragments with the collator")
    void jobRunsForEveryPullRequestAndChecksFragments() throws Exception {
        JsonNode job = job();
        String condition = job.path("if").asText();
        assertFalse(condition.contains("head_ref"),
                "the job's own `if` must not exempt a branch by name — any branch, including a fork's, can be"
                        + " called chore/collate-changelog. Condition was: " + condition);

        String exemption = job.path("env").path("IS_COLLATION").asText();
        assertTrue(exemption.contains("head.repo.full_name == github.repository"),
                "the collation exemption must require the head branch to live in this repository. Was: " + exemption);
        assertTrue(step(GUARD_STEP).path("if").asText().contains("IS_COLLATION"),
                "the in-place guard must be skipped only through IS_COLLATION");
        assertTrue(step(COLLATION_STEP).path("if").asText().contains("IS_COLLATION"),
                "the collation PR must get its own check when the guard is skipped");

        boolean checks = false;
        for (JsonNode s : job.path("steps")) {
            checks |= s.path("run").asText().contains("scripts/collate-changelog.py --check");
        }
        assertTrue(checks, JOB + " must run `scripts/collate-changelog.py --check`, so a fragment the nightly job"
                + " would refuse fails the PR that adds it rather than main at 02:00");
    }

    private int runGuard(String hunk) throws Exception {
        return runStep(GUARD_STEP, hunk.isEmpty() ? "" : HEADER + hunk);
    }

    /**
     * Runs the guard step in a real repository: {@code origin} holds {@code base}
     * as docs/changelog.md on {@code main}, and the checked-out branch changes it
     * to {@code head}. Returns the script's exit code.
     */
    private int runGuardOnRepo(String base, String head) throws Exception {
        Assumptions.assumeFalse(File.separatorChar == '\\', "the job's shell is bash on Linux");
        Path dir = Files.createTempDirectory(tmp, "repo");
        Path origin = dir.resolve("origin");
        Path work = dir.resolve("work");
        Files.createDirectories(origin.resolve("docs"));
        git(origin, "init", "-q", "-b", "main");
        Files.writeString(origin.resolve("docs/changelog.md"), base, StandardCharsets.UTF_8);
        git(origin, "add", "docs/changelog.md");
        git(origin, "commit", "-q", "-m", "base");
        git(dir, "clone", "-q", origin.toString(), work.toString());
        git(work, "checkout", "-q", "-b", "feature");
        Files.writeString(work.resolve("docs/changelog.md"), head, StandardCharsets.UTF_8);
        git(work, "commit", "-q", "-am", "head");

        Path script = dir.resolve("step.sh");
        Files.writeString(script, step(GUARD_STEP).path("run").asText(), StandardCharsets.UTF_8);
        ProcessBuilder builder = new ProcessBuilder("bash", script.toString());
        builder.directory(work.toFile());
        builder.redirectErrorStream(true);
        builder.redirectOutput(dir.resolve("out.txt").toFile());
        isolateGit(builder.environment());
        builder.environment().put("BASE", "main");
        builder.environment().put("GITHUB_STEP_SUMMARY", dir.resolve("summary.md").toString());
        Process process = builder.start();
        assertTrue(process.waitFor(60, TimeUnit.SECONDS), "the step did not finish");
        return process.exitValue();
    }

    private static void git(Path dir, String... args) throws Exception {
        String[] command = new String[args.length + 5];
        command[0] = "git";
        command[1] = "-c";
        command[2] = "commit.gpgsign=false";
        command[3] = "-c";
        command[4] = "core.hooksPath=/dev/null";
        System.arraycopy(args, 0, command, 5, args.length);
        ProcessBuilder builder = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true);
        isolateGit(builder.environment());
        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            Assumptions.abort("git is not available: " + e.getMessage());
            return;
        }
        String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(60, TimeUnit.SECONDS), "git " + String.join(" ", args) + " did not finish");
        assertEquals(0, process.exitValue(), "git " + String.join(" ", args) + " failed: " + out);
    }

    /**
     * Keeps the developer's own git configuration (signing, hooks, identity) out of
     * the test repos.
     */
    private static void isolateGit(Map<String, String> env) {
        env.put("GIT_CONFIG_NOSYSTEM", "1");
        env.put("GIT_CONFIG_GLOBAL", "/dev/null");
        env.put("GIT_AUTHOR_NAME", "test");
        env.put("GIT_AUTHOR_EMAIL", "test@example.invalid");
        env.put("GIT_COMMITTER_NAME", "test");
        env.put("GIT_COMMITTER_EMAIL", "test@example.invalid");
    }

    /**
     * Runs one step's {@code run:} script with {@code git} stubbed: every
     * {@code git diff} prints {@code gitDiffOutput}, every other git call succeeds
     * silently. Returns the script's exit code.
     */
    private int runStep(String stepName, String gitDiffOutput) throws Exception {
        Assumptions.assumeFalse(File.separatorChar == '\\', "the job's shell is bash on Linux");

        Path bin = Files.createDirectories(tmp.resolve("bin"));
        Path git = bin.resolve("git");
        Files.writeString(git, "#!/bin/sh\nif [ \"$1\" = diff ]; then cat \"$FAKE_DIFF\"; fi\nexit 0\n");
        assertTrue(git.toFile().setExecutable(true), "could not mark the git stub executable");

        Path diff = tmp.resolve("diff.txt");
        Files.writeString(diff, gitDiffOutput, StandardCharsets.UTF_8);
        Path script = tmp.resolve("step.sh");
        Files.writeString(script, step(stepName).path("run").asText(), StandardCharsets.UTF_8);

        ProcessBuilder builder = new ProcessBuilder("bash", script.toString());
        builder.directory(Path.of("").toAbsolutePath().toFile());
        builder.redirectErrorStream(true);
        builder.redirectOutput(tmp.resolve("out.txt").toFile());
        Map<String, String> env = builder.environment();
        env.put("PATH", bin + File.pathSeparator + env.getOrDefault("PATH", ""));
        env.put("FAKE_DIFF", diff.toString());
        env.put("BASE", "main");
        env.put("GITHUB_STEP_SUMMARY", tmp.resolve("summary.md").toString());

        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            Assumptions.abort("bash is not available: " + e.getMessage());
            return -1;
        }
        assertTrue(process.waitFor(60, TimeUnit.SECONDS), "the step did not finish");
        return process.exitValue();
    }

    private static JsonNode job() throws IOException {
        JsonNode job = new YAMLMapper().readTree(CI.toFile()).path("jobs").path(JOB);
        assertFalse(job.isMissingNode(), CI + " no longer has a " + JOB + " job");
        return job;
    }

    private static JsonNode step(String name) throws IOException {
        for (JsonNode s : job().path("steps")) {
            if (name.equals(s.path("name").asText())) {
                return s;
            }
        }
        throw new AssertionError(CI + "'s " + JOB + " job has no step named '" + name + "'");
    }
}
