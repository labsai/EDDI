package ai.labs.eddi.configs.properties.model;

import java.util.List;
import java.util.Map;

public class Property {
    private String name;
    private String valueString;
    private Map<String, Object> valueObject;
    private List<Object> valueList;
    private Integer valueInt;
    private Float valueFloat;
    private Boolean valueBoolean;
    private Scope scope = Scope.conversation;

    public Property(String name, String valueString, Scope scope) {
        this.name = name;
        this.valueString = valueString;
        this.scope = scope;
    }

    public Property(String name, Map<String, Object> valueObject, Scope scope) {
        this.name = name;
        this.valueObject = valueObject;
        this.scope = scope;
    }

    public Property(String name, List<Object> valueList, Scope scope) {
        this.name = name;
        this.valueList = valueList;
        this.scope = scope;
    }

    public Property(String name, Integer valueInt, Scope scope) {
        this.name = name;
        this.valueInt = valueInt;
        this.scope = scope;
    }

    public Property(String name, Float valueFloat, Scope scope) {
        this.name = name;
        this.valueFloat = valueFloat;
        this.scope = scope;
    }

    public Property(String name, Boolean valueBoolean, Scope scope) {
        this.name = name;
        this.valueBoolean = valueBoolean;
        this.scope = scope;
    }

    public enum Scope {
        step, conversation, longTerm, secret
    }

    public Property() {
    }

    public Property(String name, String valueString, Map<String, Object> valueObject, List<Object> valueList, Integer valueInt, Float valueFloat,
            Boolean valueBoolean, Scope scope) {
        this.name = name;
        this.valueString = valueString;
        this.valueObject = valueObject;
        this.valueList = valueList;
        this.valueInt = valueInt;
        this.valueFloat = valueFloat;
        this.valueBoolean = valueBoolean;
        this.scope = scope;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getValueString() {
        return valueString;
    }

    public void setValueString(String valueString) {
        this.valueString = valueString;
    }

    public Map<String, Object> getValueObject() {
        return valueObject;
    }

    public void setValueObject(Map<String, Object> valueObject) {
        this.valueObject = valueObject;
    }

    public List<Object> getValueList() {
        return valueList;
    }

    public void setValueList(List<Object> valueList) {
        this.valueList = valueList;
    }

    public Integer getValueInt() {
        return valueInt;
    }

    public void setValueInt(Integer valueInt) {
        this.valueInt = valueInt;
    }

    public Float getValueFloat() {
        return valueFloat;
    }

    public void setValueFloat(Float valueFloat) {
        this.valueFloat = valueFloat;
    }

    public Boolean getValueBoolean() {
        return valueBoolean;
    }

    public void setValueBoolean(Boolean valueBoolean) {
        this.valueBoolean = valueBoolean;
    }

    public Scope getScope() {
        return scope;
    }

    public void setScope(Scope scope) {
        this.scope = scope;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;
        Property that = (Property) o;
        return java.util.Objects.equals(name, that.name) && java.util.Objects.equals(valueString, that.valueString)
                && java.util.Objects.equals(valueObject, that.valueObject) && java.util.Objects.equals(valueList, that.valueList)
                && java.util.Objects.equals(valueInt, that.valueInt) && java.util.Objects.equals(valueFloat, that.valueFloat)
                && java.util.Objects.equals(valueBoolean, that.valueBoolean) && java.util.Objects.equals(scope, that.scope);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(name, valueString, valueObject, valueList, valueInt, valueFloat, valueBoolean, scope);
    }
}
