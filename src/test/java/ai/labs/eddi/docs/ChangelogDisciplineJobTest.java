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
 * <em>named</em> {@code chore/collate-changelog}.
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
