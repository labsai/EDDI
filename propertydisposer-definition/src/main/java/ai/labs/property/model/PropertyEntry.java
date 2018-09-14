package ai.labs.property.model;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.util.HashMap;
import java.util.List;

/**
 * @author ginccc
 */

@Getter
@Setter
@ToString
public class PropertyEntry extends HashMap<String, String> {
    public PropertyEntry(List<String> meanings, String value) {
        put(String.join(".", meanings), value);
    }
}
