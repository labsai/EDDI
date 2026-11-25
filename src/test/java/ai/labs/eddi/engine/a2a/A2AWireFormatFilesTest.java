/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.a2a;

import ai.labs.eddi.engine.a2a.A2AModels.Dialect;
import ai.labs.eddi.engine.a2a.A2AModels.FilePart;
import ai.labs.eddi.engine.a2a.A2AModels.InvalidA2ARequestException;
import ai.labs.eddi.engine.a2a.A2AModels.Part;
import ai.labs.eddi.engine.a2a.A2AWireFormat.SendRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static ai.labs.eddi.engine.a2a.A2AModels.ERROR_CONTENT_TYPE_NOT_SUPPORTED;
import static ai.labs.eddi.engine.a2a.A2AModels.ERROR_INVALID_PARAMS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * File parts on the wire: the A2A 1.0 flat shape ({@code raw}/{@code url},
 * {@code filename}, {@code mediaType}) and the 0.3 / pre-0.2 nested shape
 * ({@code kind}/{@code type: "file"}, {@code file: {name, mimeType, bytes |
 * uri}}), read in every dialect; and that with file parts off a request reads
 * exactly as it did before they existed.
 */
class A2AWireFormatFilesTest {

    private static Map<String, Object> params(List<Map<String, Object>> parts) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("messageId", "m1");
        message.put("parts", parts);
        return Map.of("message", message);
    }

    private static Map<String, Object> v1Raw(String raw, String filename, String mediaType) {
        Map<String, Object> part = new LinkedHashMap<>();
        part.put("raw", raw);
        part.put("filename", filename);
        part.put("mediaType", mediaType);
        return part;
    }

    private static Map<String, Object> v03File(String discriminatorKey, Map<String, Object> file) {
        Map<String, Object> part = new LinkedHashMap<>();
        part.put(discriminatorKey, "file");
        part.put("file", file);
        return part;
    }

    private static final Map<String, Object> TEXT = Map.of("text", "Summarize this");

    @Nested
    @DisplayName("files off — the default")
    class Off {

        @ParameterizedTest
        @EnumSource(Dialect.class)
        @DisplayName("a message of file parts only is refused with -32005 and the unchanged message")
        void fileOnlyRefused(Dialect dialect) {
            for (Map<String, Object> part : List.of(v1Raw("JVBERi0=", "a.pdf", "application/pdf"),
                    v03File("kind", Map.of("bytes", "JVBERi0=", "mimeType", "application/pdf")),
                    v03File("type", Map.of("uri", "https://example.com/a.pdf")))) {
                InvalidA2ARequestException e = assertThrows(InvalidA2ARequestException.class,
                        () -> A2AWireFormat.parseSend(params(List.of(part)), dialect));
                assertEquals(ERROR_CONTENT_TYPE_NOT_SUPPORTED, e.getCode());
                assertEquals("Only text and data parts are supported; file parts are not", e.getMessage());
                // The three-argument form with files off is the same call.
                assertThrows(InvalidA2ARequestException.class, () -> A2AWireFormat.parseSend(params(List.of(part)), dialect, false));
            }
        }

        @Test
        @DisplayName("text beside a file part runs as text, the file ignored, as before")
        void mixedReadsTheText() {
            SendRequest request = A2AWireFormat.parseSend(params(List.of(TEXT, v1Raw("JVBERi0=", "a.pdf", null))), Dialect.V1_0);
            assertEquals("Summarize this", request.text());
            assertTrue(request.files().isEmpty());
        }

        @Test
        @DisplayName("a malformed file part is not even looked at")
        void malformedIgnored() {
            SendRequest request = A2AWireFormat.parseSend(params(List.of(TEXT, Map.of("kind", "file"))), Dialect.V0_3);
            assertEquals("Summarize this", request.text());
        }
    }

    @Nested
    @DisplayName("files on")
    class On {

        @ParameterizedTest
        @EnumSource(Dialect.class)
        @DisplayName("the 1.0 flat shape: raw bytes and a url, with filename and mediaType")
        void v1Shape(Dialect dialect) {
            SendRequest request = A2AWireFormat.parseSend(params(List.of(TEXT, v1Raw("JVBERi0=", "a.pdf", "application/pdf"),
                    Map.of("url", "https://example.com/b.pdf", "filename", "b.pdf"))), dialect, true);

            assertEquals("Summarize this", request.text());
            assertEquals(List.of(new FilePart("a.pdf", "application/pdf", "JVBERi0=", null),
                    new FilePart("b.pdf", null, null, "https://example.com/b.pdf")), request.files());
        }

        @ParameterizedTest
        @EnumSource(Dialect.class)
        @DisplayName("the 0.3 nested shape (kind) and the pre-0.2 one (type): bytes and a uri, with name and mimeType")
        void nestedShape(Dialect dialect) {
            SendRequest request = A2AWireFormat.parseSend(params(List.of(
                    v03File("kind", Map.of("bytes", "JVBERi0=", "name", "a.pdf", "mimeType", "application/pdf")),
                    v03File("type", Map.of("uri", "https://example.com/b.pdf")))), dialect, true);

            assertEquals("", request.text(), "a message of files alone reads as empty text");
            assertEquals(List.of(new FilePart("a.pdf", "application/pdf", "JVBERi0=", null),
                    new FilePart(null, null, null, "https://example.com/b.pdf")), request.files());
        }

        @Test
        @DisplayName("a part with both bytes and a URI, or neither, is refused")
        void exactlyOneSource() {
            for (Map<String, Object> part : List.of(Map.<String, Object>of("raw", "JVBERi0=", "url", "https://example.com/a"),
                    v03File("kind", Map.of("name", "a.pdf")), Map.<String, Object>of("kind", "file"))) {
                InvalidA2ARequestException e = assertThrows(InvalidA2ARequestException.class,
                        () -> A2AWireFormat.parseSend(params(List.of(part)), Dialect.V1_0, true));
                assertEquals(ERROR_INVALID_PARAMS, e.getCode());
            }
        }

        @Test
        @DisplayName("bytes or a URI that are not strings, an over-long URI, or a name with control characters are refused")
        void malformedFields() {
            List<Map<String, Object>> bad = List.of(Map.of("raw", 42), Map.of("url", List.of("x")), Map.of("raw", "   "),
                    Map.of("url", "https://example.com/" + "a".repeat(A2AWireFormat.MAX_FILE_URI_LENGTH)),
                    Map.of("raw", "JVBERi0=", "filename", "a\u0000.pdf"), Map.of("raw", "JVBERi0=", "mediaType", 7),
                    Map.of("raw", "JVBERi0=", "filename", "a".repeat(A2AWireFormat.MAX_FILE_LABEL_LENGTH + 1)));
            for (Map<String, Object> part : bad) {
                assertThrows(InvalidA2ARequestException.class, () -> A2AWireFormat.parseSend(params(List.of(part)), Dialect.V1_0, true),
                        part.toString());
            }
        }

        @Test
        @DisplayName("a part of an unknown kind is still unreadable: alone it is refused with -32005")
        void unknownKindStillRefused() {
            InvalidA2ARequestException e = assertThrows(InvalidA2ARequestException.class,
                    () -> A2AWireFormat.parseSend(params(List.of(Map.of("kind", "video", "video", "x"))), Dialect.V0_3, true));
            assertEquals(ERROR_CONTENT_TYPE_NOT_SUPPORTED, e.getCode());
        }
    }

    @Nested
    @DisplayName("rendering a file part")
    class Rendering {

        private final Part part = Part.filePart(new FilePart("a.pdf", "application/pdf", "JVBERi0=", null));

        @Test
        @DisplayName("1.0: flat, no discriminator")
        void v1() {
            assertEquals(Map.of("raw", "JVBERi0=", "filename", "a.pdf", "mediaType", "application/pdf"),
                    A2AWireFormat.part(part, Dialect.V1_0));
        }

        @Test
        @DisplayName("0.3: kind file with a nested file object; pre-0.2 adds type")
        void nested() {
            Map<String, Object> file = Map.of("name", "a.pdf", "mimeType", "application/pdf", "bytes", "JVBERi0=");
            assertEquals(Map.of("kind", "file", "file", file), A2AWireFormat.part(part, Dialect.V0_3));
            assertEquals(Map.of("kind", "file", "type", "file", "file", file), A2AWireFormat.part(part, Dialect.LEGACY));
        }

        @Test
        @DisplayName("text and data parts render as before")
        void textUnchanged() {
            assertEquals(Map.of("text", "hi"), A2AWireFormat.part(Part.textPart("hi"), Dialect.V1_0));
        }
    }
}
