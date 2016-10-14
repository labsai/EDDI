package io.sls.resources.rest.documentdescriptor.model;


import io.sls.resources.rest.descriptor.model.ResourceDescriptor;
import lombok.Getter;
import lombok.Setter;

/**
 * User: jarisch
 * Date: 06.09.12
 * Time: 09:32
 */
@Getter
@Setter
public class DocumentDescriptor extends ResourceDescriptor {
    private String name;
    private String description;
}
