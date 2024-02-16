package ai.labs.eddi.engine.lifecycle;

import ai.labs.eddi.engine.lifecycle.exceptions.IllegalExtensionConfigurationException;
import ai.labs.eddi.engine.lifecycle.exceptions.LifecycleException;
import ai.labs.eddi.engine.lifecycle.exceptions.PackageConfigurationException;
import ai.labs.eddi.engine.lifecycle.exceptions.UnrecognizedExtensionException;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.configs.packages.model.ExtensionDescriptor;

import java.util.Map;

/**
 * @author ginccc
 */
public interface ILifecycleTask {
    /**
     * The unique ID of this Task may be referenced by other tasks
     * as well as the LifecycleManager for dependency checks
     *
     * @return unique ID of this Task
     */
    String getId();

    /**
     * get type of lifecycle task.
     *
     * @return type of lifecycle task: input, input:normalized, behavior_rules,...,output
     */
    String getType();

    void execute(IConversationMemory memory, Object component) throws LifecycleException;

    default Object configure(Map<String, Object> configuration, Map<String, Object> extensions)
            throws PackageConfigurationException, IllegalExtensionConfigurationException, UnrecognizedExtensionException {

        //to be overridden if needed
        return null;
    }

    default ExtensionDescriptor getExtensionDescriptor() {
        return new ExtensionDescriptor(getId());
    }
}
