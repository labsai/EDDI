package io.sls.core.lifecycle;

/**
 * User: jarisch
 * Date: 22.08.12
 * Time: 11:55
 */
public class PackageConfigurationException extends Exception {
    public PackageConfigurationException(String message) {
        super(message);
    }

    public PackageConfigurationException(String message, Exception e) {
        super(message, e);
    }
}
