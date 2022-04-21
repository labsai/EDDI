package ai.labs.eddi.models;

import lombok.*;

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
