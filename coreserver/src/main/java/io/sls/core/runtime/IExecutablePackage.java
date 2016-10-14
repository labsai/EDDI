package io.sls.core.runtime;

import io.sls.core.lifecycle.ILifecycleManager;

/**
 * User: jarisch
 * Date: 23.06.12
 * Time: 19:43
 */
public interface IExecutablePackage {
    String getContext();

    ILifecycleManager getLifecycleManager();
}
