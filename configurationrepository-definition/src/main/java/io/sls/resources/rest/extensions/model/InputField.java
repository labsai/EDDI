package io.sls.resources.rest.extensions.model;

/**
 * User: jarisch
 * Date: 12.09.12
 * Time: 15:03
 */
public class InputField {
    private String type;
    private String defaultValue;
    private String displayKey;

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getDefaultValue() {
        return defaultValue;
    }

    public void setDefaultValue(String defaultValue) {
        this.defaultValue = defaultValue;
    }

    public String getDisplayKey() {
        return displayKey;
    }

    public void setDisplayKey(String displayKey) {
        this.displayKey = displayKey;
    }
}
