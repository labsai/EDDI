package ai.labs.eddi.engine.lifecycle;

import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IConversationMemory.IWritableConversationStep;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.mockito.Mockito.mock;

/**
 * @author ginccc
 */
public class LifecycleManagerTest {
    private static ILifecycleManager lifecycleManager;
    private static IConversationMemory memory;

    @BeforeAll
    public static void setUp() {
        lifecycleManager = new LifecycleManager();
        memory = mock(IConversationMemory.class);
        IWritableConversationStep currentConversationStep = mock(IWritableConversationStep.class);
        Mockito.when(memory.getCurrentStep()).thenAnswer(invocation -> currentConversationStep);
    }

    @Test
    public void testExecuteLifecycle() throws Exception {
        //setup
        ILifecycleTask lifecycleTask = mock(ILifecycleTask.class);
        lifecycleManager.addLifecycleTask(lifecycleTask);

        //test
        lifecycleManager.executeLifecycle(memory, null);

        //assert
        Mockito.verify(lifecycleTask, Mockito.atMost(1)).executeTask(memory);
    }

    @Test
    public void testValidationWhenMemoryIsNull() {
        //test
        try {
            lifecycleManager.executeLifecycle(null, null);
            Assertions.fail();
        } catch (Exception e) {
            if (!e.getClass().equals(IllegalArgumentException.class)) {
                Assertions.fail();
            }
        }
    }

    @Test
    public void testValidationWhenLifecycleIsNull() {
        //test
        try {
            lifecycleManager.addLifecycleTask(null);
            Assertions.fail();
        } catch (Exception e) {
            if (!e.getClass().equals(IllegalArgumentException.class)) {
                Assertions.fail();
            }
        }
    }
}
