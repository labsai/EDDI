package ai.labs.output.model;

import ai.labs.expressions.Expression;
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

    @NoArgsConstructor
    @AllArgsConstructor
    @Getter
    @Setter
    public static class QuickReply {
        private String value;
        private List<Expression> expressions;
    }

    @NoArgsConstructor
    @AllArgsConstructor
    @Getter
    @Setter
    public static class OutputValue {
        public enum Type {
            text,
            image,
            video,
            audio
        }

        private Type type;
        private List<String> valueAlternatives;
    }
}
