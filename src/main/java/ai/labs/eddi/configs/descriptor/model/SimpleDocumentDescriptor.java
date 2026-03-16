package ai.labs.eddi.configs.descriptor.model;


/**
 * @author ginccc
 */
public class SimpleDocumentDescriptor {
    private String name;
    private String description;


    public SimpleDocumentDescriptor() {
    }

    public SimpleDocumentDescriptor(String name, String description) {
        this.name = name;
        this.description = description;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }
}
