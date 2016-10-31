package io.sls.core.lifecycle;

import io.sls.lifecycle.ILifecycleTask;

/**
 * @author ginccc
 */
public interface ILifecycleTaskProvider {

    String getId();

    ILifecycleTask createLifecycleTask();
}
