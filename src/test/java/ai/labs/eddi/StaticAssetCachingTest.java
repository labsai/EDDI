/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the shipped static assets and the one-year cache header applied to
 * them.
 * <p>
 * Two review findings meet here. The assets tree had accumulated a complete
 * second Vite build — 19 files, 9.3 MB, roughly a fifth of the directory — that
 * no page referenced and that only imported each other: a closed island with no
 * entry point. Nothing detected it because the {@code .manager-assets} manifest
 * the Manager sync writes is advisory; no code, build step or workflow reads
 * it. Separately the immutable cache filter matched
 * {@code /(scripts|assets)/.*}, which caught {@code landing-redirect.js} —
 * un-hashed, and loaded by the landing page every visitor hits first. A fix to
 * it would have gone unseen for up to a year with no revalidation request even
 * sent, because {@code immutable} suppresses one.
 * <p>
 * The two are guarded together because the second depends on the first: the
 * filter is allowed to treat all of {@code /assets/} as immutable precisely
 * because every file there is machine-generated and content-hashed, which is
 * what {@link #everyAssetCarriesAContentHash()} asserts.
 */
@DisplayName("static assets and cache headers")
class StaticAssetCachingTest {

    private static final String ASSETS_DIR = "src/main/resources/META-INF/resources/assets";
    private static final String SCRIPTS_DIR = "src/main/resources/META-INF/resources/scripts";
    private static final String MANIFEST = ".manager-assets";
    private static final String APPLICATION_PROPERTIES = "src/main/resources/application.properties";

    /**
     * A Vite content hash: eight characters of its base64url alphabet, separated
     * from the stem by {@code -} or {@code .} and sitting immediately before the
     * extension.
     */
    private static final Pattern HASHED = Pattern.compile(".+[.-][A-Za-z0-9_-]{8}\\.[A-Za-z0-9]+");

    private static Path repoRoot() {
        Path root = Path.of("").toAbsolutePath();
        assertTrue(Files.isRegularFile(root.resolve("pom.xml")),
                "expected the working directory to be the project root, was " + root);
        return root;
    }

    private static Set<String> filesIn(String relativeDir) {
        Path dir = repoRoot().resolve(relativeDir);
        assertTrue(Files.isDirectory(dir), relativeDir + " is missing");
        try (Stream<Path> entries = Files.list(dir)) {
            Set<String> names = new TreeSet<>();
            entries.filter(Files::isRegularFile).map(p -> p.getFileName().toString()).filter(n -> !n.startsWith(".")).forEach(names::add);
            return names;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Set<String> manifestEntries() {
        Path manifest = repoRoot().resolve(ASSETS_DIR).resolve(MANIFEST);
        assertTrue(Files.isRegularFile(manifest), MANIFEST + " is missing from " + ASSETS_DIR);
        try {
            // The manifest is written by the Manager sync on Windows, so its line
            // endings are CRLF; comparing without stripping them matches nothing.
            Set<String> entries = new TreeSet<>();
            for (String line : Files.readAllLines(manifest, StandardCharsets.UTF_8)) {
                String name = line.strip();
                if (!name.isEmpty()) {
                    entries.add(name);
                }
            }
            return entries;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * The manifest is what the Manager sync says it shipped; the directory is what
     * is actually in the jar and the image. When they disagree, the difference is
     * either dead weight nobody can load or a live file nobody recorded — and
     * neither is visible to a reviewer diffing 700-odd hashed filenames.
     */
    @Test
    @DisplayName("the assets directory and .manager-assets agree in both directions")
    void assetsDirectoryMatchesItsManifest() {
        Set<String> onDisk = filesIn(ASSETS_DIR);
        Set<String> declared = manifestEntries();

        Set<String> orphaned = new TreeSet<>(onDisk);
        orphaned.removeAll(declared);
        assertTrue(orphaned.isEmpty(), orphaned.size() + " asset file(s) are on disk but absent from " + MANIFEST
                + ", so they ship in every jar and image with no page able to load them: " + orphaned);

        Set<String> missing = new TreeSet<>(declared);
        missing.removeAll(onDisk);
        assertTrue(missing.isEmpty(),
                missing.size() + " file(s) are listed in " + MANIFEST + " but are not on disk, so a page may 404: " + missing);
    }

    /**
     * Justifies the cache filter treating all of {@code /assets/} as immutable. If
     * an un-hashed file ever lands here, that assumption stops holding and this
     * test says so before a year-long cache pin does.
     */
    @Test
    @DisplayName("every shipped asset carries a content hash")
    void everyAssetCarriesAContentHash() {
        Set<String> unhashed = new TreeSet<>();
        for (String name : filesIn(ASSETS_DIR)) {
            if (!HASHED.matcher(name).matches()) {
                unhashed.add(name);
            }
        }
        assertTrue(unhashed.isEmpty(), "these assets carry no content hash, so the immutable cache header would pin a mutable file: " + unhashed);
    }

    /**
     * The filter's own regex, applied to the paths it will actually see. Asserting
     * the pattern string would only restate the config; asserting its behaviour on
     * {@code landing-redirect.js} is the thing that was wrong.
     */
    @Test
    @DisplayName("the immutable cache filter pins hashed bundles and never an un-hashed script")
    void immutableCacheFilterOnlyMatchesHashedFiles() {
        Properties props = new Properties();
        try {
            props.load(Files.newBufferedReader(repoRoot().resolve(APPLICATION_PROPERTIES), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        String raw = props.getProperty("quarkus.http.filter.ui-assets-immutable.matches");
        assertTrue(raw != null && !raw.isBlank(), "the ui-assets-immutable filter has no 'matches' pattern");
        Pattern filter = Pattern.compile(raw);

        assertTrue(filter.matcher("/assets/index-BnLj1hck.js").matches(), "the live Manager bundle should be cached immutably");
        assertTrue(filter.matcher("/scripts/js/chat-ui.DHWeOytM.js").matches(), "a hashed script should be cached immutably");
        assertTrue(filter.matcher("/scripts/css/chat-ui.DZ5NqRHZ.css").matches(), "a hashed stylesheet should be cached immutably");

        // The landing redirect is the first thing every visitor loads, so a stale copy
        // is both maximally visible and, under `immutable`, unfixable without renaming
        // the file.
        assertFalse(filter.matcher("/scripts/js/landing-redirect.js").matches(),
                "landing-redirect.js carries no content hash and must not be pinned for a year");
        assertFalse(filter.matcher("/scripts/js/some-new-helper.js").matches(), "a future un-hashed script must fall back to normal caching");
    }

    /**
     * Everything under {@code /scripts/} that the filter would pin must in fact be
     * hashed. This is the pair to {@link #everyAssetCarriesAContentHash()} for the
     * hand-maintained half of the tree, where a new file is written by a person
     * rather than emitted by a bundler.
     */
    @Test
    @DisplayName("no un-hashed script is matched by the immutable filter")
    void handMaintainedScriptsAreEitherHashedOrNotPinned() {
        Properties props = new Properties();
        try {
            props.load(Files.newBufferedReader(repoRoot().resolve(APPLICATION_PROPERTIES), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Pattern filter = Pattern.compile(props.getProperty("quarkus.http.filter.ui-assets-immutable.matches"));

        Set<String> pinnedButUnhashed = new TreeSet<>();
        for (String sub : List.of("js", "css")) {
            Path dir = repoRoot().resolve(SCRIPTS_DIR).resolve(sub);
            if (!Files.isDirectory(dir)) {
                continue;
            }
            for (String name : filesIn(SCRIPTS_DIR + "/" + sub)) {
                String path = "/scripts/" + sub + "/" + name;
                if (filter.matcher(path).matches() && !HASHED.matcher(name).matches()) {
                    pinnedButUnhashed.add(path);
                }
            }
        }
        assertTrue(pinnedButUnhashed.isEmpty(),
                "these scripts would be cached for a year but carry no content hash, so a fix to them could not reach a returning "
                        + "visitor: " + pinnedButUnhashed);
    }
}
