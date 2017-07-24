package ai.labs.resources.rest.output.model;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/**
 * @author ginccc
 */

@Getter
@Setter
@EqualsAndHashCode
public class OutputConfigurationSet {
    private List<OutputConfiguration> outputs;

    public OutputConfigurationSet() {
        this.outputs = new ArrayList<>();
    }
}
