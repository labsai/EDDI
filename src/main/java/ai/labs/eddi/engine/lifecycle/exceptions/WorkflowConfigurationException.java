package ai.labs.eddi.engine.lifecycle.exceptions;

/**
 * @author ginccc
 */
public class WorkflowConfigurationException extends Exception {
    public WorkflowConfigurationException(String message) {
        super(message);
    }

    public WorkflowConfigurationException(String message, Exception e) {
        super(message, e);
    }
}
