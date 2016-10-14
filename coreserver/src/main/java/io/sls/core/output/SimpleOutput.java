package io.sls.core.output;

import java.util.*;

/**
 * Spoken Language System. Core.
 * User: jarisch
 * Date: 22.02.12
 * Time: 20:27
 */
public class SimpleOutput {
    private Map<String, List<OutputEntry>> outputMapper = new HashMap<String, List<OutputEntry>>();

    public void addOutputEntry(OutputEntry outputEntry) {
        String key = outputEntry.getKey();
        if (!outputMapper.containsKey(key)) {
            outputMapper.put(key, new LinkedList<OutputEntry>());
        }

        List<OutputEntry> tmpOutputEntries = outputMapper.get(key);
        if (!tmpOutputEntries.contains(outputEntry)) {
            tmpOutputEntries.add(outputEntry);
            Collections.sort(tmpOutputEntries);
        }
    }

    public List<List<OutputEntry>> getOutputs(List<IOutputFilter> outputFilter) {
        List<List<OutputEntry>> outputs = new LinkedList<List<OutputEntry>>();

        for (IOutputFilter filter : outputFilter) {
            List<OutputEntry> entryList = outputMapper.get(filter.getKey());
            if (entryList == null) {
                continue;
            }

            List<OutputEntry> tmpOutputEntries = new LinkedList<OutputEntry>(entryList);

            List<OutputEntry> outputEntries;
            int occurrence = filter.getOccurence();
            do {
                outputEntries = extractOutputEntryOfSameOccurrence(tmpOutputEntries, occurrence);
                occurrence--;
            } while (outputEntries.isEmpty());

            outputs.add(outputEntries);
        }

        return outputs;
    }

    public List<String> convert(List<OutputEntry> outputEntries) {
        List<String> ret = new LinkedList<String>();
        for (OutputEntry outputEntry : outputEntries) {
            ret.add(outputEntry.getText());
        }

        return ret;
    }

    private List<OutputEntry> extractOutputEntryOfSameOccurrence(List<OutputEntry> outputEntries, int occurrence) {
        int highestOccurrence = -1;
        for (OutputEntry outputEntry : outputEntries) {
            if (highestOccurrence < outputEntry.getOccurrence()) {
                highestOccurrence = outputEntry.getOccurrence();
            }

            if (outputEntry.getOccurrence() == occurrence) {
                highestOccurrence = occurrence;
                break;
            }
        }

        for (int i = 0; i < outputEntries.size(); ) {
            OutputEntry outputEntry = outputEntries.get(i);
            if (outputEntry.getOccurrence() != highestOccurrence)
                outputEntries.remove(outputEntry);
            else
                i++;
        }

        return outputEntries;
    }
}
