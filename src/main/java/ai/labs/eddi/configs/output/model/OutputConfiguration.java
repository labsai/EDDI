/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.output.model;

import ai.labs.eddi.modules.output.model.OutputItem;
import ai.labs.eddi.modules.output.model.QuickReply;

import java.util.LinkedList;
import java.util.List;

/**
 * @author ginccc
 */

public class OutputConfiguration {
    private String action;
    private int timesOccurred;
    private List<Output> outputs = new LinkedList<>();
    private List<QuickReply> quickReplies = new LinkedList<>();

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;

        OutputConfiguration that = (OutputConfiguration) o;

        return action.equals(that.action) && timesOccurred == that.timesOccurred;
    }

    @Override
    public int hashCode() {
        int result = action.hashCode();
        result = 31 * result + timesOccurred;
        return result;
    }

    public static class Output {
        private List<OutputItem> valueAlternatives = new LinkedList<>();

        public List<OutputItem> getValueAlternatives() {
            return valueAlternatives;
        }

        public void setValueAlternatives(List<OutputItem> valueAlternatives) {
            this.valueAlternatives = valueAlternatives;
        }
    }

    public OutputConfiguration() {
    }

    public OutputConfiguration(String action, int timesOccurred, List<Output> outputs, List<QuickReply> quickReplies) {
        this.action = action;
        this.timesOccurred = timesOccurred;
        this.outputs = outputs;
        this.quickReplies = quickReplies;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public int getTimesOccurred() {
        return timesOccurred;
    }

    public void setTimesOccurred(int timesOccurred) {
        this.timesOccurred = timesOccurred;
    }

    public List<Output> getOutputs() {
        return outputs;
    }

    public void setOutputs(List<Output> outputs) {
        this.outputs = outputs;
    }

    public List<QuickReply> getQuickReplies() {
        return quickReplies;
    }

    public void setQuickReplies(List<QuickReply> quickReplies) {
        this.quickReplies = quickReplies;
    }
}
