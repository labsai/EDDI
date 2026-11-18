/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.output.impl;

import ai.labs.eddi.modules.output.IOutputFilter;
import ai.labs.eddi.modules.output.IOutputGeneration;
import ai.labs.eddi.modules.output.model.OutputEntry;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * @author ginccc
 */

public class OutputGeneration implements IOutputGeneration {
    private final Map<String, List<OutputEntry>> outputMapper = new LinkedHashMap<>();
    private final String language;
    private final AtomicBoolean languageMismatchLogged = new AtomicBoolean();

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
            // Stops when nothing is left to find: extracting removes every entry that is
            // not the one it picks, so an empty candidate list can never yield a result.
            // The loop used to spin forever for an action whose entries all had a
            // timesOccurred below -1 (or none at all), because the result stayed empty
            // however far "occurred" counted down.
            do {
                outputEntries = extractOutputEntryOfSameOccurrence(tmpOutputEntries, occurred);
                occurred--;
            } while (outputEntries.isEmpty() && !tmpOutputEntries.isEmpty() && occurred > Integer.MIN_VALUE);

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

        for (int i = 0; i < outputEntries.size();) {
            OutputEntry outputEntry = outputEntries.get(i);
            if (outputEntry.getOccurred() != highestOccurrence) {
                outputEntries.remove(outputEntry);
            } else {
                i++;
            }
        }

        return outputEntries;
    }

    /**
     * @return true exactly once: the first time an output set found its language
     *         not matching the conversation's, so that the task logs it once per
     *         deployed configuration instead of once per turn
     */
    @Override
    public boolean claimLanguageMismatchLog() {
        return languageMismatchLogged.compareAndSet(false, true);
    }

    public Map<String, List<OutputEntry>> getOutputMapper() {
        return outputMapper;
    }

    public String getLanguage() {
        return language;
    }
}
