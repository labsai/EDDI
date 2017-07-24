package ai.labs.resources.rest.output.model;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/**
 * @author ginccc
 */

@Getter
@Setter
public class OutputConfigurationSet {
    private List<OutputConfiguration> outputs;

    public OutputConfigurationSet() {
        this.outputs = new ArrayList<>();
    }
}
