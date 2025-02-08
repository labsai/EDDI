package ai.labs.eddi.engine.model;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * @author ginccc
 */
@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
public class Context {
    public enum ContextType {
        string,
        expressions,
        object,
        array
    }

    private ContextType type;
    private Object value;
}