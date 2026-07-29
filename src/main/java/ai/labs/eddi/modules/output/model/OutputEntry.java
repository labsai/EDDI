/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.output.model;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * @author ginccc
 */

public class OutputEntry implements Comparable<OutputEntry> {
    private static final Comparator<String> ACTION_COMPARATOR = Comparator.nullsFirst(Comparator.naturalOrder());

    /**
     * Hands every instance a monotonically increasing creation sequence.
     * {@code OutputGenerationTask} builds the entries in configuration order, so
     * this is the configuration order — and it is what makes the final tie-break of
     * {@link #compareTo(OutputEntry)} both total and <em>stable</em>.
     */
    private static final AtomicLong CREATION_SEQUENCE = new AtomicLong();

    private String action;
    private int occurred;
    private List<OutputValue> outputs;
    private List<QuickReply> quickReplies;

    /**
     * Not part of the entry's identity — deliberately excluded from
     * {@link #equals(Object)} / {@link #hashCode()} and never exposed as a getter,
     * so it stays invisible to serialization.
     */
    private final long creationSequence = CREATION_SEQUENCE.getAndIncrement();

    /**
     * Orders primarily by {@code occurred}, which is what the output pipeline sorts
     * on. The remaining steps exist so the ordering stays consistent with
     * {@link #equals(Object)}: two entries only compare equal when they really are
     * equal, otherwise a {@code TreeSet}/{@code TreeMap} would silently drop
     * distinct entries that merely share the same {@code occurred} value.
     * <p>
     * The final tie-break is the creation sequence and NOT the content hash: the
     * production sort site ({@code OutputGeneration#addOutputEntry}) sorts a list
     * of entries that all share the same action, and every entry of the highest
     * occurrence is emitted to the user in list order. Ordering those by hash would
     * scramble configuration order into an arbitrary (though deterministic) one;
     * ordering them by creation sequence keeps the order the agent author wrote.
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
        // outputs are not themselves comparable, so fall back to configuration
        // order instead of reporting equality.
        return Long.compare(creationSequence, o.creationSequence);
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
