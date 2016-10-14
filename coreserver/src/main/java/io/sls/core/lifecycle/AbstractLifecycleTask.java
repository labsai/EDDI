package io.sls.core.lifecycle;

import java.util.Map;

/**
 * User: jarisch
 * Date: 18.05.12
 * Time: 17:13
 */
public abstract class AbstractLifecycleTask implements ILifecycleTask {


    @Override
    public void init() {
        //TODO
    }

    @Override
    public void configure(Map<String, Object> configuration) throws PackageConfigurationException {
        //TODO
    }

    @Override
    public void setExtensions(Map<String, Object> extensions) throws UnrecognizedExtensionException, IllegalExtensionConfigurationException {
        //TODO
    }
}
