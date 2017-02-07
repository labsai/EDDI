package ai.labs.lifecycle;

import ai.labs.memory.IConversationMemory;

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
    List<String> getComponentDependencies();

    /**
     * Returns a List representing all Task which have to be executed in lifecycle
     * BEFORE this Task will be executed!
     * Reason: This task depends on the output of those Tasks.
     *
     * @return a List of Task IDs
     */
    List<String> getOutputDependencies();

    void init();

    void executeTask(IConversationMemory memory) throws LifecycleException;

    void configure(Map<String, Object> configuration) throws PackageConfigurationException;

    void setExtensions(Map<String, Object> extensions) throws UnrecognizedExtensionException, IllegalExtensionConfigurationException;
}
