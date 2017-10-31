package ai.labs.property.model;

import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * @author ginccc
 */

@AllArgsConstructor
@Getter
@Setter
@EqualsAndHashCode
public class PropertyEntry {
    private List<String> meanings;
    private String value;
}
