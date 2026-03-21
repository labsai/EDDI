package ai.labs.eddi.engine.lifecycle;

import ai.labs.eddi.engine.lifecycle.exceptions.IllegalExtensionConfigurationException;
import ai.labs.eddi.engine.lifecycle.exceptions.LifecycleException;
import ai.labs.eddi.engine.lifecycle.exceptions.PackageConfigurationException;
import ai.labs.eddi.engine.lifecycle.exceptions.UnrecognizedExtensionException;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.configs.pipelines.model.ExtensionDescriptor;

import java.util.Map;

/**
 * Core interface for EDDI's Lifecycle Pipeline architecture.
 *
 * <p>ILifecycleTask represents a single processing step in EDDI's configurable bot pipeline.
 * Each bot's behavior is defined by a sequence of lifecycle tasks that execute in order,
 * transforming the conversation memory at each step.</p>
 *
 * <h2>Lifecycle Pipeline Concept</h2>
 * <p>Instead of hard-coded bot logic, EDDI processes user interactions through a sequential
 * pipeline of pluggable tasks:</p>
 * <pre>
 * User Input → Parser → Behavior Rules → API/LLM Calls → Output Generation → Save
 * </pre>
 *
 * <h2>Key Architectural Principles</h2>
 * <ul>
 *   <li><strong>Stateless Tasks</strong>: Tasks don't maintain state; they transform the
 *       IConversationMemory object passed to them</li>
 *   <li><strong>Sequential Execution</strong>: Tasks execute in order, each building on
 *       the previous task's results</li>
 *   <li><strong>Pluggable</strong>: New task types can be added without modifying core code</li>
 *   <li><strong>Configurable</strong>: Task behavior is defined in JSON configurations,
 *       not Java code</li>
 * </ul>
 *
 * <h2>Standard Task Types</h2>
 * <ul>
 *   <li><strong>Input Parsing</strong>: Normalize and parse user input</li>
 *   <li><strong>Semantic Parsing</strong>: Extract entities and intents using dictionaries</li>
 *   <li><strong>Behavior Rules</strong>: Evaluate IF-THEN rules to decide actions</li>
 *   <li><strong>Property Extraction</strong>: Extract and store data in conversation memory</li>
 *   <li><strong>HTTP Calls</strong>: Call external REST APIs</li>
 *   <li><strong>LangChain</strong>: Invoke LLM services (OpenAI, Claude, Gemini, etc.)</li>
 *   <li><strong>Output Generation</strong>: Format responses using templates</li>
 * </ul>
 *
 * <h2>Example Implementation</h2>
 * <pre>{@code
 * public class MyCustomTask implements ILifecycleTask {
 *     @Override
 *     public void execute(IConversationMemory memory, Object component) {
 *         // 1. Read from memory
 *         String input = memory.getCurrentStep().getLatestData("input").getResult();
 *
 *         // 2. Process
 *         String processed = process(input);
 *
 *         // 3. Write back to memory
 *         memory.getCurrentStep().storeData(
 *             dataFactory.createData("myResult", processed)
 *         );
 *     }
 * }
 * }</pre>
 *
 * @author ginccc
 * @see IConversationMemory
 * @see ai.labs.eddi.engine.lifecycle.internal.LifecycleManager
 */
public interface ILifecycleTask {
    /**
     * Returns the unique identifier for this lifecycle task.
     *
     * <p>The ID is used for:</p>
     * <ul>
     *   <li>Dependency tracking between tasks</li>
     *   <li>Component cache lookups</li>
     *   <li>Task referencing in configurations</li>
     *   <li>Debugging and logging</li>
     * </ul>
     *
     * @return unique identifier of this task (e.g., "ai.labs.parser", "ai.labs.behavior")
     */
    String getId();

    /**
     * Returns the type identifier for this lifecycle task.
     *
     * <p>Types correspond to the stage in the lifecycle pipeline where this task executes.
     * Common types include:</p>
     * <ul>
     *   <li><code>input</code> - Raw input processing</li>
     *   <li><code>input:normalized</code> - Normalized input</li>
     *   <li><code>expressions</code> - Parsed expressions</li>
     *   <li><code>behavior_rules</code> - Rule evaluation</li>
     *   <li><code>actions</code> - Action triggers</li>
     *   <li><code>httpcalls</code> - External API calls</li>
     *   <li><code>output</code> - Response generation</li>
     * </ul>
     *
     * <p>The type is used by the LifecycleManager to determine execution order and
     * filter tasks when partial lifecycle execution is needed.</p>
     *
     * @return type identifier of this lifecycle task
     */
    String getType();

    /**
     * Executes this lifecycle task, transforming the conversation memory.
     *
     * <p>This is the core method where task logic is implemented. Tasks should:</p>
     * <ol>
     *   <li>Read relevant data from the conversation memory</li>
     *   <li>Perform their specific processing (parsing, rule evaluation, API calls, etc.)</li>
     *   <li>Write results back to the conversation memory</li>
     *   <li>Avoid maintaining internal state (use memory instead)</li>
     * </ol>
     *
     * <p><strong>Important:</strong> Tasks must be thread-safe and stateless. All state
     * should be stored in the {@code memory} parameter, which is passed through the
     * entire pipeline.</p>
     *
     * <p><strong>Example:</strong></p>
     * <pre>{@code
     * @Override
     * public void execute(IConversationMemory memory, Object component) {
     *     // Read from memory
     *     String userInput = memory.getCurrentStep().getLatestData("input").getResult();
     *
     *     // Process using component configuration
     *     MyConfig config = (MyConfig) component;
     *     String result = processInput(userInput, config);
     *
     *     // Store result in memory
     *     IData<String> data = dataFactory.createData("myResult", result);
     *     memory.getCurrentStep().storeData(data);
     * }
     * }</pre>
     *
     * @param memory the conversation memory containing all conversation state and history
     * @param component task-specific configuration/resources loaded from package extensions
     * @throws LifecycleException if an error occurs during task execution
     */
    void execute(IConversationMemory memory, Object component) throws LifecycleException;

    /**
     * Configures this lifecycle task with extension-specific settings.
     *
     * <p>This method is called during bot initialization to set up task-specific
     * configurations from package extensions. The default implementation returns null,
     * indicating no configuration is needed.</p>
     *
     * <p>Tasks that need configuration should override this method to:</p>
     * <ul>
     *   <li>Parse and validate configuration parameters</li>
     *   <li>Load extension resources (dictionaries, rules, templates, etc.)</li>
     *   <li>Return a component object used during {@link #execute(IConversationMemory, Object)}</li>
     * </ul>
     *
     * <p><strong>Example:</strong></p>
     * <pre>{@code
     * @Override
     * public Object configure(Map<String, Object> configuration,
     *                         Map<String, Object> extensions)
     *         throws PackageConfigurationException {
     *     String uri = (String) extensions.get("uri");
     *     MyConfig config = configStore.load(uri);
     *     return config;
     * }
     * }</pre>
     *
     * @param configuration task-specific configuration parameters from package config
     * @param extensions extension URIs and metadata from package definition
     * @return configured component object to be passed to execute(), or null if no configuration needed
     * @throws PackageConfigurationException if configuration is invalid
     * @throws IllegalExtensionConfigurationException if extension configuration is malformed
     * @throws UnrecognizedExtensionException if the extension type is not supported
     */
    default Object configure(Map<String, Object> configuration, Map<String, Object> extensions)
            throws PackageConfigurationException, IllegalExtensionConfigurationException, UnrecognizedExtensionException {

        //to be overridden if needed
        return null;
    }

    /**
     * Returns metadata describing this lifecycle task extension.
     *
     * <p>The descriptor is used for:</p>
     * <ul>
     *   <li>Displaying available extensions in the UI</li>
     *   <li>API documentation generation</li>
     *   <li>Extension discovery and validation</li>
     * </ul>
     *
     * <p>The default implementation returns a basic descriptor with just the task ID.
     * Tasks can override this to provide richer metadata (display name, description,
     * configuration options, etc.).</p>
     *
     * @return extension descriptor containing metadata about this task
     */
    default ExtensionDescriptor getExtensionDescriptor() {
        return new ExtensionDescriptor(getId());
    }
}
