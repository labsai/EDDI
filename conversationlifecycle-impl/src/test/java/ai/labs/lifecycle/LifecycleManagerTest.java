package ai.labs.lifecycle;

import ai.labs.memory.IConversationMemory;
import ai.labs.memory.IConversationMemory.IWritableConversationStep;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.mockito.Mockito.mock;

/**
 * @author ginccc
 */
public class LifecycleManagerTest {
    private ILifecycleManager lifecycleManager;
    private IConversationMemory memory;

    @BeforeEach
    public void setUp() {
        lifecycleManager = new LifecycleManager();
        memory = mock(IConversationMemory.class);
        IWritableConversationStep currentConversationStep = mock(IWritableConversationStep.class);
        Mockito.when(memory.getCurrentStep()).thenAnswer(invocation -> currentConversationStep);
    }

    @Test
    void testExecuteLifecycle() throws Exception {
        //setup
        ILifecycleTask lifecycleTask = mock(ILifecycleTask.class);
        lifecycleManager.addLifecycleTask(lifecycleTask);

        //test
        lifecycleManager.executeLifecycle(memory);

        //assert
        Mockito.verify(lifecycleTask, Mockito.atMost(1)).executeTask(memory);
    }

    @Test
    void testValidationWhenMemoryIsNull() {
        //test
        try {
            lifecycleManager.executeLifecycle(null);
            Assertions.fail();
        } catch (Exception e) {
            if (!e.getClass().equals(IllegalArgumentException.class)) {
                Assertions.fail();
            }
        }
    }

    @Test
    void testValidationWhenLifecycleIsNull() {
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
