package ai.labs.eddi.engine.runtime;

import ai.labs.eddi.engine.lifecycle.ILifecycleManager;

/**
 * @author ginccc
 */
public interface IExecutablePackage {
    String getName();

    String getDescription();

    String getPackageId();

    ILifecycleManager getLifecycleManager();
}
