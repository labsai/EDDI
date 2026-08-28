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

    /** An archive filename: exactly {@code YYYY-MM.md}. */
    private static final Pattern ARCHIVE_NAME = Pattern.compile("^(\\d{4})-(\\d{2})\\.md$");

    @Test
    @DisplayName("the live changelog stays under the rotation cap")
    void liveChangelogIsUnderCap() {
        Path root = repoRoot();
        long size = sizeOf(root.resolve(LIVE));

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

        String live = read(root.resolve(LIVE));
        var problems = new TreeSet<String>();

        for (Path file : listMarkdown(archives)) {
            String name = file.getFileName().toString();
            Matcher m = ARCHIVE_NAME.matcher(name);
            if (!m.matches()) {
                problems.add(name + " — archives must be named YYYY-MM.md, one per month");
                continue;
            }
            int month = Integer.parseInt(m.group(2));
            if (month < 1 || month > 12) {
                problems.add(name + " — month " + m.group(2) + " is not a month");
            }
            // The Archive table is the only way a reader finds these; an
            // unlisted file is rotated-away-and-lost rather than archived.
            if (!live.contains("changelog/" + name)) {
                problems.add(name + " — not linked from the Archive table in docs/changelog.md");
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

    private static long sizeOf(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
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
