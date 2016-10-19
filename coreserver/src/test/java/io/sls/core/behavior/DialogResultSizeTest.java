package io.sls.core.behavior;

/**
 * User: jarisch
 * Date: 03.12.2011
 * Time: 09:56:44
 */
public class DialogResultSizeTest {
    /*
     todo
     private ResultSize resultSize;
     private IMemory memory;

     @Before
     public void setup()
     {
         //setup
         memory = new Memory();
         resultSize = new ResultSize(memory);
     }

     @Test
     public void testDefault()
     {
         //dummy to prevent default db fetch from product db (and therefore its initialization)
     //todo
         // 	memory.getConversationMemory().addCurrentResult(new LinkedList());

         resultSize.execute(new LinkedList());
         Assert.assertTrue(resultSize.getExecutionState() == IExtension.ExecutionState.NOT_EXECUTED);
     }

     @Test
     public void testMin()
     {
         //setup
         *//*
		todo
		resultSize.setMin(5);

		memory.getConversationMemory().addCurrentResult(Arrays.asList(1000L, 1001L, 1002L, 1003L)); //dummy list
		resultSize.execute(new LinkedList());
		Assert.assertTrue(resultSize.getExecutionState() == IExtension.ExecutionState.FAIL);

		memory.getConversationMemory().addCurrentResult(Arrays.asList(1000L, 1001L, 1002L, 1003L, 1004L, 1005L)); //dummy list
		resultSize.execute(new LinkedList());
		Assert.assertTrue(resultSize.getExecutionState() == IExtension.ExecutionState.SUCCESS);*//*
	}

	@Test
	public void testMax()
	{
		//setup
		resultSize.setMax(5);

		memory.getConversationMemory().addCurrentResult(Arrays.asList(1000L, 1001L, 1002L, 1003L)); //dummy list
		resultSize.execute(new LinkedList());
		Assert.assertTrue(resultSize.getExecutionState() == IExtension.ExecutionState.SUCCESS);

		memory.getConversationMemory().addCurrentResult(Arrays.asList(1000L, 1001L, 1002L, 1003L, 1004L, 1005L)); //dummy list
		resultSize.execute(new LinkedList());
		Assert.assertTrue(resultSize.getExecutionState() == IExtension.ExecutionState.FAIL);
	}

	@Test
	public void testEqual()
	{
		//setup
		resultSize.setEqual(5);

		memory.getConversationMemory().addCurrentResult(Arrays.asList(1000L, 1001L, 1002L, 1003L)); //dummy list
		resultSize.execute(new LinkedList());
		Assert.assertTrue(resultSize.getExecutionState() == IExtension.ExecutionState.FAIL);

		memory.getConversationMemory().addCurrentResult(Arrays.asList(1000L, 1001L, 1002L, 1003L, 1004L, 1005L)); //dummy list
		resultSize.execute(new LinkedList());
		Assert.assertTrue(resultSize.getExecutionState() == IExtension.ExecutionState.FAIL);

		memory.getConversationMemory().addCurrentResult(Arrays.asList(1000L, 1001L, 1002L, 1003L, 1004L)); //dummy list
		resultSize.execute(new LinkedList());
		Assert.assertTrue(resultSize.getExecutionState() == IExtension.ExecutionState.SUCCESS);
	}

	@Test
	public void testCombinationMinMax()
	{
		//setup
		resultSize.setMin(2);
		resultSize.setMax(5);

		// test for correct fail
		memory.getConversationMemory().addCurrentResult(Arrays.asList(1000L)); //dummy list
		resultSize.execute(new LinkedList());
		Assert.assertTrue(resultSize.getExecutionState() == IExtension.ExecutionState.FAIL);

		memory.getConversationMemory().addCurrentResult(Arrays.asList(1000L, 1001L, 1002L, 1003L, 1004L, 1005L)); //dummy list
		resultSize.execute(new LinkedList());
		Assert.assertTrue(resultSize.getExecutionState() == IExtension.ExecutionState.FAIL);

		//test for correct success
		memory.getConversationMemory().addCurrentResult(Arrays.asList(1000L, 1001L)); //dummy list
		resultSize.execute(new LinkedList());
		Assert.assertTrue(resultSize.getExecutionState() == IExtension.ExecutionState.SUCCESS);

		memory.getConversationMemory().addCurrentResult(Arrays.asList(1000L, 1001L, 1002L, 1003L, 1004L)); //dummy list
		resultSize.execute(new LinkedList());
		Assert.assertTrue(resultSize.getExecutionState() == IExtension.ExecutionState.SUCCESS);
	}

	@Test
	public void testCombinationMinMaxEqual()
	{
		//setup
		resultSize.setMin(2);
		resultSize.setMax(5);
		resultSize.setEqual(4);

		memory.getConversationMemory().addCurrentResult(Arrays.asList(1000L, 1001L)); //dummy list
		resultSize.execute(new LinkedList());
		Assert.assertTrue(resultSize.getExecutionState() == IExtension.ExecutionState.FAIL);

		memory.getConversationMemory().addCurrentResult(Arrays.asList(1000L, 1001L, 1002L, 1003L, 1004L)); //dummy list
		resultSize.execute(new LinkedList());
		Assert.assertTrue(resultSize.getExecutionState() == IExtension.ExecutionState.FAIL);

		memory.getConversationMemory().addCurrentResult(Arrays.asList(1000L, 1001L, 1002L, 1003L)); //dummy list
		resultSize.execute(new LinkedList());
		Assert.assertTrue(resultSize.getExecutionState() == IExtension.ExecutionState.SUCCESS);
	}*/
}

