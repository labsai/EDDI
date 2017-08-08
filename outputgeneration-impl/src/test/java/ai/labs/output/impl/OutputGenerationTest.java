package ai.labs.output.impl;

import ai.labs.output.IOutputFilter;
import ai.labs.output.model.OutputEntry;
import ai.labs.output.model.OutputValue;
import ai.labs.output.model.QuickReply;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * @author ginccc
 */
public class OutputGenerationTest {

    private OutputGeneration outputGeneration;

    @Before
    public void setUp() throws Exception {
        outputGeneration = new OutputGeneration();
    }

    @Test
    public void addOutputEntry() throws Exception {
        //setup
        List<OutputEntry> outputEntries = setupOutputEntries();

        //test
        outputEntries.forEach(outputEntry -> outputGeneration.addOutputEntry(outputEntry));

        //assert
        Assert.assertEquals(3, outputGeneration.getOutputMapper().keySet().size());
        Assert.assertEquals(2, outputGeneration.getOutputMapper().get("action3").get(0).getOccurred());
        Assert.assertEquals(4, outputGeneration.getOutputMapper().get("action3").get(1).getOccurred());
    }

    @Test
    public void getOutputs() throws Exception {
        //setup
        List<OutputEntry> outputEntries = setupOutputEntries();
        outputEntries.forEach(outputEntry -> outputGeneration.addOutputEntry(outputEntry));
        LinkedList<IOutputFilter> outputFilter = new LinkedList<>();

        //test
        outputFilter.add(new OutputFilter("action3", 2));
        Map<String, List<OutputEntry>> outputs = outputGeneration.getOutputs(outputFilter);

        //assert
        Assert.assertEquals(1, outputs.keySet().size());
        Assert.assertEquals(2, outputs.get("action3").get(0).getOccurred());
    }

    @Test
    public void extractOutputEntryOfSameOccurrence_Occured_0() throws Exception {
        //setup
        List<OutputEntry> outputEntries = setupOutputEntries();

        //test
        List<OutputEntry> actual = outputGeneration.extractOutputEntryOfSameOccurrence(outputEntries, 0);

        //assert
        Assert.assertEquals("action1", actual.get(0).getAction());
    }

    @Test
    public void extractOutputEntryOfSameOccurrence_Occured_1() throws Exception {
        //setup
        List<OutputEntry> outputEntries = setupOutputEntries();

        //test
        List<OutputEntry> actual = outputGeneration.extractOutputEntryOfSameOccurrence(outputEntries, 1);

        //assert
        Assert.assertEquals("action2", actual.get(0).getAction());
    }

    @Test
    public void extractOutputEntryOfSameOccurrence_Occured_2() throws Exception {
        //setup
        List<OutputEntry> outputEntries = setupOutputEntries();

        //test
        List<OutputEntry> actual = outputGeneration.extractOutputEntryOfSameOccurrence(outputEntries, 2);

        //assert
        Assert.assertEquals("action3", actual.get(0).getAction());
    }

    @Test
    public void extractOutputEntryOfSameOccurrence_Occured_3() throws Exception {
        //setup
        List<OutputEntry> outputEntries = setupOutputEntries();

        //test
        List<OutputEntry> actual = outputGeneration.extractOutputEntryOfSameOccurrence(outputEntries, 3);

        //assert
        Assert.assertEquals("action3", actual.get(0).getAction());
    }

    private List<OutputEntry> setupOutputEntries() {
        List<OutputEntry> outputEntries = new LinkedList<>();
        List<String> valueAlternatives1 = Arrays.asList("Text1 Alternative 1", "Text1 Alternative 2");
        List<String> valueAlternatives2 = Arrays.asList("Text2 Alternative 1", "Text2 Alternative 2");
        List<OutputValue> outputValues = Arrays.asList(new OutputValue(OutputValue.Type.text, valueAlternatives1),
                new OutputValue(OutputValue.Type.text, valueAlternatives2));
        List<QuickReply> quickReply = Arrays.asList(new QuickReply("Some QuickReply", "some(Expression)"),
                new QuickReply("Some Other QuickReply", "someOther(Expression"));

        outputEntries.add(new OutputEntry("action3", 4, outputValues, quickReply));
        outputEntries.add(new OutputEntry("action3", 2, outputValues, quickReply));
        outputEntries.add(new OutputEntry("action2", 1, outputValues, quickReply));
        outputEntries.add(new OutputEntry("action1", 0, outputValues, quickReply));
        outputEntries.add(new OutputEntry("action3", 2, outputValues, quickReply));
        return outputEntries;
    }
}