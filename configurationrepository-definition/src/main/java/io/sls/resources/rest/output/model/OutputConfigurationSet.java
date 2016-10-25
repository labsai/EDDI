package io.sls.resources.rest.output.model;

import java.util.ArrayList;
import java.util.List;

/**
 * @author ginccc
 */
public class OutputConfigurationSet {
    private List<OutputConfiguration> outputs;

    public OutputConfigurationSet() {
        this.outputs = new ArrayList<OutputConfiguration>();
    }

    public List<OutputConfiguration> getOutputs() {
        return outputs;
    }

    public void setOutputs(List<OutputConfiguration> outputs) {
        this.outputs = outputs;
    }
}
