package io.sls.core.lifecycle;

/**
 * @author ginccc
 */
public class LifecycleManagerTest {
    /* private ILifecycleManager lifecycleManager;
   private IConversation conversation;

   private class TestConversation implements IConversation
    {
        private ConversationState conversationStatus = ConversationState.READY;

        @Override
        public IConversationMemory getConversationMemory() {
            return null;  //To change body of implemented methods use File | Settings | File Templates.
        }

        @Override
        public void init() throws LifecycleException {
            //To change body of implemented methods use File | Settings | File Templates.
        }

        @Override
        public boolean isInProgress()
        {
            return false;
        }

        @Override
        public boolean isEnded()
        {
            return conversationStatus == ConversationState.ENDED;
        }

        @Override
        public void endConversation()
        {
            conversationStatus = ConversationState.ENDED;
        }

        @Override
        public void say(String input) throws LifecycleException
        {
        }
    }

    private class TestLifecycleTask implements ILifecycleTask
    {
        @Override
        public String getId()
        {
            return "";
        }

        @Override
        public Object getComponent()
        {
            return new Object();
        }

        @Override
        public List<String> getComponentDependencies()
        {
            return Collections.emptyList();
        }

        @Override
        public List<String> getOutputDependencies()
        {
            return Collections.emptyList();
        }

        @Override
        public void init() {
            //To change body of implemented methods use File | Settings | File Templates.
        }

        @Override
        public void executeTask(IConversationMemory memory) throws LifecycleException
        {
        }

        @Override
        public void configure(Map<String, Object> configuration) throws PackageConfigurationException {
            //To change body of implemented methods use File | Settings | File Templates.
        }

        @Override
        public void setExtensions(Map<String, Object> extensions) throws UnrecognizedExtensionException, IllegalExtensionConfigurationException {
            //To change body of implemented methods use File | Settings | File Templates.
        }
    }

    @Before
    public void setUp() throws Exception
    {
        memory = new Memory();
        lifecycleManager = new LifecycleManager();
        conversation = new TestConversation();
    }

    @Test
    public void testExecuteLifecycle() throws Exception
    {
        //setup
        final List<ILifecycleTask> executedLifecycleTasks = new LinkedList<ILifecycleTask>();
        final ILifecycleTask lifecycleTask = new TestLifecycleTask()
        {
            @Override
            public void executeTask(IConversationMemory memory) throws LifecycleException
            {
                executedLifecycleTasks.add(this);

            }
        };
        conversation.getLifecycleTasks().add(lifecycleTask);

        //test
        lifecycleManager.executeLifecycle(conversation);

        //assert
        Assert.assertTrue(executedLifecycleTasks.size() == 1);
    }

    @Test
    public void testValidationWhenConversationNull() throws Exception
    {
        //test
        try
        {
            lifecycleManager.executeLifecycle(null);
            Assert.fail();
        } catch(LifecycleException e)
        {
            Assert.assertEquals(NullPointerException.class, e.getCause().getClass());
            Assert.assertEquals("Cannot execute lifecycle! Conversation was null.", e.getMessage());
        }
    }

    @Test
    public void testValidationWhenConversationHasEnded() throws Exception
    {
        //setup
        conversation.endConversation();

        //test
        try
        {
            lifecycleManager.executeLifecycle(conversation);
            Assert.fail();
        } catch(LifecycleException e)
        {
            Assert.assertEquals(LifecycleException.class, e.getCause().getClass());
            Assert.assertEquals("Cannot execute lifecycle! Conversation has already ended.", e.getMessage());
        }
    }

    @Test
    public void testValidationWhenMemoryIsNull() throws Exception
    {
        //setup
        conversation = new TestConversation()
        {
            @Override
            public IMemory getMemory()
            {
                return null;
            }
        };

        //test
        try
        {
            lifecycleManager.executeLifecycle(conversation);
            Assert.fail();
        } catch(LifecycleException e)
        {
            Assert.assertEquals(NullPointerException.class, e.getCause().getClass());
            Assert.assertEquals("Cannot execute lifecycle! Memory was null.", e.getMessage());
        }
    }

    @Test
    public void testValidationWhenDialogMonitorIsNull() throws Exception
    {
        //setup
        conversation = new TestConversation()
        {
            @Override
            public IDialogMonitor getDialogMonitor()
            {
                return null;
            }
        };

        //test
        try
        {
            lifecycleManager.executeLifecycle(conversation);
            Assert.fail();
        } catch(LifecycleException e)
        {
            Assert.assertEquals(NullPointerException.class, e.getCause().getClass());
            Assert.assertEquals("Cannot execute lifecycle! DialogMonitor was null.", e.getMessage());
        }
    }

    @Test
    public void testValidationWhenReversibilityIsNull() throws Exception
    {
        //setup
        conversation = new TestConversation()
        {
            @Override
            public IReversibility getReversibility()
            {
                return null;
            }
        };

        //test
        try
        {
            lifecycleManager.executeLifecycle(conversation);
            Assert.fail();
        } catch(LifecycleException e)
        {
            Assert.assertEquals(NullPointerException.class, e.getCause().getClass());
            Assert.assertEquals("Cannot execute lifecycle! Reversibility was null.", e.getMessage());
        }
    }

    @Test
    public void testValidationWhenLifecycleIsNull() throws Exception
    {
        //setup
        conversation = new TestConversation()
        {
            @Override
            public List<ILifecycleTask> getLifecycleTasks()
            {
                return null;
            }
        };

        //test
        try
        {
            lifecycleManager.executeLifecycle(conversation);
            Assert.fail();
        } catch(LifecycleException e)
        {
            Assert.assertEquals(NullPointerException.class, e.getCause().getClass());
            Assert.assertEquals("Cannot execute lifecycle! LifecycleTasks were null.", e.getMessage());
        }
    }

    @Test
    public void testValidationWhenLifecycleIsEmpty() throws Exception
    {
        //test
        try
        {
            lifecycleManager.executeLifecycle(conversation);
            Assert.fail();
        } catch(LifecycleException e)
        {
            Assert.assertEquals(LifecycleException.class, e.getCause().getClass());
            Assert.assertEquals("Cannot execute lifecycle! LifecycleTasks were empty.", e.getMessage());
        }
    }

    @Test
    public void testCheckComponentDependencies() throws Exception
    {
        //setup
        conversation.getLifecycleTasks().add(new TestLifecycleTask()
        {
            @Override
            public String getId()
            {
                return "testId";
            }

            @Override
            public List<String> getComponentDependencies()
            {
                return Arrays.asList(Object.class.toString());
            }
        });

        //test
        try
        {
            lifecycleManager.executeLifecycle(conversation);
            Assert.fail();
        } catch(LifecycleException e)
        {
            Assert.assertEquals(CannotExecuteException.class, e.getCause().getClass());
            Assert.assertEquals("Error while executing lifecycle!", e.getMessage());
            Assert.assertEquals("testId cannot be executed because it depends on presents of class java.lang.Object " +
                                "which is NOT part of the lifecycle.", e.getCause().getMessage());
        }
    }

    @Test
    public void testCheckOutputDependencies() throws Exception
    {
        //todo
        Assert.fail();
    }*/
}
