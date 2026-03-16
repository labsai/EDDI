package ai.labs.eddi.modules.output.model;


import java.util.List;

/**
 * @author ginccc
 */

public class OutputEntry implements Comparable<OutputEntry> {
    private String action;
    private int occurred;
    private List<OutputValue> outputs;
    private List<QuickReply> quickReplies;

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
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        OutputEntry that = (OutputEntry) o;
        return java.util.Objects.equals(action, that.action) && occurred == that.occurred && java.util.Objects.equals(outputs, that.outputs) && java.util.Objects.equals(quickReplies, that.quickReplies);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(action, occurred, outputs, quickReplies);
    }

    @Override
    public String toString() {
        return "OutputEntry(" + "action=" + action + ", occurred=" + occurred + ", outputs=" + outputs + ", quickReplies=" + quickReplies + ")";
    }
}
