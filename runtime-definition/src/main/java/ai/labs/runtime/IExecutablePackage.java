package ai.labs.runtime;

import ai.labs.lifecycle.ILifecycleManager;

/**
 * @author ginccc
 */
public interface IExecutablePackage {
    String getContext();

    ILifecycleManager getLifecycleManager();
}
