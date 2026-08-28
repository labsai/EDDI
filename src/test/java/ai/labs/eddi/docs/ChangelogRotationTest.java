/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.docs;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Keeps the working changelog small enough to actually be read.
 * <p>
 * AGENTS.md §2 rule 8 requires every session to append an entry here, and
 * nothing ever required one to be removed. Over six months that produced a
 * single 1.9 MB file — roughly half a million tokens, which is more than most
 * context windows hold and far more than the "skim the top 2–3 entries"
 * instruction implies. It was also linked from {@code SUMMARY.md} as a
 * browsable documentation page.
 * <p>
 * Growth of that kind cannot be fixed once and left alone: the same rule that
 * produced it is still in force, so the file refills. The cap therefore has to
 * be enforced by something that runs, which is this test. When it fails, the
 * fix is to rotate — move the oldest entries into
 * {@code docs/changelog/<YYYY-MM>.md} — not to raise {@link #LIVE_CAP_BYTES}.
 *
 * @see ai.labs.eddi.docs.DocumentationLinksTest
 */
@DisplayName("changelog rotation")
class ChangelogRotationTest {

    private static final Path LIVE = Path.of("docs", "changelog.md");
    private static final Path ARCHIVE_DIR = Path.of("docs", "changelog");

    /**
     * The cap named in the live file's own header. Roughly 60k tokens: large enough
     * for a few months of ordinary work, small enough that an agent can hold the
     * recent history and still have room to do the task.
     */
    private static final long LIVE_CAP_BYTES = 250 * 1024;

    /**
     * Headroom before the cap bites, so a session that appends a long entry is told
     * to rotate rather than being failed by the entry it just wrote.
     */
    private static final long WARN_AT_BYTES = (LIVE_CAP_BYTES * 9) / 10;

    /**
     * An archive filename: exactly {@code YYYY-MM.md}, with the month validated by
     * the pattern rather than by parsing.
     * <p>
     * {@code (0[1-9]|1[0-2])} says "a real month" declaratively. The earlier
     * {@code (\d{2})} plus {@code Integer.parseInt} said the same thing in two
     * steps, one of which static analysis reads — correctly, in general — as an
     * unguarded {@code NumberFormatException}. It could not actually throw here,
     * because the regex had already established two digits, but a validation that
     * has to be reasoned about to be dismissed is worse than one that cannot fail.
     */
    private static final Pattern ARCHIVE_NAME = Pattern.compile("^(\\d{4})-(0[1-9]|1[0-2])\\.md$");

    /**
     * A row of the {@code ## Archive} table linking one archive.
     * <p>
     * Matching the whole file for {@code changelog/<name>} was too loose: a
     * changelog *entry* that happens to mention an archive path would satisfy it
     * while the table itself omitted the file. That is precisely the drift this
     * test exists to catch, and this very changelog contains entries discussing
     * {@code docs/changelog/} paths — so the loose form was one edit away from
     * passing vacuously.
     */
    private static Pattern archiveTableRow(String fileName) {
        return Pattern.compile("^\\|.*\\]\\(changelog/" + Pattern.quote(fileName) + "\\).*\\|\\s*$",
                Pattern.MULTILINE);
    }

    /** Every archive a row of the table links to, whatever it is called. */
    private static final Pattern ARCHIVE_LINK = Pattern.compile("\\]\\(changelog/([^)]+)\\)");

    @Test
    @DisplayName("the live changelog stays under the rotation cap")
    void liveChangelogIsUnderCap() {
        Path root = repoRoot();
        long size = normalisedSize(root.resolve(LIVE));

        assertTrue(size <= LIVE_CAP_BYTES, String.format(
                "docs/changelog.md is %,d bytes, over the %,d-byte cap.%n"
                        + "Rotate it: move the oldest entries into docs/changelog/<YYYY-MM>.md for the month "
                        + "each entry is dated, add one '../' to the relative links you move, and add a row to "
                        + "the Archive table. Do NOT raise the cap — it exists because this file reached 1.9 MB "
                        + "once already, and every session is required to add to it.",
                size, LIVE_CAP_BYTES));

        if (size > WARN_AT_BYTES) {
            System.out.printf("docs/changelog.md is %,d bytes, within %,d of the cap — rotate soon.%n",
                    size, LIVE_CAP_BYTES - size);
        }
    }

    @Test
    @DisplayName("every archive is named YYYY-MM.md and listed in the live file's Archive table")
    void archivesAreNamedAndIndexed() {
        Path root = repoRoot();
        Path archives = root.resolve(ARCHIVE_DIR);
        if (!Files.isDirectory(archives)) {
            return; // nothing rotated yet
        }

        String archiveTable = archiveTableOf(read(root.resolve(LIVE)));
        var problems = new TreeSet<String>();

        // Disk → table. A file missing from the index is rotated-away-and-lost
        // rather than archived: the table is the only way a reader finds it.
        var onDisk = new TreeSet<String>();
        for (Path file : listMarkdown(archives)) {
            String name = file.getFileName().toString();
            onDisk.add(name);
            if (!ARCHIVE_NAME.matcher(name).matches()) {
                problems.add(name + " — archives must be named YYYY-MM.md, with a real month");
                continue;
            }
            if (!archiveTableRow(name).matcher(archiveTable).find()) {
                problems.add(name + " — no row for it in the ## Archive table of docs/changelog.md");
            }
        }

        // Table → disk. The other direction, which is not symmetric with the
        // first: a row naming a file that no longer exists is a dead link in the
        // live changelog. DocumentationLinksTest does fail on it, but reports it
        // as a generic unresolved link, which says nothing about the index being
        // stale — and it cannot catch a row naming a file that exists under a
        // name no rotation would ever produce.
        Matcher indexed = ARCHIVE_LINK.matcher(archiveTable);
        while (indexed.find()) {
            String name = indexed.group(1);
            if (!ARCHIVE_NAME.matcher(name).matches()) {
                problems.add(name + " — indexed in the ## Archive table, but not a YYYY-MM.md archive name");
            } else if (!onDisk.contains(name)) {
                problems.add(name + " — a row in the ## Archive table points at it, but the file does not exist");
            }
        }

        assertTrue(problems.isEmpty(),
                "changelog archives are inconsistent with the index that points at them:\n  "
                        + String.join("\n  ", problems));
    }

    @Test
    @DisplayName("the live changelog still carries the running registers")
    void runningRegistersSurviveRotation() {
        String live = read(repoRoot().resolve(LIVE));
        var missing = new TreeSet<String>();
        for (String register : List.of("## Decision Log", "## Regression Notes")) {
            if (!live.contains(register)) {
                missing.add(register);
            }
        }

        assertTrue(missing.isEmpty(),
                "These are running registers that sessions append to, not dated entries — rotating them "
                        + "into an archive silently retires them, because nobody appends to an archive:\n  "
                        + String.join("\n  ", missing));
    }

    /**
     * The {@code ## Archive} section of the live file, up to the next {@code ##}
     * heading. Restricting the search to it is what stops a changelog entry that
     * mentions an archive path from standing in for the table row.
     */
    private static String archiveTableOf(String live) {
        int start = live.indexOf("## Archive");
        assertTrue(start >= 0, "docs/changelog.md has no '## Archive' section — the archives it "
                + "points at are unreachable, and this test cannot check the index that does not exist.");
        int end = live.indexOf("\n## ", start + 1);
        return end < 0 ? live.substring(start) : live.substring(start, end);
    }

    private static List<Path> listMarkdown(Path dir) {
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".md"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * The file's size with {@code \r\n} normalised to {@code \n}, which is what git
     * stores.
     * <p>
     * {@code Files.size} would measure the working copy instead. Markdown carries
     * no {@code eol} setting in {@code .gitattributes}, so a Windows checkout has
     * CRLF and the Linux CI runner has LF — about 5% apart on a file this size, on
     * identical content. Measuring the working copy would therefore make the cap
     * mean something different per platform, and the first symptom would be a
     * Windows developer being told to rotate a changelog that CI is perfectly happy
     * with.
     */
    private static long normalisedSize(Path file) {
        return read(file).replace("\r\n", "\n").getBytes(StandardCharsets.UTF_8).length;
    }

    private static Path repoRoot() {
        // Surefire runs with the project basedir as the working directory.
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
