package io.sls.core.lifecycle;

/**
 * User: jarisch
 * Date: 20.01.2012
 * Time: 17:28:44
 */
public class LifecycleException extends Exception {
    public LifecycleException(String message) {
        super(message);
    }

    public LifecycleException(String message, Exception exception) {
        super(message, exception);
    }
}
