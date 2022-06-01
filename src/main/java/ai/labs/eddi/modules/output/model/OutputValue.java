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
    private List<OutputItem> valueAlternatives;
}
