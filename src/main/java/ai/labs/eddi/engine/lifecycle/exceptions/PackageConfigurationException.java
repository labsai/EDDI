package ai.labs.eddi.engine.lifecycle.exceptions;

/**
 * @author ginccc
 */
public class PackageConfigurationException extends Exception {
    public PackageConfigurationException(String message) {
        super(message);
    }

    public PackageConfigurationException(String message, Exception e) {
        super(message, e);
    }
}
