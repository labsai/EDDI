package ai.labs.core.output;

import lombok.*;

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
    private int occurrence;

    @Override
    public int compareTo(OutputEntry o) {
        return occurrence < o.occurrence ? -1 : (occurrence == o.occurrence ? 0 : 1);
    }
}
