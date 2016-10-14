package io.sls.memory.impl;

import io.sls.memory.IConversationMemory;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.util.LinkedList;

/**
 * Created by IntelliJ IDEA.
 * User: jarisch
 * Date: 11.02.2012
 * Time: 23:06:05
 */
public class ConversationMemoryTest {
    private ConversationMemory memory;

    @Before
    public void setUp() throws Exception {
        memory = new ConversationMemory("", 0);
    }

    @After
    public void tearDown() throws Exception {
    }

    @Test
    public void testInitialization() throws Exception {
        //assert
        Assert.assertNotNull(memory.getCurrentStep());
        Assert.assertEquals(0, memory.getPreviousSteps().size());
    }

    @Test
    public void testQueryOrder() throws Exception {
        // setup
        IConversationMemory.IWritableConversationStep step1 = memory.getCurrentStep();
        IConversationMemory.IConversationStep step2 = memory.startNextStep();
        IConversationMemory.IConversationStep step3 = memory.startNextStep();

        // test
        IConversationMemory.IConversationStepStack steps = memory.getAllSteps();

        // test
        Assert.assertSame(step3, steps.get(0));
        Assert.assertSame(step2, steps.get(1));
        Assert.assertSame(step1, steps.get(2));
    }

    //todo
    /*@Test
     public void testQueryOrderFromZeroAge() throws Exception
     {
         // setup
         IConversationMemory.IWritableConversationStep step1 = memory.getCurrentStep();
         ConversationStep step2 = memory.startNextStep();
         ConversationStep step3 = memory.startNextStep();

         // test
         IConversationMemory.IConversationStepStack steps = memory.getAllStepsFromAge(0);

         // test
         Assert.assertSame(step3, steps.get(0));
         Assert.assertSame(step2, steps.get(1));
         Assert.assertSame(step1, steps.get(2));
     }

     @Test
     public void testQueryOrderFromAge() throws Exception
     {
         // setup
         IConversationMemory.IWritableConversationStep step1 = memory.getCurrentStep();
         ConversationStep step2 = memory.startNextStep();
         ConversationStep step3 = memory.startNextStep();

         // test
         IConversationMemory.IConversationStepStack steps = memory.getAllStepsFromAge(1);

         // test
         Assert.assertSame(step2, steps.get(0));
         Assert.assertSame(step1, steps.get(1));
     } */

    @Test
    public void testStartNextStep() throws Exception {
        //setup
        IConversationMemory.IWritableConversationStep entry = memory.getCurrentStep();

        final Data data = new Data("testkey", new LinkedList());
        entry.storeData(data);

        //test
        memory.startNextStep();

        //assert
        Assert.assertTrue(memory.size() == 2);
        Assert.assertTrue(memory.getPreviousSteps().size() == 1);
        Assert.assertSame(entry, memory.getPreviousSteps().get(0));
    }

    @Test
    public void testUndo() {
        //setup
        IConversationMemory.IWritableConversationStep entry = memory.getCurrentStep();
        final Data data = new Data("testkey", new LinkedList());
        entry.storeData(data);
        memory.startNextStep();

        // test
        memory.undoLastStep();

        // assert
        Assert.assertEquals(1, memory.size());
        Assert.assertTrue(memory.isRedoAvailable());
    }

    @Test
    public void testRedo() {
        //setup
        memory.startNextStep();

        IConversationMemory.IWritableConversationStep entry = memory.getCurrentStep();
        final Data data = new Data("testkey", new LinkedList());
        entry.storeData(data);

        memory.undoLastStep();

        // test
        memory.redoLastStep();

        // assert
        Assert.assertEquals(2, memory.size());
        Assert.assertNotNull(memory.getCurrentStep().getData("testkey"));
        Assert.assertFalse(memory.isRedoAvailable());

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
//		Assert.assertEquals(lifecycleMemory1, lifecycleMemory2);
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
//		Assert.assertEquals(conversationMemory1, conversationMemory2);
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
//		Assert.assertFalse(lifecycleMemory.equals(memory.getLifecycleMemory()));
//		Assert.assertTrue(memory.getLifecycleMemory().size() == 0);
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
//		Assert.assertTrue(memory.getConversationMemory().size() == 1);
//		Assert.assertFalse(conversationHistory.equals(memory.getConversationMemory()));
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
//		Assert.assertTrue(conversationHistory.size() == 1);
//		Assert.assertTrue(lifecycleMemory.equals(memory.getLifecycleMemory()));
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
//        Assert.assertTrue(conversationHistory.size() == 1);
//        Assert.assertEquals(memory.getLifecycleMemory(), latestLifecycleStore);
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
//        Assert.assertTrue(conversationHistory.size() == 2);
//        Assert.assertNotNull(secondToLatestLifecycleStore);
//        Assert.assertTrue(memory.getLifecycleMemory().equals(secondToLatestLifecycleStore));
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
//		Assert.assertNotNull(data1);
//		Assert.assertEquals(data, data1);
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
//		Assert.assertNull(data1);
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
//		Assert.assertNotNull(lastestData);
//		Assert.assertEquals(data, lastestData);
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
//		Assert.assertNotNull(allLatestData);
//		Assert.assertTrue(allLatestData.size() == 2);
//		Assert.assertEquals(data1, allLatestData.get(0));
//		Assert.assertEquals(data2, allLatestData.get(1));
//    }
}
