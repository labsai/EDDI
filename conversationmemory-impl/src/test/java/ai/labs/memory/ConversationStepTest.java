package ai.labs.memory;

import ai.labs.memory.model.ConversationOutput;
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
    public void setUp() {
        conversationStep = new ConversationStep(new ConversationOutput());
    }

    @Test
    public void testPlain() {
        //assert
        Assert.assertNull(conversationStep.getData("whatever"));
        final List<IData<Void>> allData = conversationStep.getAllData("void");
        Assert.assertNotNull(allData);
        Assert.assertEquals(0, allData.size());
        Assert.assertNotNull(conversationStep.getAllElements());
        Assert.assertEquals(0, conversationStep.getAllElements().size());
        Assert.assertNotNull(conversationStep.getAllKeys());
        Assert.assertEquals(0, conversationStep.getAllKeys().size());
        Assert.assertTrue(conversationStep.isEmpty());
        Assert.assertEquals(0, conversationStep.size());
    }

    @Test
    public void testGetData() {
        //setup
        final Data data = new Data<List>("testKey", new LinkedList());
        conversationStep.storeData(data);

        //assert
        Assert.assertNotNull(conversationStep.getData("testKey"));
        Assert.assertEquals(data, conversationStep.getData("testKey"));
    }

    @Test
    public void testGetAllData() {
        //setup
        final Data data1 = new Data<List>("testKey1", new LinkedList());
        final Data data2 = new Data<List>("testKey2", new LinkedList());
        conversationStep.storeData(data1);
        conversationStep.storeData(data2);

        //test
        final List<IData<List>> allData = conversationStep.getAllData("testKey");

        //assert
        Assert.assertNotNull(allData);
        Assert.assertEquals(2, allData.size());
        Assert.assertEquals(data1, allData.get(0));
        Assert.assertEquals(data2, allData.get(1));
    }

    @Test
    public void testGetAllKeys() {
        //setup
        final Data data1 = new Data<List>("testKey1", new LinkedList());
        final Data data2 = new Data<List>("testKey2", new LinkedList());
        conversationStep.storeData(data1);
        conversationStep.storeData(data2);

        //test
        final Set<String> allKeys = conversationStep.getAllKeys();

        //assert
        Assert.assertNotNull(allKeys);
        Assert.assertEquals("testKey1", allKeys.toArray()[0]);
        Assert.assertEquals("testKey2", allKeys.toArray()[1]);

    }

    @Test
    public void testGetAllElements() {
        //setup
        final Data data1 = new Data<>("testKey1", "testData1");
        final Data data2 = new Data<>("testKey2", "testData2");
        conversationStep.storeData(data1);
        conversationStep.storeData(data2);

        //test
        final List<IData> allData = conversationStep.getAllElements();

        //assert
        Assert.assertNotNull(allData);
        Assert.assertEquals("testData1", allData.toArray(new IData[2])[0].getResult());
        Assert.assertEquals("testData2", allData.toArray(new IData[2])[1].getResult());
    }

    @Test
    public void testSize() {
        //setup
        final Data data1 = new Data<List>("testKey1", new LinkedList());
        conversationStep.storeData(data1);

        //assert
        Assert.assertEquals(1, conversationStep.size());
    }

    @Test
    public void testIsEmpty() {
        //setup
        final Data data1 = new Data<List>("testKey1", new LinkedList());
        conversationStep.storeData(data1);

        //assert
        Assert.assertFalse(conversationStep.isEmpty());
    }

    @Test
    public void testEquals() {
        //setup
        final Data data = new Data<List>("testKey", new LinkedList());
        this.conversationStep.storeData(data);
        ConversationStep conversationStep = new ConversationStep(new ConversationOutput());
        conversationStep.storeData(data);

        //assert
        Assert.assertEquals(this.conversationStep, conversationStep);
    }
}
