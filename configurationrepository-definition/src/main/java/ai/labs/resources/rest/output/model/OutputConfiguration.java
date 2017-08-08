package ai.labs.resources.rest.output.model;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * @author ginccc
 */

@Getter
@Setter
public class OutputConfiguration {
    private String action;
    private int occurred;
    private List<OutputType> outputs;
    private List<QuickReply> quickReplies;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        OutputConfiguration that = (OutputConfiguration) o;

        return action.equals(that.action) && occurred == that.occurred;
    }

    @Override
    public int hashCode() {
        int result = action.hashCode();
        result = 31 * result + occurred;
        return result;
    }

    @Getter
    @Setter
    public static class OutputType {
        private String type;
        private List<String> valueAlternatives;
    }

    @Getter
    @Setter
    public static class QuickReply {
        private String value;

        private String expressions;

    }
}
