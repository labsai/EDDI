/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.openai;

import ai.labs.eddi.integrations.openai.model.TokenUsage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link OpenAiSseWriter} — the exact bytes on the wire.
 * <p>
 * Frame shape is asserted rather than eyeballed because a client parses it
 * literally: a missing blank line or a stray {@code data:} makes the whole
 * stream unreadable, and the failure surfaces as "the model said nothing".
 */
class OpenAiSseWriterTest {

    private static final String ID = "chatcmpl-eddi-test";
    private static final String MODEL = "support-a3f9c1";
    private static final long CREATED = 1_753_500_000L;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private ByteArrayOutputStream out;

    @BeforeEach
    void setUp() {
        out = new ByteArrayOutputStream();
    }

    private OpenAiSseWriter writer() {
        return new OpenAiSseWriter(out, objectMapper, ID, MODEL, CREATED, false);
    }

    private String body() {
        return out.toString(StandardCharsets.UTF_8);
    }

    /** The JSON payloads of every {@code data:} frame except {@code [DONE]}. */
    private List<JsonNode> frames() throws Exception {
        List<JsonNode> frames = new ArrayList<>();
        for (String frame : body().split("\n\n")) {
            String trimmed = frame.strip();
            if (trimmed.isEmpty() || trimmed.equals("data: [DONE]")) {
                continue;
            }
            assertTrue(trimmed.startsWith("data: "), "every frame must start with 'data: ', got: " + trimmed);
            frames.add(objectMapper.readTree(trimmed.substring("data: ".length())));
        }
        return frames;
    }

    // ─── frame shape ───

    @Test
    void contentEmitsRoleFrameFirst() throws Exception {
        var writer = writer();
        writer.content("Hello");

        List<JsonNode> frames = frames();
        assertEquals(2, frames.size());
        assertEquals("assistant", frames.get(0).at("/choices/0/delta/role").asText());
        assertTrue(frames.get(0).at("/choices/0/delta/content").isMissingNode(),
                "the opening frame must carry no content");
        assertEquals("Hello", frames.get(1).at("/choices/0/delta/content").asText());
    }

    @Test
    void identityFieldsAreStableAcrossFrames() throws Exception {
        var writer = writer();
        writer.content("a");
        writer.content("b");
        writer.finish("stop");

        for (JsonNode frame : frames()) {
            assertEquals(ID, frame.get("id").asText());
            assertEquals(MODEL, frame.get("model").asText());
            assertEquals(CREATED, frame.get("created").asLong());
            assertEquals("chat.completion.chunk", frame.get("object").asText());
        }
    }

    @Test
    void terminalFrameHasEmptyDeltaAndFinishReason() throws Exception {
        var writer = writer();
        writer.content("hi");
        writer.finish("stop");

        JsonNode last = frames().getLast();
        assertTrue(last.at("/choices/0/delta").isObject());
        assertTrue(last.at("/choices/0/delta").isEmpty(),
                "the terminal delta must serialize as {} — the protocol says so");
        assertEquals("stop", last.at("/choices/0/finish_reason").asText());
    }

    @Test
    void streamEndsWithDoneSentinel() {
        var writer = writer();
        writer.content("hi");
        writer.finish("stop");

        assertTrue(body().endsWith("data: [DONE]\n\n"));
    }

    @Test
    void everyFrameIsSeparatedByABlankLine() {
        var writer = writer();
        writer.content("one");
        writer.content("two");
        writer.finish("stop");

        // 4 frames (role, one, two, terminal) + [DONE], each ending in "\n\n".
        assertEquals(5, body().split("\n\n").length);
    }

    // ─── escaping ───

    @Test
    void escapesCharactersThatWouldBreakTheFraming() throws Exception {
        var writer = writer();
        writer.content("line1\nline2 \"quoted\" \\ back");

        String raw = body();
        assertFalse(raw.contains("line1\nline2"), "a raw newline inside a frame would split it");
        assertEquals("line1\nline2 \"quoted\" \\ back",
                frames().get(1).at("/choices/0/delta/content").asText());
    }

    @Test
    void preservesUnicodeAndEmoji() throws Exception {
        var writer = writer();
        writer.content("Grüße 👋 日本語");

        assertEquals("Grüße 👋 日本語", frames().get(1).at("/choices/0/delta/content").asText());
    }

    // ─── idempotence and robustness ───

    @Test
    void finishIsIdempotent() {
        var writer = writer();
        writer.content("hi");
        writer.finish("stop");
        writer.finish("stop");

        assertEquals(1, countOccurrences(body(), "data: [DONE]"),
                "a second terminator would confuse strict clients");
    }

    @Test
    void finishWithoutContentStillProducesAValidStream() throws Exception {
        var writer = writer();
        writer.finish("stop");

        List<JsonNode> frames = frames();
        assertEquals("assistant", frames.get(0).at("/choices/0/delta/role").asText());
        assertEquals("stop", frames.getLast().at("/choices/0/finish_reason").asText());
        assertTrue(body().endsWith("data: [DONE]\n\n"));
    }

    @Test
    void emptyAndNullContentAreSkipped() {
        var writer = writer();
        writer.content(null);
        writer.content("");

        assertEquals("", body(), "an empty delta carries no information");
        assertFalse(writer.hasStarted(), "a skipped delta must not even open the stream");
    }

    @Test
    void writesAreFlushedPerFrame() {
        var counting = new CountingOutputStream();
        var writer = new OpenAiSseWriter(counting, objectMapper, ID, MODEL, CREATED, false);

        writer.content("a");
        writer.content("b");

        // role + a + b = 3 frames, each flushed. Buffering here is
        // indistinguishable from a hung stream at the client.
        assertEquals(3, counting.flushes);
    }

    @Test
    void clientDisconnectDoesNotPropagate() {
        var broken = new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                throw new IOException("client went away");
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                throw new IOException("client went away");
            }
        };
        var writer = new OpenAiSseWriter(broken, objectMapper, ID, MODEL, CREATED, false);

        assertDoesNotThrow(() -> {
            writer.content("a");
            writer.content("b");
            writer.finish("stop");
        }, "a disconnected client is routine; it must not raise once per token");
    }

    @Test
    void hasStartedTracksTheOpeningFrame_notContent() {
        var writer = writer();
        assertFalse(writer.hasStarted());

        writer.finish("stop");

        assertTrue(writer.hasStarted(),
                "finish() opens the stream too — the flag means 'started', not 'sent content'");
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int index = haystack.indexOf(needle);
        while (index >= 0) {
            count++;
            index = haystack.indexOf(needle, index + needle.length());
        }
        return count;
    }

    // ─── usage ───

    @Test
    void usageChunkIsEmittedAfterTheTerminator_whenRequested() throws Exception {
        var writer = new OpenAiSseWriter(out, objectMapper, ID, MODEL, CREATED, true);
        writer.content("Hi");
        writer.usage(new TokenUsage(10L, 20L, 30L));
        writer.finish("stop");

        List<JsonNode> frames = frames();
        JsonNode last = frames.get(frames.size() - 1);
        JsonNode terminal = frames.get(frames.size() - 2);

        assertEquals("stop", terminal.at("/choices/0/finish_reason").asText(),
                "usage must follow the finish_reason frame, not replace it");
        assertTrue(last.get("choices").isEmpty(), "the usage frame carries no choices");
        assertEquals(30L, last.at("/usage/total_tokens").asLong());
        assertTrue(body().endsWith("data: [DONE]\n\n"), "[DONE] must still be last");
    }

    @Test
    void usageIsWithheldWhenTheClientDidNotAskForIt() throws Exception {
        var writer = writer();
        writer.content("Hi");
        writer.usage(new TokenUsage(10L, 20L, 30L));
        writer.finish("stop");

        for (JsonNode frame : frames()) {
            assertTrue(frame.at("/usage").isMissingNode(),
                    "an unrequested usage frame is a protocol deviation: " + frame);
        }
    }

    @Test
    void noUsageChunkWhenTheAgentReportedNone() throws Exception {
        // A rule-based agent calls no model. Asking for usage must not conjure an
        // empty-choices frame with nothing in it.
        var writer = new OpenAiSseWriter(out, objectMapper, ID, MODEL, CREATED, true);
        writer.content("Hi");
        writer.finish("stop");

        for (JsonNode frame : frames()) {
            assertTrue(frame.at("/usage").isMissingNode(), frame.toString());
            assertTrue(!frame.get("choices").isEmpty(), "no empty-choices frame should be emitted");
        }
    }

    /** Counts flushes so per-frame flushing can be asserted. */
    private static final class CountingOutputStream extends OutputStream {
        int flushes;

        @Override
        public void write(int b) {
            // discarded
        }

        @Override
        public void write(byte[] b, int off, int len) {
            // discarded
        }

        @Override
        public void flush() {
            flushes++;
        }
    }
}
