/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.modules.llm.capability.ResponseSchemaConverter;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import ai.labs.eddi.modules.llm.tools.AnswerToolConfigValidator;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.request.ToolChoice;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import io.micrometer.core.instrument.Metrics;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static ai.labs.eddi.modules.llm.bootstrap.LlmModule.LLM_TYPE_ANTHROPIC;
import static ai.labs.eddi.modules.llm.bootstrap.LlmModule.LLM_TYPE_AZURE_OPENAI;
import static ai.labs.eddi.modules.llm.bootstrap.LlmModule.LLM_TYPE_GEMINI;
import static ai.labs.eddi.modules.llm.bootstrap.LlmModule.LLM_TYPE_MISTRAL;
import static ai.labs.eddi.modules.llm.bootstrap.LlmModule.LLM_TYPE_OPENAI;
import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * The terminal reply tool of {@code answerMode: "tool"}: a synthetic tool whose
 * parameters are the task's {@code responseSchema}. Calling it ends the tool
 * loop, and its arguments become the answer text — the JSON object the
 * {@code convertToObject} path then parses, validates against the schema and
 * {@code nonBlankFields}, and hands to {@code postResponse}, exactly as it does
 * with a JSON reply written as text.
 * <p>
 * The tool has no executor. It never reaches the approval gate, the call caps,
 * {@code toolQuotas}, rate limiting, the cache or cost tracking, because the
 * loop takes it out of the model's response before any of them sees the
 * response ({@link #extract}). It is recorded in the trace as its own step
 * type, {@value #TRACE_TYPE}, without arguments: the arguments are the answer,
 * which is stored as the output already.
 * <p>
 * Stateless: everything here is static; the one piece of memory — "this
 * conversation was already warned about a duplicate reply" — is a bounded set,
 * the same shape as the other warn-once sets of the tool loop.
 */
final class AnswerTool {

    private static final Logger LOGGER = Logger.getLogger(AnswerTool.class);

    /** Trace step type of a reply-tool answer. */
    static final String TRACE_TYPE = "answer";

    /** Counter of every reply-tool decision point, tagged {@code outcome}. */
    static final String METRIC = "eddi.llm.answer_tool";

    /** The model answered through the reply tool. */
    static final String OUTCOME_REPLY = "reply";
    /** The model answered in plain text; the invalid-JSON policy decides. */
    static final String OUTCOME_TEXT = "text";
    /** More than one reply call in one response; the first was taken. */
    static final String OUTCOME_DUPLICATE = "duplicate";
    /**
     * A reply call sat next to a call that paused for approval; it was dropped.
     */
    static final String OUTCOME_DISCARDED = "discarded_by_pause";
    /**
     * The loop spent its iteration budget without a reply; its fallback text goes
     * through the invalid-JSON handling.
     */
    static final String OUTCOME_NO_REPLY = "no_reply";
    /** A runtime tool carried the reply tool's name and was dropped. */
    static final String OUTCOME_COLLISION = "collision_dropped";

    static final String DESCRIPTION = "Call this exactly once, as your last step, to give the user your answer. "
            + "Never answer in plain text. The arguments are the complete answer.";

    /**
     * Providers whose langchain4j binding maps {@link ToolChoice#REQUIRED} to the
     * provider's "must call a tool" mode (langchain4j 1.23): OpenAI and Azure
     * {@code required}, Anthropic {@code any}, Google AI Gemini function-calling
     * mode {@code ANY}, Mistral {@code any}. Every other binding either throws
     * {@code UnsupportedFeatureException} for it (Ollama, jlama, Hugging Face),
     * takes it from the model builder only (Vertex Gemini), or depends on the model
     * behind it (Bedrock, Oracle GenAI, OpenAI-compatible servers), and gets
     * {@code auto}.
     */
    private static final Set<String> REQUIRED_TOOL_CHOICE = Set.of(LLM_TYPE_OPENAI, LLM_TYPE_AZURE_OPENAI, LLM_TYPE_ANTHROPIC,
            LLM_TYPE_GEMINI, LLM_TYPE_MISTRAL);

    private static final int MAX_WARNED = 10_000;
    private static final Set<String> DUPLICATE_WARNED = ConcurrentHashMap.newKeySet();
    private static final Set<String> NO_OTHER_TOOLS_LOGGED = ConcurrentHashMap.newKeySet();

    private AnswerTool() {
    }

    /** Whether the task answers through the reply tool. */
    static boolean enabled(LlmConfiguration.Task task) {
        return AnswerToolConfigValidator.isToolMode(task);
    }

    /**
     * The reply tool's specification for this task, or null when the task does not
     * use it or its schema cannot be converted (save-time validation refuses that;
     * a stored config from before the check then answers as {@code "text"}).
     */
    static ToolSpecification specFor(LlmConfiguration.Task task) {
        if (!enabled(task)) {
            return null;
        }
        String schema = task.getParameters() != null ? task.getParameters().get("responseSchema") : null;
        var converted = ResponseSchemaConverter.convert(schema);
        if (converted.isEmpty() || !(converted.get().rootElement() instanceof JsonObjectSchema parameters)) {
            LOGGER.warnf("LLM task '%s' sets answerMode \"tool\" but its responseSchema has no convertible object root; answering as text",
                    sanitize(String.valueOf(task.getId())));
            return null;
        }
        return ToolSpecification.builder().name(AnswerToolConfigValidator.toolName(task)).description(DESCRIPTION).parameters(parameters)
                .build();
    }

    /**
     * Whether the provider's binding honours {@link ToolChoice#REQUIRED} on a
     * request.
     */
    static boolean supportsRequiredToolChoice(String provider) {
        return provider != null && REQUIRED_TOOL_CHOICE.contains(provider.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * The tool choice to set on a request of this task to {@code provider}:
     * {@link ToolChoice#REQUIRED} when the task forces the call and the provider
     * supports it, otherwise null (the request keeps the provider default,
     * {@code auto}).
     */
    static ToolChoice toolChoice(LlmConfiguration.Task task, String provider) {
        return AnswerToolConfigValidator.forcesToolCall(task) && supportsRequiredToolChoice(provider) ? ToolChoice.REQUIRED : null;
    }

    /**
     * Deploy-time notes for an LLM configuration: a forced tool call that a
     * provider (the task's or a cascade step's) cannot honour falls back to
     * {@code auto}. Logged at INFO once per configuration load.
     */
    static void reportAtConfigure(LlmConfiguration llmConfiguration) {
        if (llmConfiguration == null || llmConfiguration.tasks() == null) {
            return;
        }
        for (var task : llmConfiguration.tasks()) {
            if (!enabled(task) || !AnswerToolConfigValidator.forcesToolCall(task)) {
                continue;
            }
            Set<String> providers = new LinkedHashSet<>();
            providers.add(task.getType());
            var cascade = task.getModelCascade();
            if (cascade != null && cascade.isEnabled() && cascade.getSteps() != null) {
                for (var step : cascade.getSteps()) {
                    providers.add(step.getType() != null ? step.getType() : task.getType());
                }
            }
            for (String provider : providers) {
                if (!supportsRequiredToolChoice(provider)) {
                    LOGGER.infof("LLM task '%s': answerToolChoice \"required\" is not supported by provider '%s'; its requests use "
                            + "\"auto\", so the model may answer in plain text, which then goes through onInvalidJson",
                            sanitize(String.valueOf(task.getId())), sanitize(String.valueOf(provider)));
                }
            }
        }
    }

    /**
     * Notes, once per task id, that a task in {@code answerMode: "tool"} runs a
     * tool loop whose only tool is the reply tool — it works, but native JSON mode
     * is the cheaper choice where the provider has it.
     */
    static void noteOnlyTool(LlmConfiguration.Task task) {
        String key = String.valueOf(task.getId());
        if (NO_OTHER_TOOLS_LOGGED.size() < MAX_WARNED && NO_OTHER_TOOLS_LOGGED.add(key)) {
            LOGGER.infof("LLM task '%s' uses answerMode \"tool\" without any other tool: every turn is a tool loop with only the reply "
                    + "tool. Where the provider supports native JSON mode without tools, answerMode \"text\" is the cheaper choice.",
                    sanitize(key));
        }
    }

    /**
     * One model response with the reply calls taken out.
     *
     * @param remaining
     *            the response without any reply call, or the response itself when
     *            it had none
     * @param answer
     *            the first reply call, or null
     * @param replyCalls
     *            how many reply calls the response carried
     */
    record Extraction(AiMessage remaining, ToolExecutionRequest answer, int replyCalls) {
        boolean answered() {
            return answer != null;
        }

        /** Whether other tool calls are left to run. */
        boolean hasOtherCalls() {
            return remaining.hasToolExecutionRequests();
        }

        /** The answer text: the reply call's arguments, {@code {}} when empty. */
        String answerText() {
            String arguments = answer.arguments();
            return arguments == null || arguments.isBlank() ? "{}" : arguments;
        }
    }

    /**
     * Takes the reply calls out of a model response. With {@code toolName} null
     * (the task does not use the reply tool) the response is returned unchanged.
     */
    static Extraction extract(AiMessage message, String toolName) {
        if (toolName == null || message == null || !message.hasToolExecutionRequests()) {
            return new Extraction(message, null, 0);
        }
        List<ToolExecutionRequest> others = new ArrayList<>();
        ToolExecutionRequest first = null;
        int replies = 0;
        for (ToolExecutionRequest request : message.toolExecutionRequests()) {
            if (toolName.equals(request.name())) {
                replies++;
                if (first == null) {
                    first = request;
                }
            } else {
                others.add(request);
            }
        }
        if (replies == 0) {
            return new Extraction(message, null, 0);
        }
        return new Extraction(message.toBuilder().toolExecutionRequests(others).build(), first, replies);
    }

    /**
     * Counts a duplicate reply and warns about it once per conversation.
     */
    static void duplicate(String conversationId, int replyCalls) {
        count(OUTCOME_DUPLICATE);
        String key = String.valueOf(conversationId);
        if (DUPLICATE_WARNED.size() >= MAX_WARNED) {
            DUPLICATE_WARNED.clear();
        }
        if (DUPLICATE_WARNED.add(key)) {
            LOGGER.warnf("The model called the reply tool %d times in one response (conversation %s); the first call is the answer, "
                    + "the others are ignored. Logged once per conversation.", replyCalls, sanitize(key));
        }
    }

    /** The trace step of a reply-tool answer: type and tool name, no arguments. */
    static Map<String, Object> traceStep(String toolName) {
        return Map.of("type", TRACE_TYPE, "tool", toolName);
    }

    static void count(String outcome) {
        Metrics.globalRegistry.counter(METRIC, "outcome", outcome).increment();
    }
}
