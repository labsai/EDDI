package ai.labs.eddi.configs.documentdescriptor.model;


import ai.labs.eddi.engine.model.ResourceDescriptor;
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
