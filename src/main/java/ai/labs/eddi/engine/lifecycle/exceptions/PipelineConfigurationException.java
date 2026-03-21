package ai.labs.eddi.engine.lifecycle.exceptions;

/**
 * @author ginccc
 */
public class PipelineConfigurationException extends Exception {
    public PipelineConfigurationException(String message) {
        super(message);
    }

    public PipelineConfigurationException(String message, Exception e) {
        super(message, e);
    }
}
