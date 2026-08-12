/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.docs;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Keeps the documentation's internal links honest.
 * <p>
 * A repository review found 38 broken relative links. They were not 38 separate
 * mistakes: 32 came from one, when {@code planning/} moved to the repository
 * root and every file in it kept computing {@code ../} and {@code ../../} as
 * though it still lived under {@code docs/}. So {@code ../../AGENTS.md} pointed
 * outside the repository entirely, and nothing noticed for as long as those
 * files existed. The rest pointed into a {@code .gitbook/assets/} directory
 * that does not exist in this repository at all, which broke the
 * <em>onboarding</em> tutorials specifically — the pages a new user reads
 * first.
 * <p>
 * Link rot is invisible to every other check in this build: markdown compiles
 * to nothing, so a wrong path is indistinguishable from a right one until a
 * human clicks it. This test is the check.
 */
@DisplayName("documentation links")
class DocumentationLinksTest {

    /**
     * Markdown inline links, e.g. {@code [text](target)}. The target group stops at
     * the first {@code )}, which is why targets containing parentheses are excluded
     * below rather than parsed.
     */
    private static final Pattern LINK = Pattern.compile("]\\(([^)]+)\\)");

    /** Fenced code blocks — their contents are examples, not links. */
    private static final Pattern FENCE = Pattern.compile("(?ms)^```.*?^```");

    /** Inline code spans, for the same reason. */
    private static final Pattern CODE_SPAN = Pattern.compile("`[^`\\n]*`");

    /** Directories with no documentation to check (or not ours to check). */
    private static final Set<String> SKIPPED_DIRS = Set.of("target", ".git", "node_modules", ".claude", ".mvn");

    private static Path repoRoot() {
        // Surefire runs with the project basedir as the working directory.
        Path root = Path.of("").toAbsolutePath();
        assertTrue(Files.isRegularFile(root.resolve("pom.xml")),
                "expected the working directory to be the project root, was " + root);
        return root;
    }

    /**
     * Collects every markdown file, <em>pruning</em> the directories in
     * {@link #SKIPPED_DIRS} rather than walking them and filtering afterwards.
     * <p>
     * The distinction is not cosmetic here. {@code target/} always exists when
     * these tests run and holds tens of thousands of class files, {@code .git/} is
     * comparably large, and this repository's own agent worktrees live under
     * {@code .claude/} — so a filter-after-walk pays the full I/O cost of all three
     * on every run, and would recurse through nested checkouts.
     */
    private static List<Path> markdownFiles(Path root) {
        List<Path> found = new ArrayList<>();
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (!dir.equals(root) && SKIPPED_DIRS.contains(dir.getFileName().toString())) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (attrs.isRegularFile()
                            && file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".md")) {
                        found.add(file);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE; // an unreadable entry is not this test's business
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return found;
    }

    /**
     * Strips fenced blocks and inline code so that documentation OF link syntax —
     * {@code `![alt](uri)`} in the output-format tables, for instance — is not
     * mistaken for a link TO something called "uri".
     */
    private static String stripCode(String markdown) {
        String withoutFences = FENCE.matcher(markdown).replaceAll("");
        return CODE_SPAN.matcher(withoutFences).replaceAll("");
    }

    private static boolean isExternalOrAnchor(String target) {
        String t = target.trim();
        return t.isEmpty()
                || t.startsWith("#")
                || t.startsWith("http://")
                || t.startsWith("https://")
                || t.startsWith("mailto:")
                || t.startsWith("<http")
                // A target carrying an unbalanced '(' was truncated by LINK; do not
                // guess at what the author meant.
                || t.contains("(");
    }

    /**
     * Returns the on-disk name when {@code resolved} exists only by a
     * case-insensitive match, else {@code null}.
     * <p>
     * This matters more than it looks. Windows and macOS resolve
     * {@code ../security.md} to {@code SECURITY.md}, so a link that is broken on
     * the Linux CI runner — and for every reader of the published docs — looks
     * perfectly fine to the developer who wrote it. That is exactly how
     * {@code planning/agentic-improvements-plan.md} came to point at the security
     * <em>policy</em> at the repository root while its own link text said
     * {@code docs/security.md}. Without this check the test would have inherited
     * the same blind spot as the human.
     */
    private static String mismatchedCasing(Path resolved) {
        try {
            Path real = resolved.toRealPath(LinkOption.NOFOLLOW_LINKS);
            String actual = real.getFileName().toString();
            String requested = resolved.getFileName().toString();
            return actual.equals(requested) ? null : actual;
        } catch (IOException e) {
            return null; // existence was already established; nothing more to say
        }
    }

    @Test
    @DisplayName("every relative link in every tracked markdown file resolves to a real path")
    void everyRelativeLinkResolves() {
        Path root = repoRoot();
        List<String> broken = new ArrayList<>();

        for (Path file : markdownFiles(root)) {
            String body;
            try {
                body = Files.readString(file, StandardCharsets.UTF_8);
            } catch (IOException | RuntimeException e) {
                continue; // unreadable/non-UTF-8 files are not this test's business
            }
            Matcher m = LINK.matcher(stripCode(body));
            while (m.find()) {
                String target = m.group(1).trim();
                if (isExternalOrAnchor(target)) {
                    continue;
                }
                // Drop any '#fragment' and surrounding angle brackets before resolving.
                String pathPart = target.replaceAll("^<|>$", "");
                int hash = pathPart.indexOf('#');
                if (hash >= 0) {
                    pathPart = pathPart.substring(0, hash);
                }
                if (pathPart.isBlank()) {
                    continue;
                }
                String decoded = URLDecoder.decode(pathPart, StandardCharsets.UTF_8);
                // A leading '/' is repository-root-relative (how the forge renders
                // it), NOT filesystem-absolute — resolving it against the file's
                // own directory would send it to the drive root.
                Path resolved = decoded.startsWith("/")
                        ? root.resolve(decoded.substring(1)).normalize()
                        : file.getParent().resolve(decoded).normalize();
                if (!Files.exists(resolved)) {
                    broken.add(root.relativize(file) + "  ->  " + target);
                } else {
                    String actualCasing = mismatchedCasing(resolved);
                    if (actualCasing != null) {
                        broken.add(root.relativize(file) + "  ->  " + target
                                + "  (case mismatch — the file on disk is '" + actualCasing + "')");
                    }
                }
            }
        }

        assertEquals(List.of(), broken,
                "broken relative link(s) in documentation:\n  " + String.join("\n  ", broken));
    }

    /**
     * {@code SUMMARY.md} is the published table of contents. A page missing from it
     * still renders, still passes every other check, and is simply unreachable by
     * navigation — which is how {@code security-review.md} and
     * {@code release-notes-6.0.2.md} went unlisted.
     */
    @Test
    @DisplayName("every page under docs/ is reachable from SUMMARY.md")
    void everyDocIsListedInSummary() throws IOException {
        Path docs = repoRoot().resolve("docs");
        String summary = Files.readString(docs.resolve("SUMMARY.md"), StandardCharsets.UTF_8);

        Set<String> missing = new TreeSet<>();
        try (Stream<Path> paths = Files.list(docs)) {
            for (Path p : paths.filter(Files::isRegularFile).toList()) {
                String name = p.getFileName().toString();
                if (!name.endsWith(".md") || name.equals("SUMMARY.md") || name.equals("README.md")) {
                    continue; // the index itself, and the folder's own landing page
                }
                if (!summary.contains("(" + name + ")") && !summary.contains("/" + name + ")")) {
                    missing.add(name);
                }
            }
        }

        assertEquals(Set.of(), missing,
                "these pages exist under docs/ but nothing in SUMMARY.md links to them, so they are "
                        + "unreachable in the published docs: " + missing);
    }
}
