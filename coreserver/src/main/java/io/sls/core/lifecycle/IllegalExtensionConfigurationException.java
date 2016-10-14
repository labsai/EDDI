package io.sls.core.lifecycle;

/**
 * User: jarisch
 * Date: 13.09.12
 * Time: 16:08
 */
public class IllegalExtensionConfigurationException extends Exception {
    public IllegalExtensionConfigurationException(String message) {
        super(message);
    }

    public IllegalExtensionConfigurationException(String message, Throwable cause) {
        super(message, cause);
    }
}
