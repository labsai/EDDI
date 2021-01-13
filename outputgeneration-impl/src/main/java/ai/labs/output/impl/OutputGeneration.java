package ai.labs.output.impl;

import ai.labs.output.IOutputFilter;
import ai.labs.output.IOutputGeneration;
import ai.labs.output.model.OutputEntry;
import lombok.Getter;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * @author ginccc
 */
public class OutputGeneration implements IOutputGeneration {
    @Getter
    private Map<String, List<OutputEntry>> outputMapper = new LinkedHashMap<>();

    @Override
    public void addOutputEntry(OutputEntry outputEntry) {
        String action = outputEntry.getAction();
        if (!outputMapper.containsKey(action)) {
            outputMapper.put(action, new LinkedList<>());
        }

        List<OutputEntry> tmpOutputEntries = outputMapper.get(action);
        if (!tmpOutputEntries.contains(outputEntry)) {
            tmpOutputEntries.add(outputEntry);
            Collections.sort(tmpOutputEntries);
        }
    }

    @Override
    public Map<String, List<OutputEntry>> getOutputs(List<IOutputFilter> outputFilter) {
        Map<String, List<OutputEntry>> outputs = new LinkedHashMap<>();

        for (IOutputFilter filter : outputFilter) {
            List<OutputEntry> entryList = outputMapper.get(filter.getAction());
            if (entryList == null) {
                continue;
            }

            List<OutputEntry> tmpOutputEntries = new LinkedList<>(entryList);

            List<OutputEntry> outputEntries;
            int occurred = filter.getOccurred();
            do {
                outputEntries = extractOutputEntryOfSameOccurrence(tmpOutputEntries, occurred);
                occurred--;
            } while (outputEntries.isEmpty());

            outputs.put(filter.getAction(), outputEntries);
        }

        return outputs;
    }

    List<OutputEntry> extractOutputEntryOfSameOccurrence(List<OutputEntry> outputEntries, int occurred) {
        int highestOccurrence = -1;
        for (OutputEntry outputEntry : outputEntries) {
            if (highestOccurrence < outputEntry.getOccurred()) {
                highestOccurrence = outputEntry.getOccurred();
            }

            if (outputEntry.getOccurred() == occurred) {
                highestOccurrence = occurred;
                break;
            }
        }

        for (int i = 0; i < outputEntries.size(); ) {
            OutputEntry outputEntry = outputEntries.get(i);
            if (outputEntry.getOccurred() != highestOccurrence) {
                outputEntries.remove(outputEntry);
            } else {
                i++;
            }
        }

        return outputEntries;
    }
}
