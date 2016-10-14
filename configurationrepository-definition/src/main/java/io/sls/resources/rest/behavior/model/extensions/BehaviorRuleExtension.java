package io.sls.resources.rest.behavior.model.extensions;

import java.util.List;

/**
 * User: jarisch
 * Date: 06.08.12
 * Time: 13:14
 */
public class BehaviorRuleExtension {
    private String name;
    private String description;
    private int maxChildCount;
    private List<ExecutableAttributeDescriptor> attributes;

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

    public int getMaxChildCount() {
        return maxChildCount;
    }

    public void setMaxChildCount(int maxChildCount) {
        this.maxChildCount = maxChildCount;
    }

    public List<ExecutableAttributeDescriptor> getAttributes() {
        return attributes;
    }

    public void setAttributes(List<ExecutableAttributeDescriptor> attributes) {
        this.attributes = attributes;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        BehaviorRuleExtension that = (BehaviorRuleExtension) o;

        if (maxChildCount != that.maxChildCount) return false;
        if (attributes != null ? !attributes.equals(that.attributes) : that.attributes != null) return false;
        if (description != null ? !description.equals(that.description) : that.description != null) return false;
        return name.equals(that.name);

    }

    @Override
    public int hashCode() {
        int result = (name != null ? name.hashCode() : 0);
        result = 31 * result + (description != null ? description.hashCode() : 0);
        result = 31 * result + maxChildCount;
        result = 31 * result + (attributes != null ? attributes.hashCode() : 0);
        return result;
    }
}
