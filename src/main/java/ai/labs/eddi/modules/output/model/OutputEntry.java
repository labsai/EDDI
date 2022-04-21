package ai.labs.eddi.modules.output.model;

import lombok.*;

import java.util.List;

/**
 * @author ginccc
 */

@AllArgsConstructor
@Getter
@Setter
@EqualsAndHashCode
@ToString
public class OutputEntry implements Comparable<OutputEntry> {
    private String action;
    private int occurred;
    private List<OutputValue> outputs;
    private List<QuickReply> quickReplies;

    @Override
    public int compareTo(OutputEntry o) {
        return Integer.compare(occurred, o.occurred);
    }
}
