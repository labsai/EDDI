package io.sls.resources.rest.documentdescriptor.model;


import io.sls.resources.rest.descriptor.model.ResourceDescriptor;
import lombok.Getter;
import lombok.Setter;

/**
 * @author ginccc
 */
@Getter
@Setter
public class DocumentDescriptor extends ResourceDescriptor {
    private String name;
    private String description;
}
