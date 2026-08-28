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
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Keeps the documentation's REST paths on the v6 spelling.
 * <p>
 * The v5→v6 rename gave every configuration store a new path and a new
 * {@code eddi://} namespace, and {@code LegacyPathRewriteFilter} keeps the old
 * ones answering so that existing clients do not break. That compatibility is
 * exactly what let the documentation rot invisibly: a reader following
 * {@code POST /packagestore/packages} got a {@code 201}, so nothing anywhere
 * said the page was five years out of date — until the payload shape had
 * drifted too, at which point the same page produced a {@code 400} with no body
 * and no way to tell which half was wrong.
 * <p>
 * That is not hypothetical. A developer working through
 * {@code developer-quickstart.md} in August 2026 hit four different failures in
 * seven steps, every one of them the documentation rather than the server, and
 * had to reverse-engineer the real payloads from the OpenAPI page.
 * <p>
 * So: legacy spellings stay supported on the wire and stay banned in prose.
 * {@link #legacyChangeRecords} lists the files that legitimately quote the old
 * names — a changelog describing the rename has to be able to name what was
 * renamed.
 *
 * @see ai.labs.eddi.engine.runtime.rest.interceptors.LegacyPathRewriteFilter
 */
@DisplayName("documented REST paths")
class DocumentedRestPathsTest {

    /**
     * Legacy spelling → the v6 spelling that replaced it, in the message.
     * <p>
     * Keyed on the store path rather than the full URI so that both forms are
     * caught by one entry: the bare REST path ({@code /behaviorstore/behaviorsets})
     * and the resource URI that contains it
     * ({@code eddi://ai.labs.behavior/behaviorstore/behaviorsets/…}).
     */
    private static final Map<String, String> LEGACY_TO_V6 = new LinkedHashMap<>();

    static {
        LEGACY_TO_V6.put("/regulardictionarystore/regulardictionaries", "/dictionarystore/dictionaries");
        LEGACY_TO_V6.put("/behaviorstore/behaviorsets", "/rulestore/rulesets");
        LEGACY_TO_V6.put("/httpcallsstore/httpcalls", "/apicallstore/apicalls");
        LEGACY_TO_V6.put("/langchainstore/langchains", "/llmstore/llms");
        LEGACY_TO_V6.put("/packagestore/packages", "/workflowstore/workflows");
        LEGACY_TO_V6.put("/botstore/bots", "/agentstore/agents");
        LEGACY_TO_V6.put("/bottriggerstore/bottriggers", "/AgentTriggerStore/agenttriggers");
        LEGACY_TO_V6.put("/langchain/tools", "/llm/tools");
    }

    /**
     * Documents whose subject IS the rename, so they have to spell out both sides
     * of it. Repository-relative, forward-slashed; a trailing {@code /} matches
     * everything beneath it.
     * <p>
     * {@code docs/changelog/} is a prefix rather than a list of files because the
     * changelog rotates: entries move out of {@code docs/changelog.md} into a new
     * {@code <YYYY-MM>.md} each time the live file fills up. Naming the months
     * individually would mean this test starts failing on a routine rotation, at
     * which point somebody deletes the assertion rather than the offending path.
     */
    private static Set<String> legacyChangeRecords() {
        return Set.of("docs/changelog.md", "docs/changelog/", "docs/archive/handoff-v6.0-snapshot.md");
    }

    /**
     * True when {@code relative} is exempt, directly or under an exempt directory.
     */
    private static boolean isChangeRecord(String relative) {
        for (String exempt : legacyChangeRecords()) {
            if (exempt.endsWith("/") ? relative.startsWith(exempt) : relative.equals(exempt)) {
                return true;
            }
        }
        return false;
    }

    private static final Set<String> SKIPPED_DIRS = Set.of("target", ".git", "node_modules", ".claude", ".mvn");

    /**
     * Payload keys that no longer parse at all, unlike the paths above.
     * <p>
     * {@code packageExtensions} was the v5 name for {@code workflowSteps}. Strict
     * configuration parsing rejects it with a 400 naming the field, so every
     * documented workflow payload carrying it was an instruction that could not
     * work — five pages still had one when this test was written, because the v6
     * sweep had corrected the store paths and left the payload shapes alone.
     */
    @Test
    @DisplayName("no markdown file documents a payload key the API rejects")
    void noRejectedPayloadKeysInDocumentation() {
        Path root = repoRoot();
        var offences = new TreeSet<String>();

        for (Path file : markdownFiles(root)) {
            String relative = root.relativize(file).toString().replace('\\', '/');
            if (isChangeRecord(relative)) {
                continue;
            }

            String[] lines = read(file).split("\r?\n", -1);
            for (int i = 0; i < lines.length; i++) {
                if (lines[i].contains("\"packageExtensions\"")) {
                    offences.add(String.format("%s:%d uses 'packageExtensions' — write 'workflowSteps'", relative, i + 1));
                }
            }
        }

        assertTrue(offences.isEmpty(),
                "These payloads are rejected by the server with a 400, so the pages carrying them cannot be followed:\n  "
                        + String.join("\n  ", offences));
    }

    @Test
    @DisplayName("no markdown file documents a pre-v6 store path")
    void noLegacyStorePathsInDocumentation() {
        Path root = repoRoot();
        var offences = new TreeSet<String>();

        for (Path file : markdownFiles(root)) {
            String relative = root.relativize(file).toString().replace('\\', '/');
            if (isChangeRecord(relative)) {
                continue;
            }

            String text = read(file);
            String[] lines = text.split("\r?\n", -1);
            for (int i = 0; i < lines.length; i++) {
                for (var entry : LEGACY_TO_V6.entrySet()) {
                    if (lines[i].contains(entry.getKey()) && !allowedMentions().contains(relative + " → " + entry.getKey())) {
                        offences.add(String.format("%s:%d uses '%s' — write '%s'",
                                relative, i + 1, entry.getKey(), entry.getValue()));
                    }
                }
            }
        }

        assertTrue(offences.isEmpty(),
                "Documentation must use the v6 store paths. The pre-v6 ones still answer, because "
                        + "LegacyPathRewriteFilter rewrites them, so a reader following these gets a plausible "
                        + "response from the wrong half of the API and no warning at all:\n  "
                        + String.join("\n  ", offences));
    }

    /**
     * The exact places a pre-v6 path may still be named, as {@code file → path}.
     * <p>
     * A first attempt exempted any line containing the word "legacy", which is too
     * coarse to be a rule: {@code Legacy endpoint: POST /packagestore/packages}
     * would have passed it while still being a stale instruction, which is the one
     * thing this test exists to stop. An explicit pair has to be added
     * deliberately, and shows up in review as what it is.
     */
    private static Set<String> allowedMentions() {
        return Set.of(
                // AGENTS.md §5.5 explains that legacy URIs are auto-normalized on
                // import, which cannot be written without naming one.
                "AGENTS.md → /botstore/bots",
                // metrics.md warns readers off this exact prefix. The warning is the
                // reason the entry above it exists: six tool endpoints were documented
                // under /langchain/tools, all of which answered — LegacyPathRewriteFilter
                // rewrote them — so nothing looked wrong until you compared the page
                // against RestToolHistory and found the base path had moved to /llm/tools.
                "docs/metrics.md → /langchain/tools");
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

    /**
     * Every markdown file, with {@link #SKIPPED_DIRS} <em>pruned</em> rather than
     * walked and filtered — {@code target/}, {@code .git/} and this repository's
     * agent worktrees under {@code .claude/} are each large enough that the
     * difference is seconds per run.
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
                    if (attrs.isRegularFile() && file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".md")) {
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
}
