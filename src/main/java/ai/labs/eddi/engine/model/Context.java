package ai.labs.eddi.engine.model;


/**
 * @author ginccc
 */
public class Context {
    public enum ContextType {
        string,
        expressions,
        object,
        array
    }

    private ContextType type;
    private Object value;

    public Context() {
    }

    public Context(ContextType type, Object value) {
        this.type = type;
        this.value = value;
    }

    public ContextType getType() {
        return type;
    }

    public void setType(ContextType type) {
        this.type = type;
    }

    public Object getValue() {
        return value;
    }

    public void setValue(Object value) {
        this.value = value;
    }
}