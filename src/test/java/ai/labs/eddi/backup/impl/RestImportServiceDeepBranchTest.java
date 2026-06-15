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
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Deep branch coverage tests for {@link RestImportService} and
 * {@link AbstractBackupService} targeting branches NOT covered by
 * {@code RestImportServiceHelpersTest}, {@code AbstractBackupServiceTest}, and
 * other existing test files.
 * <p>
 * Focus areas:
 * <ul>
 * <li>{@code createResourcePath} — different extensions</li>
 * <li>{@code buildOldAgentUri} — path extraction</li>
 * <li>{@code checkIfCreatedResponse} — 201 vs non-201</li>
 * <li>{@code toMap} — URI mapping helper</li>
 * <li>{@code normalizeVaultReferences} — additional edge cases (mixed, nested
 * JSON)</li>
 * <li>{@code findSnippetsDir} — deeply nested agent/version/ path</li>
 * <li>Legacy vs v6 pattern cross-matching</li>
 * </ul>
 */
@DisplayName("RestImportService — Deep Branch Coverage")
class RestImportServiceDeepBranchTest {

    private RestImportService service;

    @BeforeEach
    void setUp() throws Exception {
        service = createMinimalInstance();
    }

    // =========================================================
    // createResourcePath — via reflection
    // =========================================================

    @Nested
    @DisplayName("createResourcePath")
    class CreateResourcePath {

        @Test
        @DisplayName("creates path with behavior extension")
        void behaviorExtension() throws Exception {
            Method method = RestImportService.class.getDeclaredMethod(
                    "createResourcePath", Path.class, String.class, String.class);
            method.setAccessible(true);

            Path workflowPath = Paths.get("tmp", "import", "wf1", "1");
            Path result = (Path) method.invoke(service, workflowPath, "res1", "behavior");

            assertTrue(result.toString().endsWith("res1.behavior.json"));
        }

        @Test
        @DisplayName("creates path with langchain extension")
        void langchainExtension() throws Exception {
            Method method = RestImportService.class.getDeclaredMethod(
                    "createResourcePath", Path.class, String.class, String.class);
            method.setAccessible(true);

            Path workflowPath = Paths.get("tmp", "import", "wf1", "1");
            Path result = (Path) method.invoke(service, workflowPath, "abc", "langchain");

            assertTrue(result.toString().endsWith("abc.langchain.json"));
        }

        @Test
        @DisplayName("creates path with rag extension")
        void ragExtension() throws Exception {
            Method method = RestImportService.class.getDeclaredMethod(
                    "createResourcePath", Path.class, String.class, String.class);
            method.setAccessible(true);

            Path workflowPath = Paths.get("data");
            Path result = (Path) method.invoke(service, workflowPath, "r1", "rag");

            assertTrue(result.toString().endsWith("r1.rag.json"));
        }
    }

    // =========================================================
    // buildOldAgentUri — via reflection
    // =========================================================

    @Nested
    @DisplayName("buildOldAgentUri")
    class BuildOldAgentUri {

        @Test
        @DisplayName("extracts agent ID from path and builds URI")
        void buildsCorrectUri() throws Exception {
            Method method = RestImportService.class.getDeclaredMethod(
                    "buildOldAgentUri", Path.class);
            method.setAccessible(true);

            Path agentPath = Paths.get("tmp", "import", "abc123.agent.json");
            URI result = (URI) method.invoke(service, agentPath);

            assertTrue(result.toString().contains("abc123"));
            assertTrue(result.toString().contains("version=1"));
        }

        @Test
        @DisplayName("handles deeply nested path")
        void deeplyNestedPath() throws Exception {
            Method method = RestImportService.class.getDeclaredMethod(
                    "buildOldAgentUri", Path.class);
            method.setAccessible(true);

            Path agentPath = Paths.get("a", "b", "c", "def456.agent.json");
            URI result = (URI) method.invoke(service, agentPath);

            assertTrue(result.toString().contains("def456"));
        }
    }

    // =========================================================
    // checkIfCreatedResponse — via reflection
    // =========================================================

    @Nested
    @DisplayName("checkIfCreatedResponse")
    class CheckIfCreatedResponse {

        @Test
        @DisplayName("status 201 passes silently")
        void status201Passes() throws Exception {
            Method method = RestImportService.class.getDeclaredMethod(
                    "checkIfCreatedResponse", jakarta.ws.rs.core.Response.class);
            method.setAccessible(true);

            var response = jakarta.ws.rs.core.Response.status(201).build();
            method.invoke(service, response); // Should not throw
        }

        @Test
        @DisplayName("non-201 status logs error but does not throw")
        void nonCreatedStatus() throws Exception {
            Method method = RestImportService.class.getDeclaredMethod(
                    "checkIfCreatedResponse", jakarta.ws.rs.core.Response.class);
            method.setAccessible(true);

            var response = jakarta.ws.rs.core.Response.status(400).build();
            method.invoke(service, response); // Should not throw, just logs
        }

        @Test
        @DisplayName("status 500 logs error but does not throw")
        void serverError() throws Exception {
            Method method = RestImportService.class.getDeclaredMethod(
                    "checkIfCreatedResponse", jakarta.ws.rs.core.Response.class);
            method.setAccessible(true);

            var response = jakarta.ws.rs.core.Response.status(500).build();
            method.invoke(service, response); // Should not throw
        }
    }

    // =========================================================
    // toMap — via reflection
    // =========================================================

    @Nested
    @DisplayName("toMap")
    class ToMap {

        @SuppressWarnings("unchecked")
        @Test
        @DisplayName("creates correct mapping from old to new URIs")
        void createsMapping() throws Exception {
            Method method = RestImportService.class.getDeclaredMethod(
                    "toMap", List.class, List.class);
            method.setAccessible(true);

            List<URI> oldUris = List.of(
                    URI.create("eddi://old/path/a"),
                    URI.create("eddi://old/path/b"));
            List<URI> newUris = List.of(
                    URI.create("eddi://new/path/x"),
                    URI.create("eddi://new/path/y"));

            var result = (Map<String, String>) method.invoke(service, oldUris, newUris);
            assertEquals(2, result.size());
            assertEquals("eddi://new/path/x", result.get("eddi://old/path/a"));
            assertEquals("eddi://new/path/y", result.get("eddi://old/path/b"));
        }

        @SuppressWarnings("unchecked")
        @Test
        @DisplayName("empty lists return empty map")
        void emptyLists() throws Exception {
            Method method = RestImportService.class.getDeclaredMethod(
                    "toMap", List.class, List.class);
            method.setAccessible(true);

            var result = (Map<String, String>) method.invoke(service,
                    List.of(), List.of());
            assertTrue(result.isEmpty());
        }

        @SuppressWarnings("unchecked")
        @Test
        @DisplayName("single entry mapping")
        void singleEntry() throws Exception {
            Method method = RestImportService.class.getDeclaredMethod(
                    "toMap", List.class, List.class);
            method.setAccessible(true);

            var result = (Map<String, String>) method.invoke(service,
                    List.of(URI.create("eddi://a")),
                    List.of(URI.create("eddi://b")));
            assertEquals(1, result.size());
            assertEquals("eddi://b", result.get("eddi://a"));
        }
    }

    // =========================================================
    // findSnippetsDir — deeply nested agent/version/ path
    // (Supplements existing test which only covers root and agent/)
    // =========================================================

    @Nested
    @DisplayName("findSnippetsDir — deep nesting")
    class FindSnippetsDirDeep {

        @Test
        @DisplayName("finds snippets in agent/version/ subdirectory")
        void deepNested() throws Exception {
            Path tmpDir = Files.createTempDirectory("eddi-deep-snippet");
            try {
                Path snippetsDir = tmpDir.resolve("agent1").resolve("1").resolve("snippets");
                Files.createDirectories(snippetsDir);

                Path result = invokeFindSnippetsDir(tmpDir);
                assertNotNull(result);
                assertEquals(snippetsDir, result);
            } finally {
                deleteRecursive(tmpDir);
            }
        }

        @Test
        @DisplayName("returns null for non-existent directory")
        void nonExistentDir() throws Exception {
            Path nonExistent = Paths.get("nonexistent-path-" + System.nanoTime());
            Path result = invokeFindSnippetsDir(nonExistent);
            assertNull(result);
        }

        private Path invokeFindSnippetsDir(Path targetDirPath) throws Exception {
            Method method = RestImportService.class.getDeclaredMethod(
                    "findSnippetsDir", Path.class);
            method.setAccessible(true);
            return (Path) method.invoke(service, targetDirPath);
        }

        private void deleteRecursive(Path path) {
            try {
                Files.walk(path)
                        .sorted(Comparator.reverseOrder())
                        .forEach(p -> {
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
    // normalizeVaultReferences — supplementary edge cases
    // =========================================================

    @Nested
    @DisplayName("normalizeVaultReferences — supplementary")
    class NormalizeVaultSupplementary {

        @Test
        @DisplayName("multiple eddivault references all get rewritten")
        void multipleReferences() {
            String input = "${eddivault:key1} and ${eddivault:key2}";
            String result = AbstractBackupService.normalizeVaultReferences(input);
            assertEquals("${vault:key1} and ${vault:key2}", result);
        }

        @Test
        @DisplayName("mixed vault and eddivault — only eddivault gets rewritten")
        void mixedReferences() {
            String input = "${vault:good} ${eddivault:legacy}";
            String result = AbstractBackupService.normalizeVaultReferences(input);
            assertEquals("${vault:good} ${vault:legacy}", result);
        }

        @Test
        @DisplayName("eddivault nested in JSON")
        void nestedInJson() {
            String input = "{\"key\":\"${eddivault:api-key}\",\"other\":\"value\"}";
            String result = AbstractBackupService.normalizeVaultReferences(input);
            assertEquals("{\"key\":\"${vault:api-key}\",\"other\":\"value\"}", result);
        }
    }

    // =========================================================
    // Legacy vs v6 pattern cross-matching
    // =========================================================

    @Nested
    @DisplayName("Legacy vs v6 pattern cross-matching")
    class CrossMatchPatterns {

        @Test
        @DisplayName("legacy patterns don't match v6 URIs")
        void legacyDontMatchV6() throws Exception {
            String v6Dict = "\"eddi://ai.labs.dictionary/dictionarystore/dictionaries/x?version=1\"";
            assertTrue(extractUris(v6Dict, AbstractBackupService.LEGACY_DICTIONARY_URI_PATTERN).isEmpty());

            String v6Behavior = "\"eddi://ai.labs.rules/rulestore/rulesets/x?version=1\"";
            assertTrue(extractUris(v6Behavior, AbstractBackupService.LEGACY_BEHAVIOR_URI_PATTERN).isEmpty());

            String v6HttpCalls = "\"eddi://ai.labs.apicalls/apicallstore/apicalls/x?version=1\"";
            assertTrue(extractUris(v6HttpCalls, AbstractBackupService.LEGACY_HTTPCALLS_URI_PATTERN).isEmpty());

            String v6Llm = "\"eddi://ai.labs.llm/llmstore/llms/x?version=1\"";
            assertTrue(extractUris(v6Llm, AbstractBackupService.LEGACY_LANGCHAIN_URI_PATTERN).isEmpty());
        }

        @Test
        @DisplayName("v6 patterns don't match legacy URIs")
        void v6DontMatchLegacy() throws Exception {
            String legacyDict = "\"eddi://ai.labs.regulardictionary/regulardictionarystore/regulardictionaries/x?version=1\"";
            assertTrue(extractUris(legacyDict, AbstractBackupService.DICTIONARY_URI_PATTERN).isEmpty());

            String legacyBehavior = "\"eddi://ai.labs.behavior/behaviorstore/behaviorsets/x?version=1\"";
            assertTrue(extractUris(legacyBehavior, AbstractBackupService.BEHAVIOR_URI_PATTERN).isEmpty());

            String legacyHttp = "\"eddi://ai.labs.httpcalls/httpcallsstore/httpcalls/x?version=1\"";
            assertTrue(extractUris(legacyHttp, AbstractBackupService.HTTPCALLS_URI_PATTERN).isEmpty());

            String legacyLlm = "\"eddi://ai.labs.langchain/langchainstore/langchains/x?version=1\"";
            assertTrue(extractUris(legacyLlm, AbstractBackupService.LANGCHAIN_URI_PATTERN).isEmpty());
        }

        @SuppressWarnings("unchecked")
        private List<URI> extractUris(String input, java.util.regex.Pattern pattern) throws Exception {
            Method method = AbstractBackupService.class.getDeclaredMethod(
                    "extractResourcesUris", String.class, java.util.regex.Pattern.class);
            method.setAccessible(true);
            return (List<URI>) method.invoke(service, input, pattern);
        }
    }

    // =========================================================
    // readFile — via reflection (edge case)
    // =========================================================

    @Nested
    @DisplayName("readFile")
    class ReadFile {

        @Test
        @DisplayName("reads file content correctly")
        void readsContent() throws Exception {
            Path tmpFile = Files.createTempFile("eddi-read-test", ".json");
            try {
                Files.writeString(tmpFile, "{\"key\":\"value\"}");

                Method method = RestImportService.class.getDeclaredMethod(
                        "readFile", Path.class);
                method.setAccessible(true);

                String result = (String) method.invoke(service, tmpFile);
                assertEquals("{\"key\":\"value\"}", result);
            } finally {
                Files.deleteIfExists(tmpFile);
            }
        }

        @Test
        @DisplayName("reads multi-line file content (lines concatenated without separator)")
        void readsMultiLine() throws Exception {
            Path tmpFile = Files.createTempFile("eddi-read-multiline", ".json");
            try {
                Files.writeString(tmpFile, "line1\nline2\nline3");

                Method method = RestImportService.class.getDeclaredMethod(
                        "readFile", Path.class);
                method.setAccessible(true);

                String result = (String) method.invoke(service, tmpFile);
                assertEquals("line1line2line3", result);
            } finally {
                Files.deleteIfExists(tmpFile);
            }
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
