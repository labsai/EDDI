package ai.labs.eddi.engine.lifecycle;

import ai.labs.eddi.datastore.model.ResourceId;
import ai.labs.eddi.engine.lifecycle.internal.ComponentCache;
import ai.labs.eddi.engine.lifecycle.internal.LifecycleManager;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IConversationMemory.IWritableConversationStep;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.mockito.Mockito.mock;

/**
 * @author ginccc
 */
public class LifecycleManagerTest {
    private static ILifecycleManager lifecycleManager;
    private static IConversationMemory memory;

    @BeforeEach
    public void setUp() {
        lifecycleManager = new LifecycleManager(new ComponentCache(), new ResourceId("id", 1));
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
        Mockito.verify(lifecycleTask, Mockito.atMost(1)).execute(memory, null);
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
