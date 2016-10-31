package io.sls.lifecycle;

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
