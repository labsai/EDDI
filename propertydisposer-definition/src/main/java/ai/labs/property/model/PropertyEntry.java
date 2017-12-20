package ai.labs.property.model;

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
public class PropertyEntry {
    private List<String> meanings;
    private String value;
}
