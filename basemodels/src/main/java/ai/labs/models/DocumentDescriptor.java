package ai.labs.models;


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
