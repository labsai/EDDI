package ai.labs.resources.rest.output.model;

import lombok.Getter;
import lombok.Setter;

import java.util.LinkedList;
import java.util.List;

/**
 * @author ginccc
 */

@Getter
@Setter
public class OutputConfiguration {
    private String action;
    private int timesOccurred;
    private List<OutputType> outputs = new LinkedList<>();
    private List<QuickReply> quickReplies = new LinkedList<>();

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        OutputConfiguration that = (OutputConfiguration) o;

        return action.equals(that.action) && timesOccurred == that.timesOccurred;
    }

    @Override
    public int hashCode() {
        int result = action.hashCode();
        result = 31 * result + timesOccurred;
        return result;
    }

    @Getter
    @Setter
    public static class OutputType {
        private String type;
        private List<String> valueAlternatives = new LinkedList<>();
    }

    @Getter
    @Setter
    public static class QuickReply {
        private String value;
        private String expressions;
    }
}
