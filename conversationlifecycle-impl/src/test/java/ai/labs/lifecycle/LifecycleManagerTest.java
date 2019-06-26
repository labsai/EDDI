package ai.labs.lifecycle;

import ai.labs.memory.IConversationMemory;
import ai.labs.memory.IConversationMemory.IWritableConversationStep;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import static org.mockito.Mockito.mock;

/**
 * @author ginccc
 */
public class LifecycleManagerTest {
    private ILifecycleManager lifecycleManager;
    private IConversationMemory memory;

    @Before
    public void setUp() {
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
        lifecycleManager.executeLifecycle(memory);

        //assert
        Mockito.verify(lifecycleTask, Mockito.atMost(1)).executeTask(memory);
    }

    @Test
    public void testValidationWhenMemoryIsNull() {
        //test
        try {
            lifecycleManager.executeLifecycle(null);
            Assert.fail();
        } catch (Exception e) {
            if (!e.getClass().equals(IllegalArgumentException.class)) {
                Assert.fail();
            }
        }
    }

    @Test
    public void testValidationWhenLifecycleIsNull() {
        //test
        try {
            lifecycleManager.addLifecycleTask(null);
            Assert.fail();
        } catch (Exception e) {
            if (!e.getClass().equals(IllegalArgumentException.class)) {
                Assert.fail();
            }
        }
    }
}
