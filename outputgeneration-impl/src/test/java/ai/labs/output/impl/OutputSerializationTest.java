package ai.labs.output.impl;

import ai.labs.resources.rest.output.model.OutputConfiguration;
import ai.labs.resources.rest.output.model.OutputConfigurationSet;
import ai.labs.serialization.IJsonSerialization;
import ai.labs.serialization.JsonSerialization;
import com.fasterxml.jackson.databind.ObjectMapper;
import junit.framework.Assert;
import org.junit.Before;
import org.junit.Test;

import java.util.Collections;

/**
 * @author ginccc
 */
public class OutputSerializationTest {

    private final String jsonTestString = "{\"outputs\":[{\"key\":\"key\",\"outputValues\":[\"value\"],\"quickReplies\":[{\"value\":\"quickReply\",\"expressions\":\"exp(quickReply)\"}],\"occurrence\":0}]}";
    private IJsonSerialization jsonSerialization;
    private OutputConfigurationSet outputConfigurationSet;

    @Before
    public void setUp() throws Exception {
        jsonSerialization = new JsonSerialization(new ObjectMapper());

        //setup test data
        outputConfigurationSet = new OutputConfigurationSet();
        OutputConfiguration.QuickReply quickReply = new OutputConfiguration.QuickReply();
        quickReply.setValue("quickReply");
        quickReply.setExpressions("exp(quickReply)");
        OutputConfiguration outputConfiguration = new OutputConfiguration("key", 0,
                Collections.singletonList("value"), Collections.singletonList(quickReply));
        outputConfigurationSet.setOutputs(Collections.singletonList(outputConfiguration));
    }

    @Test
    public void testSerialize() throws Exception {
        //test
        String result = jsonSerialization.serialize(outputConfigurationSet);

        //assert
        Assert.assertEquals(jsonTestString, result);
    }

    @Test
    public void testDeserialize() throws Exception {
        //test
        OutputConfigurationSet result = jsonSerialization.deserialize(jsonTestString, OutputConfigurationSet.class);

        //assert
        Assert.assertEquals(outputConfigurationSet, result);
    }
}
