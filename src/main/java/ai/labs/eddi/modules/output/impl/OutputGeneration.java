package ai.labs.eddi.modules.output.impl;

import ai.labs.eddi.modules.output.IOutputFilter;
import ai.labs.eddi.modules.output.IOutputGeneration;
import ai.labs.eddi.modules.output.model.OutputEntry;
import lombok.Getter;

import java.util.*;

/**
 * @author ginccc
 */

public class OutputGeneration implements IOutputGeneration {
    @Getter
    private final Map<String, List<OutputEntry>> outputMapper = new LinkedHashMap<>();
    @Getter
    private final String language;

    public OutputGeneration(String language) {
        this.language = language;
    }

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
