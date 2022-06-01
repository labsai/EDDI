package ai.labs.eddi.configs.output.model;

import ai.labs.eddi.modules.output.model.OutputItem;
import ai.labs.eddi.modules.output.model.QuickReply;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.LinkedList;
import java.util.List;

/**
 * @author ginccc
 */

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class OutputConfiguration {
    private String action;
    private int timesOccurred;
    private List<Output> outputs = new LinkedList<>();
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
    public static class Output {
        private List<OutputItem> valueAlternatives = new LinkedList<>();
    }
}
