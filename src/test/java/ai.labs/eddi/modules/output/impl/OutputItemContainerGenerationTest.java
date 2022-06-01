package ai.labs.eddi.modules.output.impl;

import ai.labs.eddi.modules.output.IOutputFilter;
import ai.labs.eddi.modules.output.model.OutputEntry;
import ai.labs.eddi.modules.output.model.OutputItem;
import ai.labs.eddi.modules.output.model.OutputValue;
import ai.labs.eddi.modules.output.model.QuickReply;
import ai.labs.eddi.modules.output.model.types.TextOutputItem;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * @author ginccc
 */
public class OutputItemContainerGenerationTest {
    private static final String ACTION_1 = "action1";
    private static final String ACTION_2 = "action2";
    private static final String ACTION_3 = "action3";
    public static final String OUTPUT_TYPE_TEXT = "text";
    private static OutputGeneration outputGeneration;

    @BeforeEach
    public void setUp() {
        outputGeneration = new OutputGeneration("en");
    }

    @Test
    public void addOutputEntry() {
        //setup
        List<OutputEntry> outputEntries = setupOutputEntries();

        //test
        outputEntries.forEach(outputEntry -> outputGeneration.addOutputEntry(outputEntry));

        //assert
        Assertions.assertEquals(3, outputGeneration.getOutputMapper().keySet().size());
        Assertions.assertEquals(2, outputGeneration.getOutputMapper().get(ACTION_3).get(0).getOccurred());
        Assertions.assertEquals(4, outputGeneration.getOutputMapper().get(ACTION_3).get(1).getOccurred());
    }

    @Test
    public void getOutputs() {
        //setup
        List<OutputEntry> outputEntries = setupOutputEntries();
        outputEntries.forEach(outputEntry -> outputGeneration.addOutputEntry(outputEntry));
        LinkedList<IOutputFilter> outputFilter = new LinkedList<>();

        //test
        outputFilter.add(new OutputFilter(ACTION_3, 2));
        Map<String, List<OutputEntry>> outputs = outputGeneration.getOutputs(outputFilter);

        //assert
        Assertions.assertEquals(1, outputs.keySet().size());
        Assertions.assertEquals(2, outputs.get(ACTION_3).get(0).getOccurred());
    }

    @Test
    public void extractOutputEntryOfSameOccurrence_Occurred_0() {
        //setup
        List<OutputEntry> outputEntries = setupOutputEntries();

        //test
        List<OutputEntry> actual = outputGeneration.extractOutputEntryOfSameOccurrence(outputEntries, 0);

        //assert
        Assertions.assertEquals(ACTION_1, actual.get(0).getAction());
    }

    @Test
    public void extractOutputEntryOfSameOccurrence_Occurred_1() {
        //setup
        List<OutputEntry> outputEntries = setupOutputEntries();

        //test
        List<OutputEntry> actual = outputGeneration.extractOutputEntryOfSameOccurrence(outputEntries, 1);

        //assert
        Assertions.assertEquals(ACTION_2, actual.get(0).getAction());
    }

    @Test
    public void extractOutputEntryOfSameOccurrence_Occurred_2() {
        //setup
        List<OutputEntry> outputEntries = setupOutputEntries();

        //test
        List<OutputEntry> actual = outputGeneration.extractOutputEntryOfSameOccurrence(outputEntries, 2);

        //assert
        Assertions.assertEquals(ACTION_3, actual.get(0).getAction());
    }

    @Test
    public void extractOutputEntryOfSameOccurrence_Occurred_3() {
        //setup
        List<OutputEntry> outputEntries = setupOutputEntries();

        //test
        List<OutputEntry> actual = outputGeneration.extractOutputEntryOfSameOccurrence(outputEntries, 3);

        //assert
        Assertions.assertEquals(ACTION_3, actual.get(0).getAction());
    }

    private List<OutputEntry> setupOutputEntries() {
        List<OutputEntry> outputEntries = new LinkedList<>();
        List<OutputItem> valueAlternatives1 = Arrays.asList(new TextOutputItem("Text1 Alternative 1"), new TextOutputItem("Text1 Alternative 2"));
        List<OutputItem> valueAlternatives2 = Arrays.asList(new TextOutputItem("Text2 Alternative 1"), new TextOutputItem("Text2 Alternative 2"));
        List<OutputValue> outputValues = Arrays.asList(new OutputValue(valueAlternatives1), new OutputValue(valueAlternatives2));
        List<QuickReply> quickReply = Arrays.asList(new QuickReply("Some QuickReply", "some(Expression)", false),
                new QuickReply("Some Other QuickReply", "someOther(Expression", false));

        outputEntries.add(new OutputEntry(ACTION_3, 4, outputValues, quickReply));
        outputEntries.add(new OutputEntry(ACTION_3, 2, outputValues, quickReply));
        outputEntries.add(new OutputEntry(ACTION_2, 1, outputValues, quickReply));
        outputEntries.add(new OutputEntry(ACTION_1, 0, outputValues, quickReply));
        outputEntries.add(new OutputEntry(ACTION_3, 2, outputValues, quickReply));
        return outputEntries;
    }
}