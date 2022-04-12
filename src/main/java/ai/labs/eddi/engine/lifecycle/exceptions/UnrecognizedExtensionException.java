package ai.labs.eddi.engine.lifecycle.exceptions;

/**
 * @author ginccc
 */
public class UnrecognizedExtensionException extends Exception {
    public UnrecognizedExtensionException(String message) {
        super(message);
    }

    public UnrecognizedExtensionException(String message, Throwable cause) {
        super(message, cause);
    }
}
