package ai.labs.eddi.engine.runtime;

import ai.labs.eddi.engine.lifecycle.ILifecycleManager;

/**
 * @author ginccc
 */
public interface IExecutableWorkflow {
    String getName();

    String getDescription();

    String getWorkflowId();

    ILifecycleManager getLifecycleManager();
}
