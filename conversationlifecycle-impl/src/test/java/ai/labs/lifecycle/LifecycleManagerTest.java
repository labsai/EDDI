package ai.labs.lifecycle;

import io.sls.memory.IConversationMemory;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

/**
 * @author ginccc
 */
public class LifecycleManagerTest {
    private ILifecycleManager lifecycleManager;
    private IConversationMemory memory;

    @Before
    public void setUp() throws Exception {
        lifecycleManager = new LifecycleManager();
        memory = Mockito.mock(IConversationMemory.class);
    }

    @Test
    public void testExecuteLifecycle() throws Exception {
        //setup

        ILifecycleTask lifecycleTask = Mockito.mock(ILifecycleTask.class);
        lifecycleManager.addLifecycleTask(lifecycleTask);

        //test
        lifecycleManager.executeLifecycle(memory);

        //assert
        Mockito.verify(lifecycleTask, Mockito.atMost(1)).executeTask(memory);
    }

    @Test
    public void testValidationWhenMemoryIsNull() throws Exception {
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
    public void testValidationWhenLifecycleIsNull() throws Exception {
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
