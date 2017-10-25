package ai.labs.property.model;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

import java.util.LinkedList;
import java.util.List;

/**
 * @author ginccc
 */

@AllArgsConstructor
@Getter
@Setter
public class PropertyEntry {
    private List<String> meanings;
    private String value;

    public PropertyEntry() {
        this.meanings = new LinkedList<>();
        this.value = "";
    }
}
