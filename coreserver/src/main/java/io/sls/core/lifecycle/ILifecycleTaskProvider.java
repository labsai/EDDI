package io.sls.core.lifecycle;

/**
 * @author ginccc
 */
public interface ILifecycleTaskProvider {

    String getId();

    ILifecycleTask createLifecycleTask();
}
