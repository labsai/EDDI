package io.sls.resources.rest.extensions.model;

import lombok.Getter;
import lombok.Setter;

/**
 * @author ginccc
 */

@Getter
@Setter
public class InputField {
    private String type;
    private String defaultValue;
    private String displayKey;
}
