package io.sls.core.runtime;

import io.sls.core.lifecycle.ILifecycleManager;

/**
 * @author ginccc
 */
public interface IExecutablePackage {
    String getContext();

    ILifecycleManager getLifecycleManager();
}
