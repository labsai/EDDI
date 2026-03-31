package ai.labs.eddi.configs.workflows.model;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

public class ExtensionDescriptor {
    private String type;
    private String displayName;
    private Map<String, ConfigValue> configs = new HashMap<>();
    private Map<String, List<ExtensionDescriptor>> extensions = new HashMap<>();

    public ExtensionDescriptor(String type) {
        this.type = type;
    }

    public enum FieldType {
        INT, DOUBLE, STRING, BOOLEAN, ARRAY, URI
    }

    public static class ConfigValue {
        private String displayName;
        private FieldType fieldType;
        private boolean isOptional;
        private Object defaultValue;

        public ConfigValue(String displayName, FieldType fieldType, boolean isOptional, Object defaultValue) {
            this.displayName = displayName;
            this.fieldType = fieldType;
            this.isOptional = isOptional;
            this.defaultValue = defaultValue;
        }

        public String getDisplayName() {
            return displayName;
        }

        public void setDisplayName(String displayName) {
            this.displayName = displayName;
        }

        public FieldType getFieldType() {
            return fieldType;
        }

        public void setFieldType(FieldType fieldType) {
            this.fieldType = fieldType;
        }

        public boolean isIsOptional() {
            return isOptional;
        }

        public void setIsOptional(boolean isOptional) {
            this.isOptional = isOptional;
        }

        public Object getDefaultValue() {
            return defaultValue;
        }

        public void setDefaultValue(Object defaultValue) {
            this.defaultValue = defaultValue;
        }
    }

    public void addExtension(String extensionName, ExtensionDescriptor extensionDescriptor) {
        extensions.computeIfAbsent(extensionName, k -> new LinkedList<>()).add(extensionDescriptor);
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public Map<String, ConfigValue> getConfigs() {
        return configs;
    }

    public void setConfigs(Map<String, ConfigValue> configs) {
        this.configs = configs;
    }

    public Map<String, List<ExtensionDescriptor>> getExtensions() {
        return extensions;
    }

    public void setExtensions(Map<String, List<ExtensionDescriptor>> extensions) {
        this.extensions = extensions;
    }
}
