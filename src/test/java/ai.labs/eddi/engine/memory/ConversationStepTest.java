package ai.labs.eddi.engine.memory;

import ai.labs.eddi.engine.memory.model.ConversationOutput;
import ai.labs.eddi.engine.memory.model.Data;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedList;
import java.util.List;
import java.util.Set;

/**
 * @author ginccc
 */
public class ConversationStepTest {
    private static IConversationMemory.IWritableConversationStep conversationStep;

    @BeforeEach
    public void setUp() {
        conversationStep = new ConversationStep(new ConversationOutput());
    }

    @Test
    public void testPlain() {
        //assert
        Assertions.assertNull(conversationStep.getData("whatever"));
        final List<IData<Void>> allData = conversationStep.getAllData("void");
        Assertions.assertNotNull(allData);
        Assertions.assertEquals(0, allData.size());
        Assertions.assertNotNull(conversationStep.getAllElements());
        Assertions.assertEquals(0, conversationStep.getAllElements().size());
        Assertions.assertNotNull(conversationStep.getAllKeys());
        Assertions.assertEquals(0, conversationStep.getAllKeys().size());
        Assertions.assertTrue(conversationStep.isEmpty());
        Assertions.assertEquals(0, conversationStep.size());
    }

    @Test
    public void testGetData() {
        //setup
        final var data = new Data<>("testKey", new LinkedList<>());
        conversationStep.storeData(data);

        //assert
        Assertions.assertNotNull(conversationStep.getData("testKey"));
        Assertions.assertEquals(data, conversationStep.getData("testKey"));
    }

    @Test
    public void testGetAllData() {
        //setup
        final var data1 = new Data<>("testKey1", new LinkedList<>());
        final var data2 = new Data<>("testKey2", new LinkedList<>());
        conversationStep.storeData(data1);
        conversationStep.storeData(data2);

        //test
        final var allData = conversationStep.getAllData("testKey");

        //assert
        Assertions.assertNotNull(allData);
        Assertions.assertEquals(2, allData.size());
        Assertions.assertEquals(data1, allData.get(0));
        Assertions.assertEquals(data2, allData.get(1));
    }

    @Test
    public void testGetAllKeys() {
        //setup
        final var data1 = new Data<>("testKey1", new LinkedList<>());
        final var data2 = new Data<>("testKey2", new LinkedList<>());
        conversationStep.storeData(data1);
        conversationStep.storeData(data2);

        //test
        final Set<String> allKeys = conversationStep.getAllKeys();

        //assert
        Assertions.assertNotNull(allKeys);
        Assertions.assertEquals("testKey1", allKeys.toArray()[0]);
        Assertions.assertEquals("testKey2", allKeys.toArray()[1]);

    }

    @Test
    public void testGetAllElements() {
        //setup
        final var data1 = new Data<>("testKey1", "testData1");
        final var data2 = new Data<>("testKey2", "testData2");
        conversationStep.storeData(data1);
        conversationStep.storeData(data2);

        //test
        final var allData = conversationStep.getAllElements();

        //assert
        Assertions.assertNotNull(allData);
        Assertions.assertEquals("testData1", allData.toArray(new IData[2])[0].getResult());
        Assertions.assertEquals("testData2", allData.toArray(new IData[2])[1].getResult());
    }

    @Test
    public void testSize() {
        //setup
        final var data1 = new Data<>("testKey1", new LinkedList<>());
        conversationStep.storeData(data1);

        //assert
        Assertions.assertEquals(1, conversationStep.size());
    }

    @Test
    public void testIsEmpty() {
        //setup
        final var data1 = new Data<>("testKey1", new LinkedList<>());
        conversationStep.storeData(data1);

        //assert
        Assertions.assertFalse(conversationStep.isEmpty());
    }

    @Test
    public void testEquals() {
        //setup
        final var data = new Data<>("testKey", new LinkedList<>());
        conversationStep.storeData(data);
        ConversationStep conversationStep = new ConversationStep(new ConversationOutput());
        conversationStep.storeData(data);

        //assert
        Assertions.assertEquals(conversationStep, conversationStep);
    }
}
