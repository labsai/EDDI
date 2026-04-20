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
