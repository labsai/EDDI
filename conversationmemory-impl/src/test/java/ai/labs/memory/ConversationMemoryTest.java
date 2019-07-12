package ai.labs.memory;

import ai.labs.memory.model.Data;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedList;

/**
 * @author ginccc
 */
class ConversationMemoryTest {
    private ConversationMemory memory;

    @BeforeEach
    void setUp() {
        memory = new ConversationMemory("", 0);
    }

    @Test
    void testInitialization() {
        //assert
        Assertions.assertNotNull(memory.getCurrentStep());
        Assertions.assertEquals(0, memory.getPreviousSteps().size());
    }

    @Test
    void testQueryOrder() {
        // setup
        IConversationMemory.IWritableConversationStep step1 = memory.getCurrentStep();
        IConversationMemory.IConversationStep step2 = memory.startNextStep();
        IConversationMemory.IConversationStep step3 = memory.startNextStep();

        // test
        IConversationMemory.IConversationStepStack steps = memory.getAllSteps();

        // test
        Assertions.assertSame(step3, steps.get(0));
        Assertions.assertSame(step2, steps.get(1));
        Assertions.assertSame(step1, steps.get(2));
    }

    //todo
    /*@Test
    void testQueryOrderFromZeroAge() throws Exception
     {
         // setup
         IConversationMemory.IWritableConversationStep step1 = memory.getCurrentStep();
         ConversationStep step2 = memory.startNextStep();
         ConversationStep step3 = memory.startNextStep();

         // test
         IConversationMemory.IConversationStepStack steps = memory.getAllStepsFromAge(0);

         // test
         Assertions.assertSame(step3, steps.get(0));
         Assertions.assertSame(step2, steps.get(1));
         Assertions.assertSame(step1, steps.get(2));
     }

     @Test
    void testQueryOrderFromAge() throws Exception
     {
         // setup
         IConversationMemory.IWritableConversationStep step1 = memory.getCurrentStep();
         ConversationStep step2 = memory.startNextStep();
         ConversationStep step3 = memory.startNextStep();

         // test
         IConversationMemory.IConversationStepStack steps = memory.getAllStepsFromAge(1);

         // test
         Assertions.assertSame(step2, steps.get(0));
         Assertions.assertSame(step1, steps.get(1));
     } */

    @Test
    void testStartNextStep() {
        //setup
        IConversationMemory.IWritableConversationStep entry = memory.getCurrentStep();

        final Data data = new Data<>("testkey", new LinkedList());
        entry.storeData(data);

        //test
        memory.startNextStep();

        //assert
        Assertions.assertEquals(2, memory.size());
        Assertions.assertEquals(1, memory.getPreviousSteps().size());
        Assertions.assertSame(entry, memory.getPreviousSteps().get(0));
    }

    @Test
    void testUndo() {
        //setup
        IConversationMemory.IWritableConversationStep entry = memory.getCurrentStep();
        final Data data = new Data<>("testkey", new LinkedList());
        entry.storeData(data);
        memory.startNextStep();

        // test
        memory.undoLastStep();

        // assert
        Assertions.assertEquals(1, memory.size());
        Assertions.assertTrue(memory.isRedoAvailable());
    }

    @Test
    void testRedo() {
        //setup
        memory.startNextStep();

        IConversationMemory.IWritableConversationStep entry = memory.getCurrentStep();
        final Data data = new Data<>("testkey", new LinkedList());
        entry.storeData(data);

        memory.undoLastStep();

        // test
        memory.redoLastStep();

        // assert
        Assertions.assertEquals(2, memory.size());
        Assertions.assertNotNull(memory.getCurrentStep().getData("testkey"));
        Assertions.assertFalse(memory.isRedoAvailable());

    }


//	@Test
//	public void testEqualLifecycleMemory() throws Exception
//	{
//		//setup
//		final IMemory.ILifecycleMemory lifecycleMemory1 = memory.getLifecycleMemory();
//		lifecycleMemory1.storeData(new Data("testkey", new LinkedList()));
//		memory.clearLifecycleMemory();
//		final IMemory.ILifecycleMemory lifecycleMemory2 = memory.getLifecycleMemory();
//		lifecycleMemory2.storeData(new Data("testkey", new LinkedList()));
//
//		//assert
//		Assertions.assertEquals(lifecycleMemory1, lifecycleMemory2);
//	}
//
//	@Test
//	public void testEqualConversationMemory() throws Exception
//	{
//		//setup
//		memory.getLifecycleMemory().storeData(new Data("testkey", new LinkedList()));
//		((Memory)memory).integrateLifecycleIntoConversation();
//		final IMemory.IConversationHistory conversationMemory1 = memory.getConversationMemory();
//		memory.clearConversationMemory();
//		memory.getLifecycleMemory().storeData(new Data("testkey", new LinkedList()));
//		((Memory)memory).integrateLifecycleIntoConversation();
//		final IMemory.IConversationHistory conversationMemory2 = memory.getConversationMemory();
//
//		//assert
//		Assertions.assertEquals(conversationMemory1, conversationMemory2);
//	}
//
//	@Test
//	public void testClearLifecycleMemory() throws Exception
//	{
//		//setup
//		IMemory.ILifecycleMemory lifecycleMemory = memory.getLifecycleMemory();
//		final Data data = new Data("testkey", new LinkedList());
//		lifecycleMemory.storeData(data);
//
//		//test
//		memory.clearLifecycleMemory();
//
//		//assert
//		Assertions.assertFalse(lifecycleMemory.equals(memory.getLifecycleMemory()));
//		Assertions.assertTrue(memory.getLifecycleMemory().size() == 0);
//	}
//
//	@Test
//	public void testClearConversationMemory() throws Exception
//	{
//		//setup
//		IMemory.IConversationHistory conversationHistory = memory.getConversationMemory();
//		((Memory)memory).integrateLifecycleIntoConversation();
//
//		//test
//		memory.clearConversationMemory();
//
//		//assert
//		Assertions.assertTrue(memory.getConversationMemory().size() == 1);
//		Assertions.assertFalse(conversationHistory.equals(memory.getConversationMemory()));
//	}
//
//
//
//	@Test
//	public void testExtractLifecycleFromConversation() throws Exception
//	{
//		//setup
//		IMemory.ILifecycleMemory lifecycleMemory = memory.getLifecycleMemory();
//		IMemory.IConversationHistory conversationHistory = memory.getConversationMemory();
//		final Data data = new Data("testkey", new LinkedList());
//		lifecycleMemory.storeData(data);
//
//		//test
//		((Memory)memory).integrateLifecycleIntoConversation();
//		((Memory)memory).extractLifecycleFromConversation();
//
//		//assert
//		Assertions.assertTrue(conversationHistory.size() == 1);
//		Assertions.assertTrue(lifecycleMemory.equals(memory.getLifecycleMemory()));
//	}
//
//	@Test
//    public void testLifecycleMemoryViaGetConversationCycle() throws Exception
//    {
//        //test
//		IMemory.IConversationHistory conversationHistory = memory.getConversationMemory();
//        IMemory.ILifecycleStore latestLifecycleStore = conversationHistory.getLifecycleStore(0);
//
//        //assert
//        Assertions.assertTrue(conversationHistory.size() == 1);
//        Assertions.assertEquals(memory.getLifecycleMemory(), latestLifecycleStore);
//    }
//
//    @Test
//    public void testGetConversationCycle() throws Exception
//    {
//        //setup
//		IMemory.IConversationHistory conversationHistory = memory.getConversationMemory();
//		memory.getLifecycleMemory().storeData(new Data("testkey", new LinkedList()));
//		((Memory)memory).integrateLifecycleIntoConversation();
//
//        //test
//        IMemory.ILifecycleStore secondToLatestLifecycleStore = conversationHistory.getLifecycleStore(1);
//
//        //assert
//        Assertions.assertTrue(conversationHistory.size() == 2);
//        Assertions.assertNotNull(secondToLatestLifecycleStore);
//        Assertions.assertTrue(memory.getLifecycleMemory().equals(secondToLatestLifecycleStore));
//    }
//
//    @Test
//    public void testGetData() throws Exception
//    {
//        //setup
//		IMemory.IConversationHistory conversationHistory = memory.getConversationMemory();
//		final Data data = new Data("testkey", new LinkedList());
//		memory.getLifecycleMemory().storeData(data);
//
//		//test
//		((Memory)memory).integrateLifecycleIntoConversation();
//
//		//assert
//		final IData data1 = conversationHistory.getResult("testkey", 0);
//		Assertions.assertNotNull(data1);
//		Assertions.assertEquals(data, data1);
//    }
//
//	 @Test
//    public void testGetData1() throws Exception
//    {
//        //setup
//		IMemory.IConversationHistory conversationHistory = memory.getConversationMemory();
//		final Data data = new Data("testkey", new LinkedList());
//		memory.getLifecycleMemory().storeData(data);
//
//		//test
//		((Memory)memory).integrateLifecycleIntoConversation();
//		((Memory)memory).integrateLifecycleIntoConversation();
//		memory.clearLifecycleMemory();
//
//		//assert
//		final IData data1 = conversationHistory.getResult("testkey", 0);
//		Assertions.assertNull(data1);
//    }
//
//	@Test
//	public void testGetLatestData() throws Exception
//	{
//		//setup
//		IMemory.IConversationHistory conversationHistory = memory.getConversationMemory();
//		final Data data = new Data("testkey", new LinkedList());
//		memory.getLifecycleMemory().storeData(data);
//
//		//test
//		((Memory)memory).integrateLifecycleIntoConversation();
//		((Memory)memory).integrateLifecycleIntoConversation();
//
//		//assert
//		final IData lastestData = conversationHistory.getLatestData("testkey");
//		Assertions.assertNotNull(lastestData);
//		Assertions.assertEquals(data, lastestData);
//	}
//
//    @Test
//    public void testGetAllLatestData() throws Exception
//    {
//        //setup
//		IMemory.IConversationHistory conversationHistory = memory.getConversationMemory();
//		final Data data1 = new Data("testkey1", new LinkedList());
//		final Data data2 = new Data("testkey2", new LinkedList());
//
//		//test
//		memory.getLifecycleMemory().storeData(data1);
//		memory.getLifecycleMemory().storeData(data2);
//		((Memory)memory).integrateLifecycleIntoConversation();
//		memory.clearLifecycleMemory();
//		((Memory)memory).integrateLifecycleIntoConversation();
//
//		//assert
//		final List<IData> allLatestData = conversationHistory.getAllLatestData("testkey.*", 1);
//		Assertions.assertNotNull(allLatestData);
//		Assertions.assertTrue(allLatestData.size() == 2);
//		Assertions.assertEquals(data1, allLatestData.get(0));
//		Assertions.assertEquals(data2, allLatestData.get(1));
//    }
}
