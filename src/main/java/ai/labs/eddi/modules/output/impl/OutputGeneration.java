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

            // One extraction is enough: it picks the entry set whose occurrence equals the
            // filter's, or else the highest one, and an empty result means there is no
            // entry to pick (none at all, or only legacy entries below -1, which are never
            // selectable). A loop that counted "occurred" down used to spin forever there.
            List<OutputEntry> outputEntries = extractOutputEntryOfSameOccurrence(tmpOutputEntries, filter.getOccurred());

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
