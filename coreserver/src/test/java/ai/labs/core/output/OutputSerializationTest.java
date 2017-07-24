package ai.labs.core.output;

import ai.labs.serialization.IJsonSerialization;
import ai.labs.serialization.JsonSerialization;
import com.fasterxml.jackson.databind.ObjectMapper;
import junit.framework.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 * @author ginccc
 */
public class OutputSerializationTest {

    private final String jsonTestString = "{\"key\":\"key\",\"text\":\"value\",\"occurrence\":0}";
    private IJsonSerialization jsonSerialization;

    @Before
    public void setUp() throws Exception {
        jsonSerialization = new JsonSerialization(new ObjectMapper());
    }

    @Test
    public void testSerialize() throws Exception {
        //setup
        OutputEntry outputEntry = new OutputEntry("key", "value", 0);

        //test
        String result = jsonSerialization.serialize(outputEntry);

        //assert
        Assert.assertEquals(jsonTestString, result);
    }

    @Test
    public void testDeserialize() throws Exception {
        //setup
        OutputEntry outputEntry = new OutputEntry("key", "value", 0);


        //test
        OutputEntry result = jsonSerialization.deserialize(jsonTestString, OutputEntry.class);

        //assert
        Assert.assertEquals(outputEntry, result);
    }
}
