package ai.labs.eddi.configs.packages.model;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

@Getter
@Setter
public class ExtensionDescriptor {
    private String type;
    private String displayName;
    private Map<String, ConfigValue> configs = new HashMap<>();
    private Map<String, List<ExtensionDescriptor>> extensions = new HashMap<>();

    public ExtensionDescriptor(String type) {
        this.type = type;
    }

    public enum FieldType {
        INT,
        DOUBLE,
        STRING,
        BOOLEAN,
        ARRAY,
        URI
    }

    @Getter
    @Setter
    @AllArgsConstructor
    public static class ConfigValue {
        private String displayName;
        private FieldType fieldType;
        private boolean isOptional;
        private Object defaultValue;
    }

    public void addExtension(String extensionName, ExtensionDescriptor extensionDescriptor) {
        extensions.computeIfAbsent(extensionName, k -> new LinkedList<>()).add(extensionDescriptor);
    }
}
