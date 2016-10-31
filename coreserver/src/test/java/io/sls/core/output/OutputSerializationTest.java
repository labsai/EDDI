package io.sls.core.output;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.sls.serialization.IJsonSerialization;
import io.sls.serialization.JsonSerialization;
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
