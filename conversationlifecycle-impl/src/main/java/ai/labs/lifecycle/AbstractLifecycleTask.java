package ai.labs.lifecycle;

import java.util.Map;

/**
 * @author ginccc
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
