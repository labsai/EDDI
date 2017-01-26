package ai.labs.resources.rest.behavior.model.extensions;

import java.util.List;

/**
 * @author ginccc
 */
public class ExecutableAttributeDescriptor {
    private String name;
    private String type;
    private List<String> possibleValues;
    private String description;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public List<String> getPossibleValues() {
        return possibleValues;
    }

    public void setPossibleValues(List<String> possibleValues) {
        this.possibleValues = possibleValues;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        ExecutableAttributeDescriptor that = (ExecutableAttributeDescriptor) o;

        if (description != null ? !description.equals(that.description) : that.description != null) return false;
        if (!name.equals(that.name)) return false;
        if (possibleValues != null ? !possibleValues.equals(that.possibleValues) : that.possibleValues != null)
            return false;
        return type != null ? type.equals(that.type) : that.type == null;

    }

    @Override
    public int hashCode() {
        int result = (name != null ? name.hashCode() : 0);
        result = 31 * result + (type != null ? type.hashCode() : 0);
        result = 31 * result + (possibleValues != null ? possibleValues.hashCode() : 0);
        result = 31 * result + (description != null ? description.hashCode() : 0);
        return result;
    }
}
