package ai.labs.eddi.engine.memory;

import java.util.List;

/**
 * Central registry of well-known memory keys used across the EDDI lifecycle
 * workflow.
 * <p>
 * Tasks should import these constants instead of declaring local string
 * constants. Keys that are task-internal (e.g., "langchain:trace") can remain
 * as local {@code MemoryKey<T>} constants within the task itself.
 *
 * @see MemoryKey
 * @see IConversationMemory.IConversationStep
 */
public final class MemoryKeys {

    private MemoryKeys() {
        // non-instantiable
    }

    // ---- Engine / Conversation ----

    /** User input text. Written by the engine, read by InputParserTask. */
    public static final MemoryKey<String> INPUT = MemoryKey.ofPublic("input");

    /** Original (un-normalized) user input. Written by Conversation. */
    public static final MemoryKey<String> INPUT_INITIAL = MemoryKey.of("input:initial");

    /** Normalized user input (after normalizers). Written by InputParserTask. */
    public static final MemoryKey<String> INPUT_NORMALIZED = MemoryKey.of("input:normalized");

    // ---- Parser ----

    /**
     * Parsed expression string. Written by InputParserTask, read by
     * RulesEvaluationTask.
     */
    public static final MemoryKey<String> EXPRESSIONS_PARSED = MemoryKey.of("expressions:parsed");

    /** Extracted intent names. Written by InputParserTask. */
    public static final MemoryKey<List<String>> INTENTS = MemoryKey.ofPublic("intents");

    // ---- Behavior Rules ----

    /** Action strings emitted by behavior rules. Read by most downstream tasks. */
    public static final MemoryKey<List<String>> ACTIONS = MemoryKey.ofPublic("actions");

    // ---- Langchain ----

    /** LLM response text. Written by LlmTask. */
    public static final MemoryKey<String> LANGCHAIN = MemoryKey.ofPublic("langchain");

    /** System message for the LLM. Written by LlmTask. */
    public static final MemoryKey<String> SYSTEM_MESSAGE = MemoryKey.of("systemMessage");

    /** Prompt sent to the LLM. Written by LlmTask. */
    public static final MemoryKey<String> PROMPT = MemoryKey.of("prompt");

    // ---- Output ----

    /**
     * Output prefix key. Used for dynamic output keys like "output:text:action".
     */
    public static final String OUTPUT_PREFIX = "output";

    /**
     * Quick replies prefix key. Used for dynamic keys like "quickReplies:action".
     */
    public static final String QUICK_REPLIES_PREFIX = "quickReplies";

    // ---- ApiCalls ----

    /** ApiCalls prefix key. Used for dynamic keys like "httpCalls:callName". */
    public static final String HTTP_CALLS_PREFIX = "httpCalls";

    // ---- Properties ----

    /** Context prefix key. Used for dynamic keys like "context:output". */
    public static final String CONTEXT_PREFIX = "context";

    /** Extracted properties. Written by PropertySetterTask. */
    public static final MemoryKey<List<?>> PROPERTIES_EXTRACTED = MemoryKey.ofPublic("properties:extracted");
}
