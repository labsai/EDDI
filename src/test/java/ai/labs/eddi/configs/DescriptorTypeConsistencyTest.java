/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs;

import ai.labs.eddi.utils.RestUtilities;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every {@code read*Descriptors} listing must query the namespace its own store
 * actually writes to.
 * <p>
 * {@code DescriptorStore.readDescriptors} filters by regex-matching the stored
 * resource URI against {@code "eddi://" + type + ".*"}, so a {@code type} that
 * is not the URI's namespace segment matches nothing — and matches nothing
 * <em>silently</em>, returning an empty list rather than an error. Three stores
 * shipped exactly that: rulesets queried {@code ai.labs.behavior} against
 * {@code eddi://ai.labs.rules/…}, api calls {@code ai.labs.httpcalls} against
 * {@code eddi://ai.labs.apicalls/…}, and dictionaries
 * {@code ai.labs.regulardictionary} against
 * {@code eddi://ai.labs.dictionary/…}. Ten ruleset and twenty-two apicall
 * descriptors existed on a live instance while their listings returned
 * {@code []}, so anything browsing behavior rules, HTTP calls or dictionaries —
 * the Manager included — saw nothing at all.
 * <p>
 * The mismatch is possible because the legacy config-file names and the v6 URI
 * names differ (AGENTS.md §5.5) and each store restated its type as a literal.
 * The fix derives the type from the store's own {@code resourceURI}; this test
 * guards the remaining freedom to hard-code one anyway, by reading the sources
 * rather than the runtime — nothing else fails when the two drift apart.
 */
@DisplayName("descriptor type matches the store's own URI namespace")
class DescriptorTypeConsistencyTest {

    /**
     * {@code String resourceBaseType = "eddi://ai.labs.x";} in an IRest*Store
     * interface.
     */
    private static final Pattern BASE_TYPE = Pattern.compile(
            "String\\s+resourceBaseType\\s*=\\s*\"([^\"]+)\"");

    /**
     * {@code String resourceURI = "eddi://…";} or {@code = resourceBaseType + "…";}
     */
    private static final Pattern RESOURCE_URI = Pattern.compile(
            "String\\s+resourceURI\\s*=\\s*(?:resourceBaseType\\s*\\+\\s*)?\"([^\"]+)\"");

    /**
     * A hard-coded descriptor type handed to any {@code readDescriptors} overload.
     */
    private static final Pattern HARDCODED_TYPE = Pattern.compile(
            "readDescriptors\\(\\s*(?:eq\\(\\s*)?\"(ai\\.labs\\.[A-Za-z]+)\"");

    private static Path repoRoot() {
        Path root = Path.of("").toAbsolutePath();
        assertTrue(Files.isRegularFile(root.resolve("pom.xml")),
                "expected the working directory to be the project root, was " + root);
        return root;
    }

    private static List<Path> javaFilesUnder(Path dir) {
        try (Stream<Path> paths = Files.walk(dir)) {
            return paths.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".java"))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Maps a store package (e.g. {@code configs/rules}) to the descriptor type its
     * {@code resourceURI} implies.
     */
    private static Map<String, String> declaredTypesByPackage(Path mainJava) {
        Map<String, String> byPackage = new LinkedHashMap<>();
        for (Path file : javaFilesUnder(mainJava)) {
            String name = file.getFileName().toString();
            if (!name.startsWith("IRest") || !name.endsWith("Store.java")) {
                continue;
            }
            String source = read(file);
            Matcher uri = RESOURCE_URI.matcher(source);
            if (!uri.find()) {
                continue;
            }
            String resourceURI = uri.group(1);
            if (!resourceURI.startsWith("eddi://")) {
                Matcher base = BASE_TYPE.matcher(source);
                if (!base.find()) {
                    continue;
                }
                resourceURI = base.group(1) + resourceURI;
            }
            byPackage.put(file.getParent().toString().replace('\\', '/'),
                    RestUtilities.extractDescriptorType(resourceURI));
        }
        return byPackage;
    }

    @Test
    @DisplayName("no REST store queries a descriptor type outside its own namespace")
    void restStoresQueryTheirOwnNamespace() {
        Path root = repoRoot();
        Path mainJava = root.resolve("src/main/java");
        Map<String, String> expectedByPackage = declaredTypesByPackage(mainJava);

        assertTrue(expectedByPackage.size() >= 10,
                "expected to discover the IRest*Store interfaces, found " + expectedByPackage);

        List<String> offenders = new ArrayList<>();
        for (Path file : javaFilesUnder(mainJava)) {
            String dir = file.getParent().toString().replace('\\', '/');
            // A REST implementation lives in <storePackage>/rest.
            if (!dir.endsWith("/rest")) {
                continue;
            }
            String expected = expectedByPackage.get(dir.substring(0, dir.length() - "/rest".length()));
            if (expected == null) {
                continue;
            }
            Matcher m = HARDCODED_TYPE.matcher(read(file));
            while (m.find()) {
                if (!expected.equals(m.group(1))) {
                    offenders.add(root.relativize(file).toString().replace('\\', '/')
                            + " queries \"" + m.group(1) + "\" but its resourceURI namespace is \"" + expected + "\"");
                }
            }
        }

        assertEquals(List.of(), offenders,
                "A descriptor listing that names a namespace its store does not write to returns an empty "
                        + "list forever, with no error. Call readDescriptors(filter, index, limit) — the overload "
                        + "that derives the type from resourceURI — instead of restating it.\n  "
                        + String.join("\n  ", offenders));
    }

    @Test
    @DisplayName("extractDescriptorType reads the namespace segment, not the path")
    void extractsNamespaceSegment() {
        assertEquals("ai.labs.rules",
                RestUtilities.extractDescriptorType("eddi://ai.labs.rules/rulestore/rulesets/"));
        assertEquals("ai.labs.apicalls",
                RestUtilities.extractDescriptorType("eddi://ai.labs.apicalls/apicallstore/apicalls/"));
        assertEquals("ai.labs.dictionary",
                RestUtilities.extractDescriptorType("eddi://ai.labs.dictionary/dictionarystore/dictionaries/"));
        // Tolerates a bare namespace and a missing scheme rather than returning "".
        assertEquals("ai.labs.agent", RestUtilities.extractDescriptorType("eddi://ai.labs.agent"));
        assertEquals("ai.labs.agent", RestUtilities.extractDescriptorType("ai.labs.agent/agentstore/agents/"));
    }
}
