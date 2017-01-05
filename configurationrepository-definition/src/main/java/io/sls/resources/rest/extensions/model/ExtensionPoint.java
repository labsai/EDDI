package io.sls.resources.rest.extensions.model;

import lombok.Getter;
import lombok.Setter;

import java.net.URI;

/**
 * @author ginccc
 */

@Getter
@Setter
public class ExtensionPoint {
    private URI namespace;
    private String interfaceClass;
    private String displayKey;

}
