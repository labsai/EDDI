/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.docs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Grades the pending changelog fragments in {@code docs/changelog.d/}.
 * <p>
 * AGENTS.md §2 rule 8 requires every branch to add a changelog entry. While
 * that entry went at the top of {@code docs/changelog.md}, every open PR
 * inserted at the same point in the same file — which git cannot merge — so
 * with several PRs in flight each one conflicted with every other over a
 * document unrelated to the code under review, and rebasing past it merely
 * re-ran the collision at the next merge. The two running registers at the
 * bottom of that file collided the same way. A fragment is a whole new file
 * under a name no other branch picks, so git takes both sides silently;
 * {@code scripts/collate-changelog.py} does the single merge that is actually
 * needed, afterwards, on main.
 * <p>
 * That moves the failure mode rather than removing it: a malformed fragment now
 * breaks the nightly collation job, days later, attributed to the bot rather
 * than to the branch that wrote it. This test is where the author finds out
 * instead — it enforces exactly what the collator enforces, at {@code mvnw
 * test} time.
 *
 * @see ai.labs.eddi.docs.ChangelogRotationTest
 */
@DisplayName("changelog fragments")
class ChangelogFragmentTest {

    private static final Path FRAGMENT_DIR = Path.of("docs", "changelog.d");
    private static final Path CI = Path.of(".github", "workflows", "ci.yml");
    private static final Path COLLATE_WORKFLOW = Path.of(".github", "workflows", "changelog-collate.yml");
    private static final Path AGENTS = Path.of("AGENTS.md");

    /** Mirrors {@code changelog_common.FRAGMENT_NAME}. */
    private static final Pattern FRAGMENT_NAME = Pattern.compile("^(\\d{4}-\\d{2}-\\d{2})-([a-z0-9][a-z0-9._-]*)\\.md$");

    /** The date an entry carries in its own heading. */
    private static final Pattern HEADING_DATE = Pattern.compile("\\((\\d{4}-\\d{2}-\\d{2})\\)");

    /**
     * A relative link, matching {@code changelog_common.LINK} — absolute URLs,
     * anchors and mailto: are not depth-sensitive and are skipped there too.
     */
    private static final Pattern LINK = Pattern.compile("\\]\\((?!https?://|#|mailto:|<http)([^)]+)\\)");

    /**
     * A code span, honouring the backtick-run rule — mirrors
     * {@code changelog_common.SPAN}.
     */
    private static final Pattern SPAN = Pattern.compile("(`+)(?:(?!\\1).)*?\\1");

    /** A fence marker, mirroring {@code changelog_common.FENCE_MARK}. */
    private static final Pattern FENCE_MARK = Pattern.compile("^(`{3,})(.*)$");

    /**
     * A reference-style link definition. {@link #LINK} cannot see the path in one,
     * so collation would move it up a directory without re-depthing it — and
     * {@code DocumentationLinksTest} only follows inline links, so the resulting
     * dead link has nothing pointed at it either.
     */
    private static final Pattern REF_DEF = Pattern.compile("^\\[[^\\]]+\\]:\\s*\\S");

    /**
     * An entry titled after one of the running registers, dated or not — the exact
     * heading is what {@code changelog_common.REGISTER} keys on.
     */
    private static final Pattern REGISTER_TITLE = Pattern.compile("^## (Decision Log|Regression Notes)\\b");

    @Test
    @DisplayName("every file in changelog.d is a README or a dated fragment")
    void fragmentDirectoryHoldsOnlyFragments() {
        Path dir = repoRoot().resolve(FRAGMENT_DIR);
        assertTrue(Files.isDirectory(dir), FRAGMENT_DIR + " is missing — it is where changelog entries "
                + "are written, and AGENTS.md §2 rule 8 sends every session to it");

        var problems = new TreeSet<String>();
        try (Stream<Path> children = Files.list(dir)) {
            for (Path child : children.toList()) {
                String name = child.getFileName().toString();
                if (Files.isDirectory(child)) {
                    // DocumentationLinksTest exempts this directory from the
                    // "every page is reachable from SUMMARY.md" rule, because
                    // fragments are pending entries rather than pages. That
                    // carve-out is only safe while nothing else can live here —
                    // a subdirectory would be an unreachable page with a reason
                    // already written for it.
                    problems.add(name + "/ — changelog.d holds fragment files only, no subdirectories");
                } else if (!name.equals("README.md") && !FRAGMENT_NAME.matcher(name).matches()) {
                    problems.add(name + " — name a fragment YYYY-MM-DD-<slug>.md, lower-case, with a slug "
                            + "unique to your branch (the branch name usually works)");
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        assertTrue(problems.isEmpty(), "scripts/collate-changelog.py refuses to run on these:\n  "
                + String.join("\n  ", problems));
    }

    @Test
    @DisplayName("every fragment is a dated entry the collator can place")
    void fragmentsCarryDatedHeadings() {
        var problems = new TreeSet<String>();

        for (Path fragment : fragments()) {
            String name = fragment.getFileName().toString();
            Matcher fileDate = FRAGMENT_NAME.matcher(name);
            fileDate.matches(); // always true: fragments() filters on this pattern

            List<String> headings = headingsOf(read(fragment));
            if (headings.isEmpty()) {
                problems.add(name + " — no '## ' heading. A fragment is one or more changelog entries, "
                        + "each headed '## <title> (YYYY-MM-DD)'");
                continue;
            }

            for (String heading : headings) {
                if (!HEADING_DATE.matcher(heading).find()) {
                    problems.add(name + " — undated heading: " + heading
                            + ". The date in brackets is what orders the collated entries");
                }
            }

            // The filename date is a sort hint for humans scanning the
            // directory; the heading is what the collator reads. Letting them
            // disagree means the directory listing tells you one thing and the
            // collated file another, and neither is wrong enough to notice.
            Matcher first = HEADING_DATE.matcher(headings.get(0));
            if (first.find() && !first.group(1).equals(fileDate.group(1))) {
                problems.add(name + " — the filename says " + fileDate.group(1)
                        + " but its first entry is dated " + first.group(1));
            }
        }

        assertTrue(problems.isEmpty(), "malformed changelog fragment(s):\n  "
                + String.join("\n  ", problems));
    }

    @Test
    @DisplayName("every relative link in a fragment survives collation")
    void fragmentLinksCarryTheExtraDepth() {
        var problems = new TreeSet<String>();

        for (Path fragment : fragments()) {
            String name = fragment.getFileName().toString();
            // Fenced blocks and code spans are skipped for the same reason the
            // collator skips them: some of this text documents link syntax, and
            // re-depthing an example corrupts it.
            for (String line : proseLines(read(fragment))) {
                if (REF_DEF.matcher(line.strip()).find()) {
                    problems.add(name + " — defines a link by reference: " + line.strip());
                }
                Matcher link = LINK.matcher(SPAN.matcher(line).replaceAll(""));
                while (link.find()) {
                    String target = link.group(1);
                    if (target.equals("../")) {
                        problems.add(name + " — links to a bare '../', which collates to an empty target");
                    } else if (!target.startsWith("/") && !target.startsWith("../")) {
                        problems.add(name + " — links to " + target);
                    }
                }
            }
        }

        // A fragment is one directory below docs/changelog.md, so collation
        // removes one '../' from each link. DocumentationLinksTest checks the
        // link where the fragment lives, so a sibling link passes there and
        // breaks only once the entry moves up — in the nightly job's commit,
        // blamed on the bot.
        assertTrue(problems.isEmpty(),
                "these links resolve where the fragment sits but not once it is collated into "
                        + "docs/changelog.md. Write '../../src/...' for a source file, "
                        + "'../architecture.md' for a neighbouring doc, and spell links inline rather "
                        + "than by reference:\n  "
                        + String.join("\n  ", problems));
    }

    @Test
    @DisplayName("register rows ride in fenced blocks, not in copies of the register headings")
    void fragmentsDoNotRedeclareTheRunningRegisters() {
        var problems = new TreeSet<String>();

        for (Path fragment : fragments()) {
            for (String heading : headingsOf(read(fragment))) {
                if (REGISTER_TITLE.matcher(heading).find()) {
                    problems.add(fragment.getFileName() + " — heading: " + heading);
                }
            }
        }

        // Both spellings are trouble, in different ways. A bare "## Decision
        // Log" is classified by split_sections() as the register itself, so the
        // section is filed below the bottom rule instead of being collated as
        // an entry. A dated "## Decision Log (2026-09-22)" collates normally —
        // and is then reclassified on the NEXT run, moving an entry that has
        // already been reviewed and merged, with no commit to explain it.
        assertTrue(problems.isEmpty(),
                "an entry may not be titled after one of the running registers. Put register rows in a "
                        + "fenced ```decision-log or ```regression-note block, which is collated into the "
                        + "table at the bottom of docs/changelog.md:\n  "
                        + String.join("\n  ", problems));
    }

    @Test
    @DisplayName("a changelog-only PR still runs the tests that guard the changelog")
    void ciRunsTheseTestsOnChangelogChanges() throws IOException {
        String filters = "";
        for (JsonNode step : new YAMLMapper().readTree(repoRoot().resolve(CI).toFile())
                .path("jobs").path("detect-changes").path("steps")) {
            if (step.path("with").has("filters")) {
                filters = step.path("with").path("filters").asText();
            }
        }

        // `filters` is itself a YAML document, embedded as a string. Parsing it
        // rather than searching the raw text is what ties the paths to the
        // operator_docs key: a plain contains() would be satisfied by the same
        // path listed under `code`, which gates the image build and publish and
        // is the one filter these must NOT be in.
        JsonNode operatorDocs = new YAMLMapper().readTree(filters).path("operator_docs");
        assertFalse(operatorDocs.isMissingNode(), CI + " has no operator_docs path filter; the `code` "
                + "and `backend` filters both exclude docs/**, so nothing would trigger the suite that "
                + "guards these files");

        var listed = new TreeSet<String>();
        operatorDocs.forEach(path -> listed.add(path.asText()));

        // The nightly collation PR touches nothing but these, so without them
        // the one PR that rewrites the entire changelog is the one PR that runs
        // no tests — and a skipped required check still satisfies branch
        // protection.
        for (String path : List.of("docs/changelog.md", "docs/changelog.d/**", "docs/changelog/**")) {
            assertTrue(listed.contains(path),
                    CI + "'s operator_docs filter must list " + path + " — this test and "
                            + "ChangelogRotationTest grade it, and an assertion that cannot be triggered "
                            + "by the file it reads is not a guard. It lists: " + listed);
        }
    }

    @Test
    @DisplayName("the nightly collation job exists and is scheduled")
    void collationIsAutomated() throws IOException {
        Path workflow = repoRoot().resolve(COLLATE_WORKFLOW);
        assertTrue(Files.isRegularFile(workflow), COLLATE_WORKFLOW + " is missing. Without it nothing "
                + "ever empties docs/changelog.d/, and the entries every branch is required to write are "
                + "never folded into the changelog anyone reads");

        JsonNode collate = new YAMLMapper().readTree(workflow.toFile());
        // `on:` is YAML 1.1's boolean true, which Jackson resolves as the field
        // name "true" rather than "on" — hence both spellings.
        JsonNode triggers = collate.has("on") ? collate.path("on") : collate.path("true");
        assertFalse(triggers.path("schedule").isMissingNode(),
                COLLATE_WORKFLOW + " must stay on a schedule. Run by hand only, it is a chore someone "
                        + "has to remember, which is what the fragments were introduced to stop being");

        String body = read(workflow);
        for (String script : List.of("scripts/collate-changelog.py", "scripts/rotate-changelog.py")) {
            assertTrue(body.contains(script), COLLATE_WORKFLOW + " must run " + script);
            assertTrue(Files.isRegularFile(repoRoot().resolve(script)),
                    script + " is referenced by " + COLLATE_WORKFLOW + " but does not exist");
        }
    }

    @Test
    @DisplayName("AGENTS.md sends sessions to the fragment directory")
    void agentsMdDocumentsTheFragmentWorkflow() {
        String agents = read(repoRoot().resolve(AGENTS));

        // AGENTS.md is auto-loaded by every AI session and read by every new
        // contributor, so it is the only instruction that actually governs where
        // an entry gets written. While it still said "edit docs/changelog.md",
        // the fragments would be a directory nobody used and the conflicts would
        // continue exactly as before.
        //
        // Anchored to rule 8's own line rather than to the file: a bare
        // contains() passes on any mention anywhere, including one telling the
        // reader NOT to use the directory, and including this paragraph's own
        // wording were it ever quoted there.
        String rule8 = agents.lines()
                .filter(l -> l.startsWith("8. **Update the changelog"))
                .findFirst()
                .orElse("");
        assertFalse(rule8.isBlank(), AGENTS + " no longer has a rule 8 starting "
                + "'8. **Update the changelog' — this test locates the instruction by that line, so "
                + "renumbering or retitling it makes the guard silently vacuous");
        assertTrue(rule8.contains("docs/changelog.d"),
                AGENTS + " §2 rule 8 must send sessions to docs/changelog.d/, not to docs/changelog.md. "
                        + "Every session follows the instruction it is given, and an unused convention "
                        + "fixes nothing. Rule 8 currently reads: " + rule8);
    }

    /**
     * The lines of a fragment that are prose rather than a fenced example.
     * <p>
     * Mirrors {@code changelog_common.fence_mask}, including its handling of the
     * backtick-run rule: a ```` ```decision-log ```` nested inside a ` ````markdown
     * ` block that documents this very format is an example, and a closing fence
     * must be at least as long as the one that opened it.
     */
    private static List<String> proseLines(String body) {
        var prose = new ArrayList<String>();
        int openRun = 0;
        for (String line : body.split("\n", -1)) {
            Matcher mark = FENCE_MARK.matcher(line.strip());
            boolean isMark = mark.matches();
            if (openRun == 0) {
                if (isMark) {
                    openRun = mark.group(1).length();
                } else {
                    prose.add(line);
                }
            } else if (isMark && mark.group(1).length() >= openRun && mark.group(2).isBlank()) {
                openRun = 0;
            }
        }
        return prose;
    }

    /**
     * A fragment's entry headings — the {@code ## } lines that are structure.
     * <p>
     * Scanning every line that starts with {@code ## } counted the ones inside a
     * fenced example too, which is not a pedantic distinction: a changelog entry
     * routinely quotes the markdown it describes, and the collator splits the entry
     * at each heading it finds. A dated heading in an example silently cut an entry
     * in half; an undated one was rejected with a message naming a heading the
     * author never wrote.
     */
    private static List<String> headingsOf(String body) {
        return proseLines(body).stream().filter(l -> l.startsWith("## ")).toList();
    }

    private static List<Path> fragments() {
        Path dir = repoRoot().resolve(FRAGMENT_DIR);
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> children = Files.list(dir)) {
            return children.filter(Files::isRegularFile)
                    .filter(p -> FRAGMENT_NAME.matcher(p.getFileName().toString()).matches())
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Path repoRoot() {
        Path root = Path.of("").toAbsolutePath();
        assertTrue(Files.isRegularFile(root.resolve("pom.xml")),
                "expected the working directory to be the project root, was " + root);
        return root;
    }

    private static String read(Path file) {
        try {
            return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
