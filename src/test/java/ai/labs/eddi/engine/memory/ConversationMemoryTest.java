package ai.labs.eddi.engine.memory;

import ai.labs.eddi.engine.memory.model.Data;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedList;

/**
 * @author ginccc
 */
public class ConversationMemoryTest {
    private static ConversationMemory memory;

    @BeforeEach
    public void setUp() {
        memory = new ConversationMemory("", 0);
    }

    @Test
    public void testInitialization() {
        //assert
        Assertions.assertNotNull(memory.getCurrentStep());
        Assertions.assertEquals(0, memory.getPreviousSteps().size());
    }

    @Test
    public void testQueryOrder() {
        // setup
        IConversationMemory.IWritableConversationStep step1 = memory.getCurrentStep();
        IConversationMemory.IConversationStep step2 = memory.startNextStep();
        IConversationMemory.IConversationStep step3 = memory.startNextStep();

        // test
        IConversationMemory.IConversationStepStack steps = memory.getAllSteps();

        // assert
        Assertions.assertSame(step3, steps.get(0));
        Assertions.assertSame(step2, steps.get(1));
        Assertions.assertSame(step1, steps.get(2));
    }

    @Test
    public void testStartNextStep() {
        //setup
        IConversationMemory.IWritableConversationStep entry = memory.getCurrentStep();

        final Data<LinkedList<Object>> data = new Data<>("testkey", new LinkedList<>());
        entry.storeData(data);

        //test
        memory.startNextStep();

        //assert
        Assertions.assertEquals(2, memory.size());
        Assertions.assertEquals(1, memory.getPreviousSteps().size());
        Assertions.assertSame(entry, memory.getPreviousSteps().get(0));
    }

    @Test
    public void testUndo() {
        //setup
        IConversationMemory.IWritableConversationStep entry = memory.getCurrentStep();
        final Data<LinkedList<Object>> data = new Data<>("testkey", new LinkedList<>());
        entry.storeData(data);
        memory.startNextStep();

        // test
        memory.undoLastStep();

        // assert
        Assertions.assertEquals(1, memory.size());
        Assertions.assertTrue(memory.isRedoAvailable());
    }

    @Test
    public void testRedo() {
        //setup
        memory.startNextStep();

        IConversationMemory.IWritableConversationStep entry = memory.getCurrentStep();
        final Data<LinkedList<Object>> data = new Data<>("testkey", new LinkedList<>());
        entry.storeData(data);

        memory.undoLastStep();

        // test
        memory.redoLastStep();

        // assert
        Assertions.assertEquals(2, memory.size());
        Assertions.assertNotNull(memory.getCurrentStep().getData("testkey"));
        Assertions.assertFalse(memory.isRedoAvailable());

    }
}
