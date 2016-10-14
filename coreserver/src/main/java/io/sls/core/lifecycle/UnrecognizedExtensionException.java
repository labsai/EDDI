package io.sls.core.lifecycle;

/**
 * User: jarisch
 * Date: 13.09.12
 * Time: 16:06
 */
public class UnrecognizedExtensionException extends Exception {
    public UnrecognizedExtensionException(String message) {
        super(message);
    }

    public UnrecognizedExtensionException(String message, Throwable cause) {
        super(message, cause);
    }
}
