package ai.labs.eddi.models;

import lombok.*;

import java.util.List;
import java.util.Map;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
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
        step, conversation, longTerm
    }
}
