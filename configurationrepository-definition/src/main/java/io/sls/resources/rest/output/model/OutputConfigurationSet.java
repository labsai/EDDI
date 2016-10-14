package io.sls.resources.rest.output.model;

import java.util.ArrayList;
import java.util.List;

/**
 * User: jarisch
 * Date: 04.06.12
 * Time: 20:38
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
