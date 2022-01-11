package ai.labs.eddi.engine.lifecycle;

/**
 * @author ginccc
 */
public class IllegalExtensionConfigurationException extends Exception {
    public IllegalExtensionConfigurationException(String message) {
        super(message);
    }

    public IllegalExtensionConfigurationException(String message, Throwable cause) {
        super(message, cause);
    }
}
