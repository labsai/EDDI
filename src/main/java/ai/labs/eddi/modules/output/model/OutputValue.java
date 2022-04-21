package ai.labs.eddi.modules.output.model;

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
    private String type;
    private List<Object> valueAlternatives;
}
