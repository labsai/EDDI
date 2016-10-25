package io.sls.core.output;

import io.sls.serialization.JSONSerialization;
import junit.framework.Assert;
import org.codehaus.jackson.type.TypeReference;
import org.junit.Test;

import java.util.LinkedList;
import java.util.List;

/**
 * @author ginccc
 */
public class OutputSerializationTest {

    private final String jsonTestString = "[{\"key\":\"key1\",\"text\":\"text1\",\"occurrence\":0},{\"key\":\"key2\",\"text\":\"text2\",\"occurrence\":1},{\"key\":\"key3\",\"text\":\"text3\",\"occurrence\":2}]";

    @Test
    public void testSerialize() throws Exception {
        //setup
        List<OutputEntry> outputEntries = new LinkedList<OutputEntry>();
        outputEntries.add(new OutputEntry("key1", "text1", 0));
        outputEntries.add(new OutputEntry("key2", "text2", 1));
        outputEntries.add(new OutputEntry("key3", "text3", 2));

        //test
        String result = JSONSerialization.serialize(outputEntries, false);

        //assert
        Assert.assertEquals(jsonTestString, result);
    }

    @Test
    public void testDeserialize() throws Exception {
        //setup
        List<OutputEntry> outputEntries = new LinkedList<OutputEntry>();
        outputEntries.add(new OutputEntry("key1", "text1", 0));
        outputEntries.add(new OutputEntry("key2", "text2", 1));
        outputEntries.add(new OutputEntry("key3", "text3", 2));

        //test
        List<OutputEntry> result = JSONSerialization.deserialize(jsonTestString, new TypeReference<List<OutputEntry>>() {
        });

        //assert
        Assert.assertEquals(outputEntries, result);
    }
}
