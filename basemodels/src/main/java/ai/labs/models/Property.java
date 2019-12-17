package ai.labs.models;

import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class Property {
    private String name;
    private Object value;
    private Scope scope = Scope.conversation;

    public enum Scope {
        step,
        conversation,
        longTerm
    }
}
