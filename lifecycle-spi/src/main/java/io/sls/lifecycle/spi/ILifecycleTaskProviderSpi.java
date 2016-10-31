package io.sls.lifecycle.spi;

import io.sls.lifecycle.ILifecycleTask;

/**
 * @author Moritz Becker (moritz.becker@gmx.at)
 * @since 1.2
 */
public interface ILifecycleTaskProviderSpi {

    String getLifecycleTaskId();

    Class<? extends ILifecycleTask> getLifecycleTaskClass();

}
