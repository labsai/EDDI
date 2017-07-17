package ai.labs.output.model;

import ai.labs.expressions.Expression;
import lombok.*;

import java.util.List;

/**
 * @author ginccc
 */

@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
@EqualsAndHashCode
@ToString
public class OutputEntry implements Comparable<OutputEntry> {
    private String key;
    private String text;
    private List<QuickReply> quickReplies;
    private int occurrence;

    @Override
    public int compareTo(OutputEntry o) {
        return occurrence < o.occurrence ? -1 : (occurrence == o.occurrence ? 0 : 1);
    }

    @Getter
    @Setter
    public static class QuickReply {
        private String value;
        private List<Expression> expressions;
    }
}
