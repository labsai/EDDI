package ai.labs.core.output;

import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

/**
 * @author ginccc
 */
public class SimpleOutputTest {
    @Test
    public void testGetOutputOccurrence0() throws Exception {
        //setup
        SimpleOutput simpleOutput = new SimpleOutput();
        simpleOutput.addOutputEntry(new OutputEntry("greeting", "Hallo!", 0));

        //test
        List<List<OutputEntry>> outputs = simpleOutput.getOutputs(Arrays.asList(new IOutputFilter[]{new OutputFilter("greeting", 0)}));

        //assert
        Assert.assertEquals("Hallo!", outputs.get(0).get(0).getText());
    }

    @Test
    public void testGetOutputOccurrence0_1() throws Exception {
        //setup
        SimpleOutput simpleOutput = new SimpleOutput();
        simpleOutput.addOutputEntry(new OutputEntry("greeting", "Hallo!", 0));
        simpleOutput.addOutputEntry(new OutputEntry("greeting", "Hallo", 0));

        //test
        List<List<OutputEntry>> outputs = simpleOutput.getOutputs(Arrays.asList(new IOutputFilter[]{new OutputFilter("greeting", 0)}));

        //assert
        Assert.assertEquals("Hallo!", outputs.get(0).get(0).getText());
        Assert.assertEquals("Hallo", outputs.get(0).get(1).getText());
    }

    @Test
    public void testGetOutputOccurrence1() throws Exception {
        //setup
        SimpleOutput simpleOutput = new SimpleOutput();
        simpleOutput.addOutputEntry(new OutputEntry("greeting", "Hallo!", 0));
        simpleOutput.addOutputEntry(new OutputEntry("greeting", "Second Hello!", 1));

        //test
        List<List<OutputEntry>> outputs1 = simpleOutput.getOutputs(Arrays.asList(new IOutputFilter[]{new OutputFilter("greeting", 0)}));
        List<List<OutputEntry>> outputs2 = simpleOutput.getOutputs(Arrays.asList(new IOutputFilter[]{new OutputFilter("greeting", 1)}));

        //assert
        Assert.assertEquals("Hallo!", outputs1.get(0).get(0).getText());
        Assert.assertEquals("Second Hello!", outputs2.get(0).get(0).getText());
    }
}
