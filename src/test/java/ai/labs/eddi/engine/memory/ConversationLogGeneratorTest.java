package ai.labs.eddi.engine.memory;

import ai.labs.eddi.engine.memory.model.ConversationLog;
import ai.labs.eddi.engine.memory.model.ConversationOutput;
import ai.labs.eddi.engine.memory.model.Data;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ConversationLogGeneratorTest {

    @Test
    void generate_emptyConversation_returnsEmptyLog() {
        var memory = new ConversationMemory("agent-1", 1, "user-1");
        var generator = new ConversationLogGenerator(memory);

        ConversationLog log = generator.generate();
        assertNotNull(log);
        assertTrue(log.getMessages().isEmpty());
    }

    @Test
    void generate_withUserInput_containsUserMessage() {
        var memory = new ConversationMemory("agent-1", 1, "user-1");
        memory.getCurrentStep().addConversationOutputString("input", "Hello");

        var generator = new ConversationLogGenerator(memory);
        ConversationLog log = generator.generate();

        assertFalse(log.getMessages().isEmpty());
        assertEquals("user", log.getMessages().getFirst().getRole());
    }

    @Test
    void generate_withOutput_containsAssistantMessage() {
        var memory = new ConversationMemory("agent-1", 1, "user-1");
        memory.getCurrentStep().addConversationOutputString("input", "Hi");
        memory.getCurrentStep().addConversationOutputObject("output",
                List.of(Map.of("text", "Hello there!")));

        var generator = new ConversationLogGenerator(memory);
        ConversationLog log = generator.generate();

        assertEquals(2, log.getMessages().size());
        assertEquals("assistant", log.getMessages().get(1).getRole());
    }

    @Test
    void generate_withLogSizeLimit_limitsMessages() {
        var memory = new ConversationMemory("agent-1", 1, "user-1");
        memory.getCurrentStep().addConversationOutputString("input", "msg1");
        memory.startNextStep();
        memory.getCurrentStep().addConversationOutputString("input", "msg2");
        memory.startNextStep();
        memory.getCurrentStep().addConversationOutputString("input", "msg3");

        var generator = new ConversationLogGenerator(memory);
        ConversationLog log = generator.generate(1);

        // Should only include last turn
        assertTrue(log.getMessages().size() <= 2);
    }

    @Test
    void generate_withZeroLogSize_returnsEmpty() {
        var memory = new ConversationMemory("agent-1", 1, "user-1");
        memory.getCurrentStep().addConversationOutputString("input", "Hello");

        var generator = new ConversationLogGenerator(memory);
        ConversationLog log = generator.generate(0);

        assertTrue(log.getMessages().isEmpty());
    }

    @Test
    void generate_excludeFirstAgentMessage_removesFirst() {
        var memory = new ConversationMemory("agent-1", 1, "user-1");
        memory.getCurrentStep().addConversationOutputString("input", "Hello");
        memory.getCurrentStep().addConversationOutputObject("output",
                List.of(Map.of("text", "Welcome!")));

        var generator = new ConversationLogGenerator(memory);
        ConversationLog logWithFirst = generator.generate(-1, true);
        ConversationLog logWithoutFirst = generator.generate(-1, false);

        assertTrue(logWithoutFirst.getMessages().size() < logWithFirst.getMessages().size());
    }

    @Test
    void generate_nullMemory_throwsIllegalState() {
        // ConversationLogGenerator with null memory and null snapshot
        assertThrows(IllegalStateException.class, () -> {
            var gen = new ConversationLogGenerator((IConversationMemory) null);
            gen.generate();
        });
    }
}
