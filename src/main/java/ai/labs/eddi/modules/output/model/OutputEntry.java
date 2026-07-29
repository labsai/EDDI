/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.output.model;

import java.util.List;
import java.util.Objects;

/**
 * @author ginccc
 */

public class OutputEntry implements Comparable<OutputEntry> {
    private String action;
    private int occurred;
    private List<OutputValue> outputs;
    private List<QuickReply> quickReplies;

    /**
     * Orders by {@code occurred} only — deliberately.
     * <p>
     * {@code OutputGeneration} keeps one {@link List} of entries per action and
     * re-sorts it with {@code Collections.sort(..)} on every insert. That sort is
     * stable, so entries that share the same {@code occurred} value keep the order
     * in which they were declared in the {@code output.json} config — and that is
     * exactly the order in which {@code OutputGenerationTask} emits them as
     * separate chat bubbles. Adding any further tie-breaker (action, content hash,
     * …) would silently re-order user-visible output.
     * <p>
     * This ordering is therefore intentionally <em>not</em> consistent with
     * {@link #equals(Object)}. {@code OutputEntry} must never be put into a
     * {@code TreeSet}/{@code TreeMap}, which would drop distinct entries sharing an
     * occurrence; no production code does (duplicates are filtered by
     * {@code List.contains}, i.e. by {@code equals}, in
     * {@code OutputGeneration.addOutputEntry}).
     */
    @Override
    public int compareTo(OutputEntry o) {
        return Integer.compare(occurred, o.occurred);
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
