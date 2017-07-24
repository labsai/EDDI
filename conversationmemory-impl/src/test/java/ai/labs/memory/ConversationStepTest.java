package ai.labs.memory;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.util.LinkedList;
import java.util.List;
import java.util.Set;

/**
 * @author ginccc
 */
public class ConversationStepTest {
    private IConversationMemory.IWritableConversationStep conversationStep;

    @Before
    public void setUp() throws Exception {
        conversationStep = new ConversationStep(new ConversationMemory.ConversationContext());
    }

    @After
    public void tearDown() throws Exception {
    }

    @Test
    public void testPlain() throws Exception {
        //assert
        Assert.assertNull(conversationStep.getData("whatever"));
        final List<IData> allData = conversationStep.getAllData("asdf");
        Assert.assertNotNull(allData);
        Assert.assertTrue(allData.size() == 0);
        Assert.assertNotNull(conversationStep.getAllElements(conversationStep.getCurrentConversationContext()));
        Assert.assertTrue(conversationStep.getAllElements(conversationStep.getCurrentConversationContext()).size() == 0);
        Assert.assertNotNull(conversationStep.getAllKeys());
        Assert.assertTrue(conversationStep.getAllKeys().size() == 0);
        Assert.assertTrue(conversationStep.isEmpty());
        Assert.assertTrue(conversationStep.size() == 0);
    }

    @Test
    public void testGetData() throws Exception {
        //setup
        final Data data = new Data("testkey", new LinkedList());
        conversationStep.storeData(data);

        //assert
        Assert.assertNotNull(conversationStep.getData("testkey"));
        Assert.assertEquals(data, conversationStep.getData("testkey"));
    }

    @Test
    public void testGetAllData() throws Exception {
        //setup
        final Data data1 = new Data("testkey1", new LinkedList());
        final Data data2 = new Data("testkey2", new LinkedList());
        conversationStep.storeData(data1);
        conversationStep.storeData(data2);

        //test
        final List<IData> allData = conversationStep.getAllData("testkey");

        //assert
        Assert.assertNotNull(allData);
        Assert.assertEquals(2, allData.size());
        Assert.assertEquals(data1, allData.get(0));
        Assert.assertEquals(data2, allData.get(1));
    }

    @Test
    public void testGetAllKeys() throws Exception {
        //setup
        final Data data1 = new Data("testkey1", new LinkedList());
        final Data data2 = new Data("testkey2", new LinkedList());
        conversationStep.storeData(data1);
        conversationStep.storeData(data2);

        //test
        final Set<String> allKeys = conversationStep.getAllKeys();

        //assert
        Assert.assertNotNull(allKeys);
        Assert.assertEquals("testkey1", allKeys.toArray()[0]);
        Assert.assertEquals("testkey2", allKeys.toArray()[1]);

    }

    @Test
    public void testGetAllElements() throws Exception {
        //setup
        final Data data1 = new Data("testkey1", "testData1");
        final Data data2 = new Data("testkey2", "testData2");
        conversationStep.storeData(data1);
        conversationStep.storeData(data2);

        //test
        final List<IData> allData = conversationStep.getAllElements(conversationStep.getCurrentConversationContext());

        //assert
        Assert.assertNotNull(allData);
        Assert.assertEquals("testData1", allData.toArray(new IData[2])[0].getResult());
        Assert.assertEquals("testData2", allData.toArray(new IData[2])[1].getResult());
    }

    @Test
    public void testSize() throws Exception {
        //setup
        final Data data1 = new Data("testkey1", new LinkedList());
        conversationStep.storeData(data1);

        //assert
        Assert.assertTrue(conversationStep.size() == 1);
    }

    @Test
    public void testIsEmpty() throws Exception {
        //setup
        final Data data1 = new Data("testkey1", new LinkedList());
        conversationStep.storeData(data1);

        //assert
        Assert.assertFalse(conversationStep.isEmpty());
    }

    @Test
    public void testEquals() throws Exception {
        //setup
        final Data data = new Data("testkey", new LinkedList());
        this.conversationStep.storeData(data);
        ConversationStep conversationStep = new ConversationStep(new ConversationMemory.ConversationContext());
        conversationStep.storeData(data);

        //assert
        Assert.assertEquals(this.conversationStep, conversationStep);
    }
}
