package ai.labs.eddi.backup.impl;

import org.junit.jupiter.api.*;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link RestImportService} private helper methods via
 * reflection. These are pure logic methods that don't require CDI or MongoDB.
 *
 * @since 6.0.0
 */
@DisplayName("RestImportService Helpers")
class RestImportServiceHelpersTest {

    // Use reflection to invoke private methods on RestImportService
    // Note: we can't construct a full RestImportService (9 CDI deps) but we can
    // test
    // static-like helpers by invoking them on a null-arg-constructed instance or
    // via
    // the Class directly.

    // ─── parseSelectedResources ─────────────────────────────────

    @Nested
    @DisplayName("parseSelectedResources")
    class ParseSelectedResources {

        @Test
        @DisplayName("null input — returns null")
        void nullInput() throws Exception {
            Set<String> result = invokeParseSelectedResources(null);
            assertNull(result);
        }

        @Test
        @DisplayName("empty string — returns null")
        void emptyString() throws Exception {
            Set<String> result = invokeParseSelectedResources("");
            assertNull(result);
        }

        @Test
        @DisplayName("single ID — returns set of one")
        void singleId() throws Exception {
            Set<String> result = invokeParseSelectedResources("abc123");
            assertNotNull(result);
            assertEquals(1, result.size());
            assertTrue(result.contains("abc123"));
        }

        @Test
        @DisplayName("comma-separated IDs — returns set of all")
        void multipleIds() throws Exception {
            Set<String> result = invokeParseSelectedResources("abc,def,ghi");
            assertNotNull(result);
            assertEquals(3, result.size());
            assertTrue(result.containsAll(Set.of("abc", "def", "ghi")));
        }

        @Test
        @DisplayName("whitespace around IDs — trimmed")
        void whitespace() throws Exception {
            Set<String> result = invokeParseSelectedResources(" abc , def , ghi ");
            assertNotNull(result);
            assertEquals(3, result.size());
            assertTrue(result.containsAll(Set.of("abc", "def", "ghi")));
        }

        @Test
        @DisplayName("trailing comma — empty entries filtered out")
        void trailingComma() throws Exception {
            Set<String> result = invokeParseSelectedResources("abc,def,");
            assertNotNull(result);
            assertEquals(2, result.size());
        }

        @SuppressWarnings("unchecked")
        private Set<String> invokeParseSelectedResources(String input) throws Exception {
            Method method = RestImportService.class.getDeclaredMethod("parseSelectedResources", String.class);
            method.setAccessible(true);
            // Create minimal instance — constructor will NPE but method is instance, not
            // static
            // We need a real instance with all fields null
            return (Set<String>) method.invoke(createMinimalInstance(), input);
        }
    }

    // ─── isSelected ─────────────────────────────────────────────

    @Nested
    @DisplayName("isSelected")
    class IsSelected {

        @Test
        @DisplayName("null selectedSet — always returns true (all selected)")
        void nullSet() throws Exception {
            assertTrue(invokeIsSelected(null, "any-id"));
        }

        @Test
        @DisplayName("ID in set — returns true")
        void idInSet() throws Exception {
            assertTrue(invokeIsSelected(Set.of("abc", "def"), "abc"));
        }

        @Test
        @DisplayName("ID not in set — returns false")
        void idNotInSet() throws Exception {
            assertFalse(invokeIsSelected(Set.of("abc", "def"), "ghi"));
        }

        private boolean invokeIsSelected(Set<String> selectedSet, String originId) throws Exception {
            Method method = RestImportService.class.getDeclaredMethod("isSelected", Set.class, String.class);
            method.setAccessible(true);
            return (boolean) method.invoke(createMinimalInstance(), selectedSet, originId);
        }
    }

    // ─── parseWorkflowOrder ─────────────────────────────────────

    @Nested
    @DisplayName("parseWorkflowOrder")
    class ParseWorkflowOrder {

        @Test
        @DisplayName("null input — returns null")
        void nullInput() throws Exception {
            assertNull(invokeParseWorkflowOrder(null));
        }

        @Test
        @DisplayName("empty string — returns null")
        void emptyString() throws Exception {
            assertNull(invokeParseWorkflowOrder(""));
        }

        @Test
        @DisplayName("single ID — returns list of one")
        void singleId() throws Exception {
            List<String> result = invokeParseWorkflowOrder("wf1");
            assertNotNull(result);
            assertEquals(1, result.size());
            assertEquals("wf1", result.getFirst());
        }

        @Test
        @DisplayName("comma-separated IDs — returns ordered list")
        void multipleIds() throws Exception {
            List<String> result = invokeParseWorkflowOrder("wf3,wf1,wf2");
            assertNotNull(result);
            assertEquals(3, result.size());
            assertEquals("wf3", result.get(0));
            assertEquals("wf1", result.get(1));
            assertEquals("wf2", result.get(2));
        }

        @Test
        @DisplayName("whitespace around IDs — trimmed")
        void whitespace() throws Exception {
            List<String> result = invokeParseWorkflowOrder(" wf1 , wf2 ");
            assertNotNull(result);
            assertEquals(2, result.size());
            assertEquals("wf1", result.get(0));
            assertEquals("wf2", result.get(1));
        }

        @SuppressWarnings("unchecked")
        private List<String> invokeParseWorkflowOrder(String input) throws Exception {
            Method method = RestImportService.class.getDeclaredMethod("parseWorkflowOrder", String.class);
            method.setAccessible(true);
            return (List<String>) method.invoke(createMinimalInstance(), input);
        }
    }

    // ─── extractIdFromAgentFilename ──────────────────────────────

    @Nested
    @DisplayName("extractIdFromAgentFilename")
    class ExtractIdFromAgentFilename {

        @Test
        @DisplayName("standard agent filename — extracts ID")
        void standardFilename() throws Exception {
            Path agentPath = Path.of("tmp", "import", "abc123def.agent.json");
            String result = invokeExtractIdFromAgentFilename(agentPath);
            assertEquals("abc123def", result);
        }

        @Test
        @DisplayName("ObjectId-style filename — extracts full ID")
        void objectIdFilename() throws Exception {
            Path agentPath = Path.of("665a1b2c3d4e5f6a7b8c9d0e.agent.json");
            String result = invokeExtractIdFromAgentFilename(agentPath);
            assertEquals("665a1b2c3d4e5f6a7b8c9d0e", result);
        }

        private String invokeExtractIdFromAgentFilename(Path agentPath) throws Exception {
            Method method = RestImportService.class.getDeclaredMethod("extractIdFromAgentFilename", Path.class);
            method.setAccessible(true);
            return (String) method.invoke(createMinimalInstance(), agentPath);
        }
    }

    // ─── findSnippetsDir ────────────────────────────────────────

    @Nested
    @DisplayName("findSnippetsDir")
    class FindSnippetsDir {

        @Test
        @DisplayName("snippets directory at root — found")
        void snippetsAtRoot() throws Exception {
            Path tmpDir = Files.createTempDirectory("eddi-test-snippets");
            try {
                Path snippetsDir = tmpDir.resolve("snippets");
                Files.createDirectory(snippetsDir);

                Path result = invokeFindSnippetsDir(tmpDir);
                assertNotNull(result);
                assertEquals(snippetsDir, result);
            } finally {
                deleteRecursive(tmpDir);
            }
        }

        @Test
        @DisplayName("snippets directory nested in agent subdir — found")
        void snippetsNested() throws Exception {
            Path tmpDir = Files.createTempDirectory("eddi-test-snippets-nested");
            try {
                Path agentDir = tmpDir.resolve("agentId123");
                Files.createDirectories(agentDir);
                Path snippetsDir = agentDir.resolve("snippets");
                Files.createDirectory(snippetsDir);

                Path result = invokeFindSnippetsDir(tmpDir);
                assertNotNull(result);
                assertEquals(snippetsDir, result);
            } finally {
                deleteRecursive(tmpDir);
            }
        }

        @Test
        @DisplayName("no snippets directory — returns null")
        void noSnippetsDir() throws Exception {
            Path tmpDir = Files.createTempDirectory("eddi-test-no-snippets");
            try {
                Path result = invokeFindSnippetsDir(tmpDir);
                assertNull(result);
            } finally {
                deleteRecursive(tmpDir);
            }
        }

        private Path invokeFindSnippetsDir(Path targetDirPath) throws Exception {
            Method method = RestImportService.class.getDeclaredMethod("findSnippetsDir", Path.class);
            method.setAccessible(true);
            return (Path) method.invoke(createMinimalInstance(), targetDirPath);
        }

        private void deleteRecursive(Path path) {
            try {
                Files.walk(path)
                        .sorted(java.util.Comparator.reverseOrder())
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

    // ─── isDevMode ──────────────────────────────────────────────

    @Nested
    @DisplayName("isDevMode")
    class IsDevMode {

        @Test
        @DisplayName("returns false for prod profile")
        void prodProfile() throws Exception {
            String original = System.getProperty("quarkus.profile");
            try {
                System.setProperty("quarkus.profile", "prod");
                boolean result = invokeIsDevMode();
                assertFalse(result);
            } finally {
                if (original != null) {
                    System.setProperty("quarkus.profile", original);
                } else {
                    System.clearProperty("quarkus.profile");
                }
            }
        }

        @Test
        @DisplayName("returns true for dev profile")
        void devProfile() throws Exception {
            String original = System.getProperty("quarkus.profile");
            try {
                System.setProperty("quarkus.profile", "dev");
                boolean result = invokeIsDevMode();
                assertTrue(result);
            } finally {
                if (original != null) {
                    System.setProperty("quarkus.profile", original);
                } else {
                    System.clearProperty("quarkus.profile");
                }
            }
        }

        @Test
        @DisplayName("returns true for test profile")
        void testProfile() throws Exception {
            String original = System.getProperty("quarkus.profile");
            try {
                System.setProperty("quarkus.profile", "test");
                boolean result = invokeIsDevMode();
                assertTrue(result);
            } finally {
                if (original != null) {
                    System.setProperty("quarkus.profile", original);
                } else {
                    System.clearProperty("quarkus.profile");
                }
            }
        }

        private boolean invokeIsDevMode() throws Exception {
            Method method = RestImportService.class.getDeclaredMethod("isDevMode");
            method.setAccessible(true);
            return (boolean) method.invoke(createMinimalInstance());
        }
    }

    // ─── Helpers ────────────────────────────────────────────────

    /**
     * Creates a minimal RestImportService with all CDI dependencies set to null.
     * Only usable for testing pure-logic helper methods that don't touch injected
     * fields.
     */
    private static RestImportService createMinimalInstance() throws Exception {
        var constructor = RestImportService.class.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        // All 9 parameters are null — the helpers we test don't use them
        return (RestImportService) constructor.newInstance(
                null, null, null, null, null, null, null, null, null);
    }
}
