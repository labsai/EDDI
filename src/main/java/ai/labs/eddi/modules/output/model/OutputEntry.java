/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.output.model;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * @author ginccc
 */

public class OutputEntry implements Comparable<OutputEntry> {
    private static final Comparator<String> ACTION_COMPARATOR = Comparator.nullsFirst(Comparator.naturalOrder());

    private String action;
    private int occurred;
    private List<OutputValue> outputs;
    private List<QuickReply> quickReplies;

    /**
     * Orders primarily by {@code occurred}, which is what the output pipeline sorts
     * on. The remaining steps exist so the ordering stays consistent with
     * {@link #equals(Object)}: two entries only compare equal when they really are
     * equal, otherwise a {@code TreeSet}/{@code TreeMap} would silently drop
     * distinct entries that merely share the same {@code occurred} value.
     */
    @Override
    public int compareTo(OutputEntry o) {
        int result = Integer.compare(occurred, o.occurred);
        if (result != 0) {
            return result;
        }

        result = ACTION_COMPARATOR.compare(action, o.action);
        if (result != 0) {
            return result;
        }

        if (equals(o)) {
            return 0;
        }

        // Same action and occurrence but different outputs / quick replies: the
        // outputs are not themselves comparable, so fall back to a stable,
        // equals-consistent discriminator instead of reporting equality.
        return Integer.compare(hashCode(), o.hashCode());
    }

    public OutputEntry(String action, int occurred, List<OutputValue> outputs, List<QuickReply> quickReplies) {
        this.action = action;
        this.occurred = occurred;
        this.outputs = outputs;
        this.quickReplies = quickReplies;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public int getOccurred() {
        return occurred;
    }

    public void setOccurred(int occurred) {
        this.occurred = occurred;
    }

    public List<OutputValue> getOutputs() {
        return outputs;
    }

    public void setOutputs(List<OutputValue> outputs) {
        this.outputs = outputs;
    }

    public List<QuickReply> getQuickReplies() {
        return quickReplies;
    }

    public void setQuickReplies(List<QuickReply> quickReplies) {
        this.quickReplies = quickReplies;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;
        OutputEntry that = (OutputEntry) o;
        return Objects.equals(action, that.action) && occurred == that.occurred && Objects.equals(outputs, that.outputs)
                && Objects.equals(quickReplies, that.quickReplies);
    }

    @Override
    public int hashCode() {
        return Objects.hash(action, occurred, outputs, quickReplies);
    }

    @Override
    public String toString() {
        return "OutputEntry(" + "action=" + action + ", occurred=" + occurred + ", outputs=" + outputs + ", quickReplies=" + quickReplies + ")";
    }
}
