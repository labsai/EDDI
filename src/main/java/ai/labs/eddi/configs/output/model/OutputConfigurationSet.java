package ai.labs.eddi.configs.output.model;

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
    private String lang;
    private List<OutputConfiguration> outputSet = new ArrayList<>();
}
