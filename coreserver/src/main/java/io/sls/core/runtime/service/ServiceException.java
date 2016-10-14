package io.sls.core.runtime.service;

/**
 * User: jarisch
 * Date: 20.05.12
 * Time: 19:38
 */
public class ServiceException extends Exception {
    public ServiceException(String message) {
        super(message);
    }

    public ServiceException(String message, Throwable cause) {
        super(message, cause);
    }

    public ServiceException(Throwable cause) {
        super(cause);
    }
}
