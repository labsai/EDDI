package io.sls.core.lifecycle;

/**
 * User: jarisch
 * Date: 02.11.12
 * Time: 11:54
 */
public interface ILifecycleTaskProvider {

    String getId();

    ILifecycleTask createLifecycleTask();
}
