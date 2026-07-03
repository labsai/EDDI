/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.memory;

import ai.labs.eddi.engine.memory.model.ConversationLog;
import ai.labs.eddi.engine.memory.model.ConversationOutput;
import ai.labs.eddi.modules.output.model.types.TextOutputItem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link ConversationLogGenerator}.
 */
@DisplayName("ConversationLogGenerator")
class ConversationLogGeneratorTest {

    // ─── Construction ───────────────────────────────────────────

    @Nested
    @DisplayName("Error handling")
    class ErrorHandling {

        @Test
        @DisplayName("null memory and snapshot — throws IllegalStateException")
        void nullEverything() {
            var generator = new ConversationLogGenerator((IConversationMemory) null);
            assertThrows(IllegalStateException.class, generator::generate);
        }
    }

    // ─── generate() branch coverage (compound conditions) ──────

    @Nested
    @DisplayName("generate branch coverage")
    class GenerateBranches {

        private IConversationMemory memoryWith(ConversationOutput output) {
            var memory = mock(IConversationMemory.class);
            when(memory.getConversationOutputs()).thenReturn(new ArrayList<>(List.of(output)));
            return memory;
        }

        @Test
        @DisplayName("inputFiles first element not a Map → only text content")
        void inputFilesFirstNotMap() {
            var output = new ConversationOutput();
            output.put("input", "hi");
            output.put("context", Map.of("inputFiles", List.of("not-a-map")));
            var log = new ConversationLogGenerator(memoryWith(output)).generate(-1, true);
            assertEquals("hi", log.getMessages().getFirst().getContent().getLast().getValue());
            assertEquals(1, log.getMessages().getFirst().getContent().size());
        }

        @Test
        @DisplayName("context without inputFiles → only text content")
        void contextWithoutInputFiles() {
            var output = new ConversationOutput();
            output.put("input", "hi");
            output.put("context", Map.of("language", "en"));
            var log = new ConversationLogGenerator(memoryWith(output)).generate(-1, true);
            assertEquals(1, log.getMessages().getFirst().getContent().size());
        }

        @Test
        @DisplayName("empty output list → no assistant message")
        void emptyOutputList() {
            var output = new ConversationOutput();
            output.put("input", "hi");
            output.put("output", new ArrayList<>());
            var log = new ConversationLogGenerator(memoryWith(output)).generate(-1, true);
            assertEquals(1, log.getMessages().size());
        }
    }

    // ─── withAttachmentExtracts (direct branch coverage) ────────

    @Nested
    @DisplayName("withAttachmentExtracts helper")
    class WithAttachmentExtracts {

        private IConversationMemory.IConversationStepStack stackWith(List<String> extracts) {
            var step = mock(IConversationMemory.IConversationStep.class);
            @SuppressWarnings("unchecked")
            IData<List<String>> data = mock(IData.class);
            when(data.getResult()).thenReturn(extracts);
            when(step.getLatestData(MemoryKeys.ATTACHMENT_EXTRACTS)).thenReturn(data);
            var stack = mock(IConversationMemory.IConversationStepStack.class);
            when(stack.size()).thenReturn(1);
            when(stack.get(0)).thenReturn(step);
            return stack;
        }

        @Test
        void nullStack_returnsInput() {
            assertEquals("hi", ConversationLogGenerator.withAttachmentExtracts(null, 0, "hi"));
        }

        @Test
        void nullInput_returnsNull() {
            assertNull(ConversationLogGenerator.withAttachmentExtracts(stackWith(List.of("x")), 0, null));
        }

        @Test
        void negativeIndex_returnsInput() {
            assertEquals("hi", ConversationLogGenerator.withAttachmentExtracts(stackWith(List.of("x")), -1, "hi"));
        }

        @Test
        void indexOutOfRange_returnsInput() {
            assertEquals("hi", ConversationLogGenerator.withAttachmentExtracts(stackWith(List.of("x")), 5, "hi"));
        }

        @Test
        void nullData_returnsInput() {
            var step = mock(IConversationMemory.IConversationStep.class);
            when(step.getLatestData(MemoryKeys.ATTACHMENT_EXTRACTS)).thenReturn(null);
            var stack = mock(IConversationMemory.IConversationStepStack.class);
            when(stack.size()).thenReturn(1);
            when(stack.get(0)).thenReturn(step);
            assertEquals("hi", ConversationLogGenerator.withAttachmentExtracts(stack, 0, "hi"));
        }

        @Test
        void nullResult_returnsInput() {
            assertEquals("hi", ConversationLogGenerator.withAttachmentExtracts(stackWith(null), 0, "hi"));
        }

        @Test
        void emptyResult_returnsInput() {
            assertEquals("hi", ConversationLogGenerator.withAttachmentExtracts(stackWith(List.of()), 0, "hi"));
        }

        @Test
        void presentResult_appends() {
            String out = ConversationLogGenerator.withAttachmentExtracts(stackWith(List.of("doc: text")), 0, "hi");
            assertTrue(out.startsWith("hi"));
            assertTrue(out.contains("doc: text"));
        }
    }

    // ─── Attachment extract stitching ───────────────────────────

    @Nested
    @DisplayName("Attachment extract stitching")
    class ExtractStitching {

        private IConversationMemory memoryWithExtract(String input, List<String> extracts) {
            var output = new ConversationOutput();
            output.put("input", input);
            var memory = mock(IConversationMemory.class);
            when(memory.getConversationOutputs()).thenReturn(new ArrayList<>(List.of(output)));

            var step = mock(IConversationMemory.IConversationStep.class);
            @SuppressWarnings("unchecked")
            IData<List<String>> data = mock(IData.class);
            when(data.getResult()).thenReturn(extracts);
            when(step.getLatestData(MemoryKeys.ATTACHMENT_EXTRACTS)).thenReturn(data);

            var stack = mock(IConversationMemory.IConversationStepStack.class);
            when(stack.size()).thenReturn(1);
            when(stack.get(0)).thenReturn(step);
            when(memory.getAllSteps()).thenReturn(stack);
            return memory;
        }

        @Test
        @DisplayName("stitchExtracts=true appends extracts to the user turn")
        void stitchesWhenEnabled() {
            var memory = memoryWithExtract("Summarize this", List.of("report.pdf: quarterly numbers"));
            var log = new ConversationLogGenerator(memory).generate(-1, true, true);

            String userText = log.getMessages().getFirst().getContent().getLast().getValue();
            assertTrue(userText.contains("Summarize this"));
            assertTrue(userText.contains("quarterly numbers"), "extracts should be stitched: " + userText);
        }

        @Test
        @DisplayName("stitchExtracts=false leaves the transcript clean")
        void noStitchWhenDisabled() {
            var memory = memoryWithExtract("Summarize this", List.of("report.pdf: quarterly numbers"));
            var log = new ConversationLogGenerator(memory).generate(-1, true, false);

            String userText = log.getMessages().getFirst().getContent().getLast().getValue();
            assertEquals("Summarize this", userText);
            verify(memory, never()).getAllSteps();
        }

        @Test
        @DisplayName("no extracts on the step leaves input unchanged")
        void noExtractsUnchanged() {
            var memory = memoryWithExtract("Hi", List.of());
            var log = new ConversationLogGenerator(memory).generate(-1, true, true);
            assertEquals("Hi", log.getMessages().getFirst().getContent().getLast().getValue());
        }
    }

    // ─── Basic generation ───────────────────────────────────────

    @Nested
    @DisplayName("Basic log generation")
    class BasicGeneration {

        @Test
        @DisplayName("logSize 0 — returns empty log")
        void logSizeZero() {
            var memory = mock(IConversationMemory.class);
            var generator = new ConversationLogGenerator(memory);

            ConversationLog log = generator.generate(0);
            assertNotNull(log);
            assertTrue(log.getMessages().isEmpty());
        }

        @Test
        @DisplayName("empty conversation outputs — returns empty log")
        void emptyOutputs() {
            var memory = mock(IConversationMemory.class);
            when(memory.getConversationOutputs()).thenReturn(new ArrayList<>());

            var generator = new ConversationLogGenerator(memory);
            ConversationLog log = generator.generate();

            assertNotNull(log);
            assertTrue(log.getMessages().isEmpty());
        }

        @Test
        @DisplayName("single user input — generates user message")
        void singleUserInput() {
            var memory = mock(IConversationMemory.class);
            var outputs = new ArrayList<ConversationOutput>();
            var output = new ConversationOutput();
            output.put("input", "Hello");
            outputs.add(output);
            when(memory.getConversationOutputs()).thenReturn(outputs);

            var generator = new ConversationLogGenerator(memory);
            ConversationLog log = generator.generate();

            assertFalse(log.getMessages().isEmpty());
            assertEquals("user", log.getMessages().getFirst().getRole());
        }

        @Test
        @DisplayName("user input + Map output — generates user + assistant messages")
        void inputAndMapOutput() {
            var memory = mock(IConversationMemory.class);
            var outputs = new ArrayList<ConversationOutput>();
            var output = new ConversationOutput();
            output.put("input", "What's up?");
            output.put("output", List.of(Map.of("text", "Not much!")));
            outputs.add(output);
            when(memory.getConversationOutputs()).thenReturn(outputs);

            var generator = new ConversationLogGenerator(memory);
            ConversationLog log = generator.generate();

            assertEquals(2, log.getMessages().size());
            assertEquals("user", log.getMessages().get(0).getRole());
            assertEquals("assistant", log.getMessages().get(1).getRole());
            // Verify actual content value
            var assistantContent = log.getMessages().get(1).getContent();
            assertFalse(assistantContent.isEmpty());
            assertEquals("Not much!", assistantContent.getFirst().getValue());
        }

        @Test
        @DisplayName("user input + TextOutputItem output — generates assistant message")
        void textOutputItemOutput() {
            var memory = mock(IConversationMemory.class);
            var outputs = new ArrayList<ConversationOutput>();
            var output = new ConversationOutput();
            output.put("input", "Hello");
            var textItem = new TextOutputItem("Hi there!");
            output.put("output", List.of(textItem));
            outputs.add(output);
            when(memory.getConversationOutputs()).thenReturn(outputs);

            var generator = new ConversationLogGenerator(memory);
            ConversationLog log = generator.generate();

            assertEquals(2, log.getMessages().size());
            assertEquals("assistant", log.getMessages().get(1).getRole());
            // Verify actual text content
            assertEquals("Hi there!", log.getMessages().get(1).getContent().getFirst().getValue());
        }
    }

    // ─── Log size windowing ─────────────────────────────────────

    @Nested
    @DisplayName("Log size windowing")
    class WindowTests {

        @Test
        @DisplayName("logSize limits output to last N entries")
        void logSizeLimits() {
            var memory = mock(IConversationMemory.class);
            var outputs = new ArrayList<ConversationOutput>();
            for (int i = 0; i < 5; i++) {
                var output = new ConversationOutput();
                output.put("input", "msg" + i);
                outputs.add(output);
            }
            when(memory.getConversationOutputs()).thenReturn(outputs);

            var generator = new ConversationLogGenerator(memory);
            ConversationLog log = generator.generate(2);

            // Only last 2 outputs → 2 user messages
            assertEquals(2, log.getMessages().size());
        }

        @Test
        @DisplayName("logSize -1 — includes all entries")
        void logSizeMinusOne() {
            var memory = mock(IConversationMemory.class);
            var outputs = new ArrayList<ConversationOutput>();
            for (int i = 0; i < 3; i++) {
                var output = new ConversationOutput();
                output.put("input", "msg" + i);
                outputs.add(output);
            }
            when(memory.getConversationOutputs()).thenReturn(outputs);

            var generator = new ConversationLogGenerator(memory);
            ConversationLog log = generator.generate(-1);

            assertEquals(3, log.getMessages().size());
        }

        @Test
        @DisplayName("logSize > outputs — includes all")
        void logSizeLarger() {
            var memory = mock(IConversationMemory.class);
            var outputs = new ArrayList<ConversationOutput>();
            var output = new ConversationOutput();
            output.put("input", "only");
            outputs.add(output);
            when(memory.getConversationOutputs()).thenReturn(outputs);

            var generator = new ConversationLogGenerator(memory);
            ConversationLog log = generator.generate(100);

            assertEquals(1, log.getMessages().size());
        }
    }

    // ─── includeFirstAgentMessage ────────────────────────────────

    @Nested
    @DisplayName("includeFirstAgentMessage")
    class IncludeFirstTests {

        @Test
        @DisplayName("false — removes first message")
        void excludeFirst() {
            var memory = mock(IConversationMemory.class);
            var outputs = new ArrayList<ConversationOutput>();
            var o1 = new ConversationOutput();
            o1.put("input", "first");
            outputs.add(o1);
            var o2 = new ConversationOutput();
            o2.put("input", "second");
            outputs.add(o2);
            when(memory.getConversationOutputs()).thenReturn(outputs);

            var generator = new ConversationLogGenerator(memory);
            ConversationLog log = generator.generate(-1, false);

            assertEquals(1, log.getMessages().size());
        }
    }

    // ─── Input files (context) ──────────────────────────────────

    @Nested
    @DisplayName("Input Files")
    class InputFileTests {

        @Test
        @DisplayName("context with inputFiles — generates content items")
        void contextWithInputFiles() {
            var memory = mock(IConversationMemory.class);
            var outputs = new ArrayList<ConversationOutput>();
            var output = new ConversationOutput();
            output.put("input", "check this image");
            output.put("context", Map.of(
                    "inputFiles", List.of(
                            Map.of("type", "image", "url", "https://example.com/img.png"))));
            outputs.add(output);
            when(memory.getConversationOutputs()).thenReturn(outputs);

            var generator = new ConversationLogGenerator(memory);
            ConversationLog log = generator.generate();

            // Should have user message with 2 content items (image + text)
            var userMsg = log.getMessages().getFirst();
            assertEquals("user", userMsg.getRole());
            assertEquals(2, userMsg.getContent().size());
            // First content item should be the image file
            var imageContent = userMsg.getContent().get(0);
            assertEquals("https://example.com/img.png", imageContent.getValue());
        }
    }

    // ─── Edge cases ─────────────────────────────────────────────

    @Nested
    @DisplayName("Edge cases")
    class EdgeCases {

        @Test
        @DisplayName("output is empty list — no assistant message")
        void emptyOutputList() {
            var memory = mock(IConversationMemory.class);
            var outputs = new ArrayList<ConversationOutput>();
            var output = new ConversationOutput();
            output.put("input", "hello");
            output.put("output", List.of());
            outputs.add(output);
            when(memory.getConversationOutputs()).thenReturn(outputs);

            var generator = new ConversationLogGenerator(memory);
            ConversationLog log = generator.generate();

            // Only user message, no assistant (empty output list)
            assertEquals(1, log.getMessages().size());
            assertEquals("user", log.getMessages().getFirst().getRole());
        }

        @Test
        @DisplayName("null input — no user message, but output is generated")
        void nullInput() {
            var memory = mock(IConversationMemory.class);
            var outputs = new ArrayList<ConversationOutput>();
            var output = new ConversationOutput();
            // No "input" key
            output.put("output", List.of(Map.of("text", "Hi!")));
            outputs.add(output);
            when(memory.getConversationOutputs()).thenReturn(outputs);

            var generator = new ConversationLogGenerator(memory);
            ConversationLog log = generator.generate();

            // Only assistant message, no user
            assertEquals(1, log.getMessages().size());
            assertEquals("assistant", log.getMessages().getFirst().getRole());
        }
    }
}
