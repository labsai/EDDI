package ai.labs.output.model;

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
public class OutputValue {
    public enum Type {
        text,
        image,
        video,
        audio
    }

    private Type type;
    private long delayInMillis;
    private List<String> valueAlternatives;
}
