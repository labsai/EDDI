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
        html,
        image,
        video,
        audio,
        delay
    }

    private Type type;
    private List<String> valueAlternatives;
}
