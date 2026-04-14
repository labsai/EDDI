package ai.labs.eddi.backup.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link AbstractBackupService} utility methods: legacy URI
 * normalization and resource URI extraction.
 */
class AbstractBackupServiceTest {

    // ─── normalizeLegacyUris ─────────────────────────────────────

    @Nested
    @DisplayName("normalizeLegacyUris")
    class NormalizeLegacyUris {

        @Test
        @DisplayName("should rewrite regulardictionary URIs to dictionary")
        void rewriteRegularDictionary() {
            String input = "\"eddi://ai.labs.regulardictionary/regulardictionarystore/regulardictionaries/abc123?version=1\"";
            String expected = "\"eddi://ai.labs.dictionary/dictionarystore/dictionaries/abc123?version=1\"";
            assertEquals(expected, AbstractBackupService.normalizeLegacyUris(input));
        }

        @Test
        @DisplayName("should rewrite behavior URIs to rules")
        void rewriteBehavior() {
            String input = "\"eddi://ai.labs.behavior/behaviorstore/behaviorsets/def456?version=2\"";
            String expected = "\"eddi://ai.labs.rules/rulestore/rulesets/def456?version=2\"";
            assertEquals(expected, AbstractBackupService.normalizeLegacyUris(input));
        }

        @Test
        @DisplayName("should rewrite httpcalls URIs to apicalls")
        void rewriteHttpCalls() {
            String input = "\"eddi://ai.labs.httpcalls/httpcallsstore/httpcalls/ghi789?version=3\"";
            String expected = "\"eddi://ai.labs.apicalls/apicallstore/apicalls/ghi789?version=3\"";
            assertEquals(expected, AbstractBackupService.normalizeLegacyUris(input));
        }

        @Test
        @DisplayName("should rewrite langchain URIs to llm")
        void rewriteLangchain() {
            String input = "\"eddi://ai.labs.langchain/langchainstore/langchains/jkl012?version=1\"";
            String expected = "\"eddi://ai.labs.llm/llmstore/llms/jkl012?version=1\"";
            assertEquals(expected, AbstractBackupService.normalizeLegacyUris(input));
        }

        @Test
        @DisplayName("should rewrite package URIs to workflow")
        void rewritePackage() {
            String input = "\"eddi://ai.labs.package/packagestore/packages/mno345?version=4\"";
            String expected = "\"eddi://ai.labs.workflow/workflowstore/workflows/mno345?version=4\"";
            assertEquals(expected, AbstractBackupService.normalizeLegacyUris(input));
        }

        @Test
        @DisplayName("should rewrite bot URIs to agent")
        void rewriteBot() {
            String input = "\"eddi://ai.labs.bot/botstore/bots/pqr678?version=5\"";
            String expected = "\"eddi://ai.labs.agent/agentstore/agents/pqr678?version=5\"";
            assertEquals(expected, AbstractBackupService.normalizeLegacyUris(input));
        }

        @Test
        @DisplayName("should handle multiple legacy URIs in same string")
        void multipleUris() {
            String input = "{\"workflow\":\"eddi://ai.labs.package/packagestore/packages/a1?version=1\"," +
                    "\"dict\":\"eddi://ai.labs.regulardictionary/regulardictionarystore/regulardictionaries/b2?version=1\"}";
            String result = AbstractBackupService.normalizeLegacyUris(input);
            assertTrue(result.contains("eddi://ai.labs.workflow/workflowstore/workflows/a1"));
            assertTrue(result.contains("eddi://ai.labs.dictionary/dictionarystore/dictionaries/b2"));
        }

        @Test
        @DisplayName("should not modify already-v6 URIs (idempotent)")
        void idempotentV6Uris() {
            String v6 = "\"eddi://ai.labs.dictionary/dictionarystore/dictionaries/abc?version=1\"";
            assertEquals(v6, AbstractBackupService.normalizeLegacyUris(v6));
        }

        @Test
        @DisplayName("should not modify non-eddi strings")
        void nonEddiStrings() {
            String input = "https://example.com/api/v1/resource";
            assertEquals(input, AbstractBackupService.normalizeLegacyUris(input));
        }

        @Test
        @DisplayName("should handle null input")
        void nullInput() {
            assertNull(AbstractBackupService.normalizeLegacyUris(null));
        }

        @Test
        @DisplayName("should handle empty string")
        void emptyString() {
            assertEquals("", AbstractBackupService.normalizeLegacyUris(""));
        }
    }

    // ─── extractResourcesUris ────────────────────────────────────

    @Nested
    @DisplayName("extractResourcesUris")
    class ExtractResourcesUris {

        // Concrete subclass to test the package-private instance method
        private final AbstractBackupService service = new AbstractBackupService() {
        };

        @Test
        @DisplayName("should extract dictionary URIs from JSON config string")
        void extractDictionaryUris() throws Exception {
            String json = "{\"extensions\":[{\"uri\":\"eddi://ai.labs.dictionary/dictionarystore/dictionaries/abc123?version=1\"}]}";
            List<URI> uris = service.extractResourcesUris(json, AbstractBackupService.DICTIONARY_URI_PATTERN);
            assertEquals(1, uris.size());
            assertEquals("eddi://ai.labs.dictionary/dictionarystore/dictionaries/abc123?version=1", uris.get(0).toString());
        }

        @Test
        @DisplayName("should extract multiple LLM URIs")
        void extractMultipleLlmUris() throws Exception {
            String json = "{\"steps\":[" +
                    "{\"uri\":\"eddi://ai.labs.llm/llmstore/llms/aaa111?version=1\"}," +
                    "{\"uri\":\"eddi://ai.labs.llm/llmstore/llms/bbb222?version=2\"}" +
                    "]}";
            List<URI> uris = service.extractResourcesUris(json, AbstractBackupService.LANGCHAIN_URI_PATTERN);
            assertEquals(2, uris.size());
        }

        @Test
        @DisplayName("should return empty list when no URIs match")
        void noMatch() throws Exception {
            String json = "{\"nothing\":\"here\"}";
            List<URI> uris = service.extractResourcesUris(json, AbstractBackupService.RAG_URI_PATTERN);
            assertTrue(uris.isEmpty());
        }

        @Test
        @DisplayName("should handle empty string")
        void emptyString() throws Exception {
            List<URI> uris = service.extractResourcesUris("", AbstractBackupService.BEHAVIOR_URI_PATTERN);
            assertTrue(uris.isEmpty());
        }

        @Test
        @DisplayName("should extract all extension type URIs")
        void allExtensionTypes() throws Exception {
            // Test each pattern matches its own URI type
            assertUriExtraction(AbstractBackupService.BEHAVIOR_URI_PATTERN,
                    "eddi://ai.labs.rules/rulestore/rulesets/x1?version=1");
            assertUriExtraction(AbstractBackupService.HTTPCALLS_URI_PATTERN,
                    "eddi://ai.labs.apicalls/apicallstore/apicalls/x2?version=1");
            assertUriExtraction(AbstractBackupService.PROPERTY_URI_PATTERN,
                    "eddi://ai.labs.property/propertysetterstore/propertysetters/x3?version=1");
            assertUriExtraction(AbstractBackupService.OUTPUT_URI_PATTERN,
                    "eddi://ai.labs.output/outputstore/outputsets/x4?version=1");
            assertUriExtraction(AbstractBackupService.MCPCALLS_URI_PATTERN,
                    "eddi://ai.labs.mcpcalls/mcpcallsstore/mcpcalls/x5?version=1");
            assertUriExtraction(AbstractBackupService.RAG_URI_PATTERN,
                    "eddi://ai.labs.rag/ragstore/rags/x6?version=1");
        }

        private void assertUriExtraction(java.util.regex.Pattern pattern, String uri) throws Exception {
            String json = "{\"uri\":\"" + uri + "\"}";
            List<URI> result = service.extractResourcesUris(json, pattern);
            assertEquals(1, result.size(), "Pattern should match " + uri);
            assertEquals(uri, result.get(0).toString());
        }
    }
}
