package ai.labs.eddi.engine.lifecycle;

import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.models.ExtensionDescriptor;

import java.util.Collections;
import java.util.List;
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

    /**
     * @return the component (main algorithm) which this task is working with
     */
    Object getComponent();

    /**
     * Returns a List representing all Components of Tasks
     * which this task requires on to be part of the lifecycle.
     * This task will call getComponent() of all Tasks in this List
     *
     * @return a List of Task IDs
     */
    default List<String> getComponentDependencies() {
        return Collections.emptyList();
    }

    /**
     * Returns a List representing all Task which have to be executed in lifecycle
     * BEFORE this Task will be executed!
     * Reason: This task depends on the output of those Tasks.
     *
     * @return a List of Task IDs
     */
    default List<String> getOutputDependencies() {
        return Collections.emptyList();
    }

    default void init() {
        //to be overridden if needed
    }

    void executeTask(IConversationMemory memory) throws LifecycleException;

    default void configure(Map<String, Object> configuration) throws PackageConfigurationException {
        //to be overridden if needed
    }

    default void setExtensions(Map<String, Object> extensions) throws UnrecognizedExtensionException, IllegalExtensionConfigurationException {
        //to be overridden if needed
    }

    default ExtensionDescriptor getExtensionDescriptor() {
        return new ExtensionDescriptor(getId());
    }
}
