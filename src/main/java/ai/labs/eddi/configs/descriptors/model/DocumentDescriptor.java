package ai.labs.eddi.configs.descriptors.model;


import ai.labs.eddi.model.ResourceDescriptor;

/**
 * @author ginccc
 */
public class DocumentDescriptor extends ResourceDescriptor {
    private String name;
    private String description;
    private String originId;  // resource ID from the exporting instance (for merge import)

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

    public String getOriginId() {
        return originId;
    }

    public void setOriginId(String originId) {
        this.originId = originId;
    }
}
