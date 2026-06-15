/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.backup.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests targeting uncovered branches in {@link RestImportService} and
 * {@link AbstractBackupService} that are NOT reached by any existing test
 * class.
 * <p>
 * Focus areas:
 * <ul>
 * <li>{@code extractResourcesUris} — various URI patterns and edge cases</li>
 * <li>{@code replaceURIs} — URI replacement with matching and non-matching
 * keys</li>
 * <li>{@code normalizeLegacyUris} — each individual rewrite pattern in
 * isolation</li>
 * <li>{@code normalizeVaultReferences} — deeply nested JSON edge cases</li>
 * <li>{@code findSnippetsDir} — IOException during directory scanning</li>
 * <li>{@code readFile} — empty file edge case</li>
 * <li>URI Pattern constants — verify they compile and match expected
 * strings</li>
 * </ul>
 */
@DisplayName("RestImportService — Uncovered Branch Coverage")
class RestImportServiceUncoveredBranchTest {

    private RestImportService service;

    @BeforeEach
    void setUp() throws Exception {
        service = createMinimalInstance();
    }

    // =========================================================
    // extractResourcesUris — comprehensive URI pattern matching
    // =========================================================

    @Nested
    @DisplayName("extractResourcesUris — URI extraction")
    class ExtractResourcesUris {

        @Test
        @DisplayName("extracts dictionary URIs from JSON string")
        void extractDictionaryUris() throws Exception {
            String input = "\"eddi://ai.labs.dictionary/dictionarystore/dictionaries/abc123?version=1\"";
            List<URI> result = invokeExtractResourcesUris(input, AbstractBackupService.DICTIONARY_URI_PATTERN);
            assertEquals(1, result.size());
            assertEquals("eddi://ai.labs.dictionary/dictionarystore/dictionaries/abc123?version=1",
                    result.getFirst().toString());
        }

        @Test
        @DisplayName("extracts behavior URIs from JSON string")
        void extractBehaviorUris() throws Exception {
            String input = "\"eddi://ai.labs.rules/rulestore/rulesets/rule1?version=2\"";
            List<URI> result = invokeExtractResourcesUris(input, AbstractBackupService.BEHAVIOR_URI_PATTERN);
            assertEquals(1, result.size());
        }

        @Test
        @DisplayName("extracts httpcalls URIs from JSON string")
        void extractHttpcallsUris() throws Exception {
            String input = "\"eddi://ai.labs.apicalls/apicallstore/apicalls/api1?version=1\"";
            List<URI> result = invokeExtractResourcesUris(input, AbstractBackupService.HTTPCALLS_URI_PATTERN);
            assertEquals(1, result.size());
        }

        @Test
        @DisplayName("extracts langchain URIs from JSON string")
        void extractLangchainUris() throws Exception {
            String input = "\"eddi://ai.labs.llm/llmstore/llms/llm1?version=1\"";
            List<URI> result = invokeExtractResourcesUris(input, AbstractBackupService.LANGCHAIN_URI_PATTERN);
            assertEquals(1, result.size());
        }

        @Test
        @DisplayName("extracts property URIs from JSON string")
        void extractPropertyUris() throws Exception {
            String input = "\"eddi://ai.labs.property/propertysetterstore/propertysetters/prop1?version=1\"";
            List<URI> result = invokeExtractResourcesUris(input, AbstractBackupService.PROPERTY_URI_PATTERN);
            assertEquals(1, result.size());
        }

        @Test
        @DisplayName("extracts output URIs from JSON string")
        void extractOutputUris() throws Exception {
            String input = "\"eddi://ai.labs.output/outputstore/outputsets/out1?version=1\"";
            List<URI> result = invokeExtractResourcesUris(input, AbstractBackupService.OUTPUT_URI_PATTERN);
            assertEquals(1, result.size());
        }

        @Test
        @DisplayName("extracts mcpcalls URIs from JSON string")
        void extractMcpcallsUris() throws Exception {
            String input = "\"eddi://ai.labs.mcpcalls/mcpcallsstore/mcpcalls/mcp1?version=1\"";
            List<URI> result = invokeExtractResourcesUris(input, AbstractBackupService.MCPCALLS_URI_PATTERN);
            assertEquals(1, result.size());
        }

        @Test
        @DisplayName("extracts rag URIs from JSON string")
        void extractRagUris() throws Exception {
            String input = "\"eddi://ai.labs.rag/ragstore/rags/rag1?version=1\"";
            List<URI> result = invokeExtractResourcesUris(input, AbstractBackupService.RAG_URI_PATTERN);
            assertEquals(1, result.size());
        }

        @Test
        @DisplayName("extracts multiple URIs from JSON string")
        void extractMultipleUris() throws Exception {
            String input = "\"eddi://ai.labs.dictionary/dictionarystore/dictionaries/a?version=1\" " +
                    "\"eddi://ai.labs.dictionary/dictionarystore/dictionaries/b?version=2\"";
            List<URI> result = invokeExtractResourcesUris(input, AbstractBackupService.DICTIONARY_URI_PATTERN);
            assertEquals(2, result.size());
        }

        @Test
        @DisplayName("returns empty list when no URIs match")
        void noMatchingUris() throws Exception {
            String input = "some random text without any eddi URIs";
            List<URI> result = invokeExtractResourcesUris(input, AbstractBackupService.DICTIONARY_URI_PATTERN);
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("returns empty list for empty input")
        void emptyInput() throws Exception {
            List<URI> result = invokeExtractResourcesUris("", AbstractBackupService.DICTIONARY_URI_PATTERN);
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("extracts legacy dictionary URIs")
        void extractLegacyDictionaryUris() throws Exception {
            String input = "\"eddi://ai.labs.regulardictionary/regulardictionarystore/regulardictionaries/d1?version=1\"";
            List<URI> result = invokeExtractResourcesUris(input, AbstractBackupService.LEGACY_DICTIONARY_URI_PATTERN);
            assertEquals(1, result.size());
        }

        @Test
        @DisplayName("extracts legacy behavior URIs")
        void extractLegacyBehaviorUris() throws Exception {
            String input = "\"eddi://ai.labs.behavior/behaviorstore/behaviorsets/b1?version=1\"";
            List<URI> result = invokeExtractResourcesUris(input, AbstractBackupService.LEGACY_BEHAVIOR_URI_PATTERN);
            assertEquals(1, result.size());
        }

        @Test
        @DisplayName("extracts legacy httpcalls URIs")
        void extractLegacyHttpcallsUris() throws Exception {
            String input = "\"eddi://ai.labs.httpcalls/httpcallsstore/httpcalls/h1?version=1\"";
            List<URI> result = invokeExtractResourcesUris(input, AbstractBackupService.LEGACY_HTTPCALLS_URI_PATTERN);
            assertEquals(1, result.size());
        }

        @Test
        @DisplayName("extracts legacy langchain URIs")
        void extractLegacyLangchainUris() throws Exception {
            String input = "\"eddi://ai.labs.langchain/langchainstore/langchains/l1?version=1\"";
            List<URI> result = invokeExtractResourcesUris(input, AbstractBackupService.LEGACY_LANGCHAIN_URI_PATTERN);
            assertEquals(1, result.size());
        }

        @Test
        @DisplayName("extracts legacy workflow URIs")
        void extractLegacyWorkflowUris() throws Exception {
            String input = "\"eddi://ai.labs.package/packagestore/packages/p1?version=1\"";
            List<URI> result = invokeExtractResourcesUris(input, AbstractBackupService.LEGACY_WORKFLOW_URI_PATTERN);
            assertEquals(1, result.size());
        }

        @Test
        @DisplayName("extracts legacy agent URIs")
        void extractLegacyAgentUris() throws Exception {
            String input = "\"eddi://ai.labs.bot/botstore/bots/bot1?version=1\"";
            List<URI> result = invokeExtractResourcesUris(input, AbstractBackupService.LEGACY_AGENT_URI_PATTERN);
            assertEquals(1, result.size());
        }

        @SuppressWarnings("unchecked")
        private List<URI> invokeExtractResourcesUris(String input, Pattern pattern) throws Exception {
            Method method = AbstractBackupService.class.getDeclaredMethod(
                    "extractResourcesUris", String.class, java.util.regex.Pattern.class);
            method.setAccessible(true);
            return (List<URI>) method.invoke(service, input, pattern);
        }
    }

    // =========================================================
    // replaceURIs — URI replacement logic
    // =========================================================

    @Nested
    @DisplayName("replaceURIs — URI substitution")
    class ReplaceURIs {

        @Test
        @DisplayName("replaces matching URIs in string")
        void replacesMatchingUris() throws Exception {
            String input = "\"eddi://ai.labs.rules/rulestore/rulesets/old1?version=1\"";
            List<URI> oldUris = List.of(URI.create("eddi://ai.labs.rules/rulestore/rulesets/old1?version=1"));
            List<URI> newUris = List.of(URI.create("eddi://ai.labs.rules/rulestore/rulesets/new1?version=2"));

            String result = invokeReplaceURIs(input, oldUris, newUris);
            assertTrue(result.contains("new1?version=2"));
            assertFalse(result.contains("old1?version=1"));
        }

        @Test
        @DisplayName("leaves non-matching URIs unchanged")
        void leavesNonMatchingUnchanged() throws Exception {
            String input = "\"eddi://ai.labs.rules/rulestore/rulesets/other?version=1\"";
            List<URI> oldUris = List.of(URI.create("eddi://ai.labs.rules/rulestore/rulesets/old1?version=1"));
            List<URI> newUris = List.of(URI.create("eddi://ai.labs.rules/rulestore/rulesets/new1?version=2"));

            String result = invokeReplaceURIs(input, oldUris, newUris);
            assertTrue(result.contains("other?version=1"));
        }

        @Test
        @DisplayName("replaces multiple URIs in one string")
        void replacesMultipleUris() throws Exception {
            String input = "\"eddi://ai.labs.rules/rulestore/rulesets/a?version=1\" " +
                    "\"eddi://ai.labs.llm/llmstore/llms/b?version=1\"";
            List<URI> oldUris = List.of(
                    URI.create("eddi://ai.labs.rules/rulestore/rulesets/a?version=1"),
                    URI.create("eddi://ai.labs.llm/llmstore/llms/b?version=1"));
            List<URI> newUris = List.of(
                    URI.create("eddi://ai.labs.rules/rulestore/rulesets/a?version=2"),
                    URI.create("eddi://ai.labs.llm/llmstore/llms/b?version=2"));

            String result = invokeReplaceURIs(input, oldUris, newUris);
            assertTrue(result.contains("a?version=2"));
            assertTrue(result.contains("b?version=2"));
        }

        @Test
        @DisplayName("empty URI lists return original string")
        void emptyUriLists() throws Exception {
            String input = "no eddi URIs here";
            String result = invokeReplaceURIs(input, List.of(), List.of());
            assertEquals(input, result);
        }

        private String invokeReplaceURIs(String input, List<URI> oldUris, List<URI> newUris) throws Exception {
            Method method = RestImportService.class.getDeclaredMethod(
                    "replaceURIs", String.class, List.class, List.class);
            method.setAccessible(true);
            return (String) method.invoke(service, input, oldUris, newUris);
        }
    }

    // =========================================================
    // normalizeLegacyUris — individual rewrite patterns
    // =========================================================

    @Nested
    @DisplayName("normalizeLegacyUris — individual rewrites")
    class NormalizeLegacyUrisIndividual {

        @Test
        @DisplayName("rewrites regulardictionary individually")
        void regulardictionary() {
            String input = "eddi://ai.labs.regulardictionary/regulardictionarystore/regulardictionaries/d1?version=1";
            String result = AbstractBackupService.normalizeLegacyUris(input);
            assertEquals("eddi://ai.labs.dictionary/dictionarystore/dictionaries/d1?version=1", result);
        }

        @Test
        @DisplayName("rewrites behavior individually")
        void behavior() {
            String input = "eddi://ai.labs.behavior/behaviorstore/behaviorsets/b1?version=1";
            String result = AbstractBackupService.normalizeLegacyUris(input);
            assertEquals("eddi://ai.labs.rules/rulestore/rulesets/b1?version=1", result);
        }

        @Test
        @DisplayName("rewrites httpcalls individually")
        void httpcalls() {
            String input = "eddi://ai.labs.httpcalls/httpcallsstore/httpcalls/h1?version=1";
            String result = AbstractBackupService.normalizeLegacyUris(input);
            assertEquals("eddi://ai.labs.apicalls/apicallstore/apicalls/h1?version=1", result);
        }

        @Test
        @DisplayName("rewrites langchain individually")
        void langchain() {
            String input = "eddi://ai.labs.langchain/langchainstore/langchains/l1?version=1";
            String result = AbstractBackupService.normalizeLegacyUris(input);
            assertEquals("eddi://ai.labs.llm/llmstore/llms/l1?version=1", result);
        }

        @Test
        @DisplayName("rewrites package individually")
        void packagePattern() {
            String input = "eddi://ai.labs.package/packagestore/packages/p1?version=1";
            String result = AbstractBackupService.normalizeLegacyUris(input);
            assertEquals("eddi://ai.labs.workflow/workflowstore/workflows/p1?version=1", result);
        }

        @Test
        @DisplayName("rewrites bot individually")
        void bot() {
            String input = "eddi://ai.labs.bot/botstore/bots/b1?version=1";
            String result = AbstractBackupService.normalizeLegacyUris(input);
            assertEquals("eddi://ai.labs.agent/agentstore/agents/b1?version=1", result);
        }

        @Test
        @DisplayName("rewrites all 6 patterns in single string")
        void allPatternsInSingleString() {
            String input = "eddi://ai.labs.regulardictionary/regulardictionarystore/regulardictionaries/d1 " +
                    "eddi://ai.labs.behavior/behaviorstore/behaviorsets/b1 " +
                    "eddi://ai.labs.httpcalls/httpcallsstore/httpcalls/h1 " +
                    "eddi://ai.labs.langchain/langchainstore/langchains/l1 " +
                    "eddi://ai.labs.package/packagestore/packages/p1 " +
                    "eddi://ai.labs.bot/botstore/bots/bot1";
            String result = AbstractBackupService.normalizeLegacyUris(input);
            assertTrue(result.contains("eddi://ai.labs.dictionary/dictionarystore/dictionaries/d1"));
            assertTrue(result.contains("eddi://ai.labs.rules/rulestore/rulesets/b1"));
            assertTrue(result.contains("eddi://ai.labs.apicalls/apicallstore/apicalls/h1"));
            assertTrue(result.contains("eddi://ai.labs.llm/llmstore/llms/l1"));
            assertTrue(result.contains("eddi://ai.labs.workflow/workflowstore/workflows/p1"));
            assertTrue(result.contains("eddi://ai.labs.agent/agentstore/agents/bot1"));
        }

        @Test
        @DisplayName("v6 URIs with partial legacy prefix do not match")
        void partialLegacyPrefix() {
            // Ensure partial matches don't trigger incorrectly
            String input = "eddi://ai.labs.behaviorX/behaviorstore/behaviorsets/b1?version=1";
            String result = AbstractBackupService.normalizeLegacyUris(input);
            // Should remain unchanged because it's not an exact match
            assertEquals(input, result);
        }
    }

    // =========================================================
    // normalizeVaultReferences — additional edge cases
    // =========================================================

    @Nested
    @DisplayName("normalizeVaultReferences — additional edge cases")
    class NormalizeVaultAdditional {

        @Test
        @DisplayName("handles vault reference at start of string")
        void atStartOfString() {
            String input = "${eddivault:key1} rest of config";
            assertEquals("${vault:key1} rest of config",
                    AbstractBackupService.normalizeVaultReferences(input));
        }

        @Test
        @DisplayName("handles vault reference at end of string")
        void atEndOfString() {
            String input = "config: ${eddivault:key1}";
            assertEquals("config: ${vault:key1}",
                    AbstractBackupService.normalizeVaultReferences(input));
        }

        @Test
        @DisplayName("handles vault reference with special chars in key name")
        void specialCharsInKeyName() {
            String input = "${eddivault:my-api_key.v2}";
            assertEquals("${vault:my-api_key.v2}",
                    AbstractBackupService.normalizeVaultReferences(input));
        }

        @Test
        @DisplayName("handles string containing 'eddivault' but not as vault ref")
        void notVaultRef() {
            // Contains substring "eddivault" but not as ${eddivault:...}
            String input = "the text eddivault is just text";
            // Should be unchanged since it doesn't contain "${eddivault:"
            assertEquals(input, AbstractBackupService.normalizeVaultReferences(input));
        }

        @Test
        @DisplayName("deeply nested JSON with eddivault")
        void deeplyNestedJson() {
            String input = "{\"a\":{\"b\":{\"c\":\"${eddivault:deep-key}\"}}}";
            assertEquals("{\"a\":{\"b\":{\"c\":\"${vault:deep-key}\"}}}",
                    AbstractBackupService.normalizeVaultReferences(input));
        }
    }

    // =========================================================
    // readFile — edge cases
    // =========================================================

    @Nested
    @DisplayName("readFile — edge cases")
    class ReadFileEdgeCases {

        @Test
        @DisplayName("reads empty file")
        void readsEmptyFile() throws Exception {
            Path tmpFile = Files.createTempFile("eddi-empty-test", ".json");
            try {
                Files.writeString(tmpFile, "");
                Method method = RestImportService.class.getDeclaredMethod("readFile", Path.class);
                method.setAccessible(true);
                String result = (String) method.invoke(service, tmpFile);
                assertEquals("", result);
            } finally {
                Files.deleteIfExists(tmpFile);
            }
        }

        @Test
        @DisplayName("reads file with unicode content")
        void readsUnicodeFile() throws Exception {
            Path tmpFile = Files.createTempFile("eddi-unicode-test", ".json");
            try {
                Files.writeString(tmpFile, "{\"name\":\"Agent éàü\"}");
                Method method = RestImportService.class.getDeclaredMethod("readFile", Path.class);
                method.setAccessible(true);
                String result = (String) method.invoke(service, tmpFile);
                assertEquals("{\"name\":\"Agent éàü\"}", result);
            } finally {
                Files.deleteIfExists(tmpFile);
            }
        }

        @Test
        @DisplayName("reads file with only newlines")
        void readsNewlinesOnly() throws Exception {
            Path tmpFile = Files.createTempFile("eddi-newlines-test", ".json");
            try {
                Files.writeString(tmpFile, "\n\n\n");
                Method method = RestImportService.class.getDeclaredMethod("readFile", Path.class);
                method.setAccessible(true);
                String result = (String) method.invoke(service, tmpFile);
                // readFile strips newlines (reader.readLine concatenation)
                assertEquals("", result);
            } finally {
                Files.deleteIfExists(tmpFile);
            }
        }
    }

    // =========================================================
    // findSnippetsDir — edge cases
    // =========================================================

    @Nested
    @DisplayName("findSnippetsDir — additional edge cases")
    class FindSnippetsDirEdge {

        @Test
        @DisplayName("handles directory with non-directory children")
        void directoryWithFiles() throws Exception {
            Path tmpDir = Files.createTempDirectory("eddi-snippet-files");
            try {
                // Create a file (not directory) in tmpDir
                Files.createFile(tmpDir.resolve("somefile.txt"));

                Path result = invokeFindSnippetsDir(tmpDir);
                assertNull(result);
            } finally {
                deleteRecursive(tmpDir);
            }
        }

        @Test
        @DisplayName("finds snippets in agent subdir and not version subdir")
        void snippetsInAgentSubdir() throws Exception {
            Path tmpDir = Files.createTempDirectory("eddi-snippet-agent");
            try {
                // Agent subdir has snippets directly
                Path agentDir = tmpDir.resolve("agent1");
                Files.createDirectories(agentDir);
                Path snippetsDir = agentDir.resolve("snippets");
                Files.createDirectory(snippetsDir);

                // Also create version subdir without snippets
                Path versionDir = agentDir.resolve("1");
                Files.createDirectories(versionDir);

                Path result = invokeFindSnippetsDir(tmpDir);
                assertNotNull(result);
                assertEquals(snippetsDir, result);
            } finally {
                deleteRecursive(tmpDir);
            }
        }

        @Test
        @DisplayName("returns null for empty directory")
        void emptyDirectory() throws Exception {
            Path tmpDir = Files.createTempDirectory("eddi-snippet-empty");
            try {
                Path result = invokeFindSnippetsDir(tmpDir);
                assertNull(result);
            } finally {
                deleteRecursive(tmpDir);
            }
        }

        private Path invokeFindSnippetsDir(Path path) throws Exception {
            Method method = RestImportService.class.getDeclaredMethod("findSnippetsDir", Path.class);
            method.setAccessible(true);
            return (Path) method.invoke(service, path);
        }

        private void deleteRecursive(Path path) {
            try {
                Files.walk(path).sorted(Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (Exception ignored) {
                    }
                });
            } catch (Exception ignored) {
            }
        }
    }

    // =========================================================
    // LEGACY_URI_REWRITES — verify static array structure
    // =========================================================

    @Nested
    @DisplayName("LEGACY_URI_REWRITES — static array")
    class LegacyUriRewrites {

        @Test
        @DisplayName("has exactly 6 rewrite entries")
        void hasSixEntries() {
            assertEquals(6, AbstractBackupService.LEGACY_URI_REWRITES.length);
        }

        @Test
        @DisplayName("each entry has exactly 2 elements (old, new)")
        void eachEntryHasTwoElements() {
            for (String[] entry : AbstractBackupService.LEGACY_URI_REWRITES) {
                assertEquals(2, entry.length);
                assertNotNull(entry[0]);
                assertNotNull(entry[1]);
                assertTrue(entry[0].startsWith("eddi://"));
                assertTrue(entry[1].startsWith("eddi://"));
            }
        }

        @Test
        @DisplayName("old patterns differ from new patterns")
        void oldDiffersFromNew() {
            for (String[] entry : AbstractBackupService.LEGACY_URI_REWRITES) {
                assertNotEquals(entry[0], entry[1],
                        "Rewrite should not map to itself: " + entry[0]);
            }
        }
    }

    // =========================================================
    // Extension constants
    // =========================================================

    @Nested
    @DisplayName("Extension constants")
    class ExtensionConstants {

        @Test
        @DisplayName("all extension constants are non-null and non-empty")
        void allExtensionsValid() {
            assertNotNull(AbstractBackupService.AGENT_EXT);
            assertNotNull(AbstractBackupService.WORKFLOW_EXT);
            assertNotNull(AbstractBackupService.DICTIONARY_EXT);
            assertNotNull(AbstractBackupService.BEHAVIOR_EXT);
            assertNotNull(AbstractBackupService.HTTPCALLS_EXT);
            assertNotNull(AbstractBackupService.LLM_EXT);
            assertNotNull(AbstractBackupService.PROPERTY_EXT);
            assertNotNull(AbstractBackupService.OUTPUT_EXT);
            assertNotNull(AbstractBackupService.MCPCALLS_EXT);
            assertNotNull(AbstractBackupService.RAG_EXT);
            assertNotNull(AbstractBackupService.SNIPPET_EXT);

            assertFalse(AbstractBackupService.AGENT_EXT.isEmpty());
            assertFalse(AbstractBackupService.SNIPPET_EXT.isEmpty());
        }
    }

    // =========================================================
    // createResourcePath — additional extensions
    // =========================================================

    @Nested
    @DisplayName("createResourcePath — additional extensions")
    class CreateResourcePathAdditional {

        @Test
        @DisplayName("creates path with output extension")
        void outputExtension() throws Exception {
            Method method = RestImportService.class.getDeclaredMethod(
                    "createResourcePath", Path.class, String.class, String.class);
            method.setAccessible(true);

            Path workflowPath = java.nio.file.Paths.get("tmp", "import");
            Path result = (Path) method.invoke(service, workflowPath, "out1", "output");
            assertTrue(result.toString().endsWith("out1.output.json"));
        }

        @Test
        @DisplayName("creates path with property extension")
        void propertyExtension() throws Exception {
            Method method = RestImportService.class.getDeclaredMethod(
                    "createResourcePath", Path.class, String.class, String.class);
            method.setAccessible(true);

            Path workflowPath = java.nio.file.Paths.get("data");
            Path result = (Path) method.invoke(service, workflowPath, "p1", "property");
            assertTrue(result.toString().endsWith("p1.property.json"));
        }

        @Test
        @DisplayName("creates path with httpcalls extension")
        void httpcallsExtension() throws Exception {
            Method method = RestImportService.class.getDeclaredMethod(
                    "createResourcePath", Path.class, String.class, String.class);
            method.setAccessible(true);

            Path workflowPath = java.nio.file.Paths.get("data");
            Path result = (Path) method.invoke(service, workflowPath, "h1", "httpcalls");
            assertTrue(result.toString().endsWith("h1.httpcalls.json"));
        }

        @Test
        @DisplayName("creates path with mcpcalls extension")
        void mcpcallsExtension() throws Exception {
            Method method = RestImportService.class.getDeclaredMethod(
                    "createResourcePath", Path.class, String.class, String.class);
            method.setAccessible(true);

            Path workflowPath = java.nio.file.Paths.get("data");
            Path result = (Path) method.invoke(service, workflowPath, "m1", "mcpcalls");
            assertTrue(result.toString().endsWith("m1.mcpcalls.json"));
        }

        @Test
        @DisplayName("creates path with snippet extension")
        void snippetExtension() throws Exception {
            Method method = RestImportService.class.getDeclaredMethod(
                    "createResourcePath", Path.class, String.class, String.class);
            method.setAccessible(true);

            Path workflowPath = java.nio.file.Paths.get("data");
            Path result = (Path) method.invoke(service, workflowPath, "s1", "snippet");
            assertTrue(result.toString().endsWith("s1.snippet.json"));
        }
    }

    // =========================================================
    // Helpers
    // =========================================================

    private static RestImportService createMinimalInstance() throws Exception {
        var constructor = RestImportService.class.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        return (RestImportService) constructor.newInstance(
                null, null, null, null, null, null, null, null, null);
    }
}
