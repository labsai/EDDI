package ai.labs.resources.rest.documentdescriptor.model;


import ai.labs.resources.rest.model.ResourceDescriptor;
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
