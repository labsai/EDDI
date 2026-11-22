/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mcp;

import ai.labs.eddi.engine.api.IRestGroupConversation.AttachmentRef;
import ai.labs.eddi.engine.memory.AttachmentContextExtractor;
import ai.labs.eddi.engine.memory.model.Attachment;
import ai.labs.eddi.engine.model.Context;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpAttachmentArgumentsTest {

    private static final long MAX = 1_000;

    private static McpAttachmentArguments.Parsed oneToOne(String json) {
        return McpAttachmentArguments.parse(json, true, MAX);
    }

    @Nested
    class Parse {

        @Test
        void absentArgumentMeansNoFiles() {
            for (String json : new String[]{null, "", "   "}) {
                var parsed = oneToOne(json);
                assertFalse(parsed.isRefused());
                assertTrue(parsed.isEmpty());
            }
        }

        @Test
        void emptyArrayMeansNoFiles() {
            var parsed = oneToOne("[]");
            assertFalse(parsed.isRefused());
            assertTrue(parsed.isEmpty());
        }

        @Test
        void invalidJsonIsRefusedWithoutEchoingThePayload() {
            var parsed = oneToOne("[{\"data\":\"secret-payload-xyz\"");
            assertTrue(parsed.isRefused());
            assertFalse(parsed.error().contains("secret-payload-xyz"), parsed.error());
        }

        @Test
        void anObjectInsteadOfAnArrayIsRefused() {
            var parsed = oneToOne("{\"storageRef\":\"abc\"}");
            assertTrue(parsed.isRefused());
            assertTrue(parsed.error().contains("JSON array"), parsed.error());
        }

        @Test
        void aNonObjectEntryIsRefused() {
            var parsed = oneToOne("[\"abc\"]");
            assertTrue(parsed.isRefused());
            assertTrue(parsed.error().contains("attachments[0] must be a JSON object"), parsed.error());
        }

        @Test
        void moreEntriesThanARestRequestMayCarryAreRefused() {
            var json = new StringBuilder("[");
            for (int i = 0; i <= McpAttachmentArguments.MAX_ENTRIES; i++) {
                json.append(i == 0 ? "" : ",").append("{\"url\":\"https://example.com/").append(i).append("\",\"mimeType\":\"image/png\"}");
            }
            var tooMany = oneToOne(json.append(']').toString());
            assertTrue(tooMany.isRefused());
            assertTrue(tooMany.error().contains(String.valueOf(McpAttachmentArguments.MAX_ENTRIES)), tooMany.error());

            var json2 = new StringBuilder("[");
            for (int i = 0; i < McpAttachmentArguments.MAX_ENTRIES; i++) {
                json2.append(i == 0 ? "" : ",").append("{\"url\":\"https://example.com/").append(i).append("\",\"mimeType\":\"image/png\"}");
            }
            assertFalse(oneToOne(json2.append(']').toString()).isRefused());
        }

        @Test
        void anUnknownFieldIsRefusedWithoutNamingIt() {
            var parsed = oneToOne("[{\"url\":\"https://example.com/a.png\",\"mimeType\":\"image/png\",\"sneakyKey\":\"x\"}]");
            assertTrue(parsed.isRefused());
            assertFalse(parsed.error().contains("sneakyKey"), parsed.error());
            assertTrue(parsed.error().contains("attachments[0]"), parsed.error());
        }

        @Test
        void aNonStringValueIsRefused() {
            var parsed = oneToOne("[{\"url\":\"https://example.com/a.png\",\"mimeType\":7}]");
            assertTrue(parsed.isRefused());
            assertTrue(parsed.error().contains("mimeType"), parsed.error());
        }

        @Test
        void exactlyOneSourceIsRequired() {
            assertTrue(oneToOne("[{\"mimeType\":\"image/png\"}]").isRefused());
            assertTrue(oneToOne("[{\"url\":\"https://example.com/a.png\",\"data\":\"AAAA\",\"mimeType\":\"image/png\"}]").isRefused());
            assertTrue(oneToOne("[{\"storageRef\":\"abc\",\"data\":\"AAAA\",\"mimeType\":\"image/png\"}]").isRefused());
        }

        @Test
        void urlAndDataNeedAMimeTypeButAStorageRefDoesNot() {
            assertTrue(oneToOne("[{\"url\":\"https://example.com/a.png\"}]").isRefused());
            assertTrue(oneToOne("[{\"data\":\"AAAA\"}]").isRefused());
            assertFalse(oneToOne("[{\"storageRef\":\"abc\"}]").isRefused());
        }

        @Test
        void aStorageRefIsRefusedWhereNoTrustedSourceExists() {
            var parsed = McpAttachmentArguments.parse("[{\"storageRef\":\"0123456789abcdef01234567\"}]", false, MAX);
            assertTrue(parsed.isRefused());
            assertTrue(parsed.error().contains("storageRef"), parsed.error());
            assertFalse(parsed.error().contains("0123456789abcdef01234567"), parsed.error());
        }

        @Test
        void anInlineFileAboveTheFileLimitIsRefused() {
            // 1,336 base64 characters decode to 1,002 bytes: above MAX.
            var overLimit = oneToOne("[{\"data\":\"" + "A".repeat(1_336) + "\",\"mimeType\":\"application/pdf\"}]");
            assertTrue(overLimit.isRefused());
            assertTrue(overLimit.error().contains("1002") && overLimit.error().contains("1000"), overLimit.error());

            // 1,332 characters decode to 999 bytes: within it.
            assertFalse(oneToOne("[{\"data\":\"" + "A".repeat(1_332) + "\",\"mimeType\":\"application/pdf\"}]").isRefused());
            // A non-positive limit disables the check.
            assertFalse(McpAttachmentArguments.parse("[{\"data\":\"" + "A".repeat(1_336) + "\",\"mimeType\":\"application/pdf\"}]", true, 0)
                    .isRefused());
        }

        @Test
        void fieldsLongerThanRestAllowsAreRefused() {
            assertTrue(oneToOne("[{\"url\":\"https://example.com/a.png\",\"mimeType\":\"" + "x".repeat(256) + "\"}]").isRefused());
            assertTrue(oneToOne("[{\"url\":\"https://example.com/a.png\",\"mimeType\":\"image/png\",\"fileName\":\"" + "x".repeat(256) + "\"}]")
                    .isRefused());
            assertTrue(oneToOne("[{\"url\":\"https://example.com/" + "x".repeat(2_048) + "\",\"mimeType\":\"image/png\"}]").isRefused());
            assertTrue(oneToOne("[{\"storageRef\":\"" + "x".repeat(257) + "\"}]").isRefused());
        }

        @Test
        void nullAndBlankFieldsCountAsAbsent() {
            var parsed = oneToOne("[{\"storageRef\":\"abc\",\"url\":null,\"data\":\"  \",\"fileName\":\"a.pdf\"}]");
            assertFalse(parsed.isRefused(), parsed.error());
            assertEquals(Map.of("storageRef", "abc", "fileName", "a.pdf"), parsed.entries().getFirst());
        }
    }

    @Nested
    class Conversion {

        @Test
        void contextsHaveTheShapeTheExtractorReads() {
            var parsed = oneToOne("[{\"storageRef\":\"ref-1\",\"fileName\":\"a.pdf\"},"
                    + "{\"url\":\"https://example.com/b.png\",\"mimeType\":\"image/png\"},"
                    + "{\"data\":\"AAAA\",\"mimeType\":\"application/pdf\",\"fileName\":\"c.pdf\"}]");

            Map<String, Context> contexts = McpAttachmentArguments.toContexts(parsed.entries());

            assertEquals(List.of("attachment_0", "attachment_1", "attachment_2"), new ArrayList<>(contexts.keySet()));
            contexts.values().forEach(c -> assertEquals(Context.ContextType.object, c.getType()));

            List<Attachment> extracted = AttachmentContextExtractor.extractAttachments(contexts);
            assertEquals(3, extracted.size());
            var byFile = new LinkedHashMap<Attachment.ContentSource, Attachment>();
            extracted.forEach(a -> byFile.put(a.getContentSource(), a));
            assertEquals("ref-1", byFile.get(Attachment.ContentSource.STORED).getStorageRef());
            assertEquals("https://example.com/b.png", byFile.get(Attachment.ContentSource.URL).getUrl());
            assertEquals("AAAA", byFile.get(Attachment.ContentSource.BASE64).getBase64Data());
            assertEquals("c.pdf", byFile.get(Attachment.ContentSource.BASE64).getFileName());
        }

        @Test
        void refsCarryTheRestRequestFields() {
            var parsed = McpAttachmentArguments.parse("[{\"data\":\"AAAA\",\"mimeType\":\"application/pdf\",\"fileName\":\"c.pdf\"},"
                    + "{\"url\":\"https://example.com/b.png\",\"mimeType\":\"image/png\"}]", false, MAX);

            assertEquals(List.of(new AttachmentRef("application/pdf", "AAAA", null, "c.pdf"),
                    new AttachmentRef("image/png", null, "https://example.com/b.png", null)), McpAttachmentArguments.toRefs(parsed.entries()));
        }
    }
}
