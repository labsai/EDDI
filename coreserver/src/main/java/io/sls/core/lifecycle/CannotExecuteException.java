package io.sls.core.lifecycle;

import io.sls.lifecycle.LifecycleException;

/**
 * @author ginccc
 */
public class CannotExecuteException extends LifecycleException {
    public CannotExecuteException(String message) {
        super(message);
    }
}
