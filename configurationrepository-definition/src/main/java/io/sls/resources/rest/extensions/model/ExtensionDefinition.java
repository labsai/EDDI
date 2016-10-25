package io.sls.resources.rest.extensions.model;

import java.net.URI;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * @author ginccc
 */
public class ExtensionDefinition {
    private URI type;
    private List<ExtensionPoint> extensionPoints;
    private Map<String, InputField> configDefinition;

    public ExtensionDefinition() {
        this.extensionPoints = new LinkedList<ExtensionPoint>();
        this.configDefinition = new HashMap<String, InputField>();
    }

    public URI getType() {
        return type;
    }

    public void setType(URI type) {
        this.type = type;
    }

    public List<ExtensionPoint> getExtensionPoints() {
        return extensionPoints;
    }

    public void setExtensionPoints(List<ExtensionPoint> extensionPoints) {
        this.extensionPoints = extensionPoints;
    }

    public Map<String, InputField> getConfigDefinition() {
        return configDefinition;
    }

    public void setConfigDefinition(Map<String, InputField> configDefinition) {
        this.configDefinition = configDefinition;
    }
}
