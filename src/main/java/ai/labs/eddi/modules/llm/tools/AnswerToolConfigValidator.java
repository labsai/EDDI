/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.tools;

import ai.labs.eddi.modules.llm.capability.ResponseSchemaConverter;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import ai.labs.eddi.modules.llm.tools.impl.ArtifactTools;
import ai.labs.eddi.modules.llm.tools.impl.CalculatorTool;
import ai.labs.eddi.modules.llm.tools.impl.DataFormatterTool;
import ai.labs.eddi.modules.llm.tools.impl.DateTimeTool;
import ai.labs.eddi.modules.llm.tools.impl.DiscoverToolsTool;
import ai.labs.eddi.modules.llm.tools.impl.FetchToolResponsePageTool;
import ai.labs.eddi.modules.llm.tools.impl.GroupTaskTools;
import ai.labs.eddi.modules.llm.tools.impl.PdfReaderTool;
import ai.labs.eddi.modules.llm.tools.impl.ReadAttachmentTool;
import ai.labs.eddi.modules.llm.tools.impl.TextSummarizerTool;
import ai.labs.eddi.modules.llm.tools.impl.WeatherTool;
import ai.labs.eddi.modules.llm.tools.impl.WebScraperTool;
import ai.labs.eddi.modules.llm.tools.impl.WebSearchTool;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The {@code answerMode} settings of an LLM task (structured answers through a
 * terminal reply tool): their tokens, their defaults and their save-time
 * validation. A violation is reported as an {@link IllegalArgumentException},
 * which the store layer answers with a 400 like the other LLM configuration
 * checks.
 * <p>
 * Lives with the tool code rather than in the store because the runtime reads
 * the same tokens and the same reserved names: one definition, so what is
 * refused at save time and what is refused at assembly cannot drift.
 */
public final class AnswerToolConfigValidator {

    /** Today's behaviour: the model writes the JSON object as its reply text. */
    public static final String MODE_TEXT = "text";
    /** The model answers by calling the terminal reply tool. */
    public static final String MODE_TOOL = "tool";
    /** Force a tool call on every request (the default). */
    public static final String CHOICE_REQUIRED = "required";
    /** Let the model decide whether to call a tool. */
    public static final String CHOICE_AUTO = "auto";
    /** Name of the reply tool when {@code answerToolName} is not set. */
    public static final String DEFAULT_TOOL_NAME = "reply";

    /** What every provider accepts as a function name. */
    private static final Pattern TOOL_NAME = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    private static final String KEY_CONVERT_TO_OBJECT = "convertToObject";
    private static final String KEY_RESPONSE_SCHEMA = "responseSchema";

    /**
     * Every tool class EDDI itself can put in front of a model. Their {@code @Tool}
     * method names are the names a reply tool may never take, whatever the
     * whitelist says today: a whitelist change must not turn a valid configuration
     * into a collision.
     */
    private static final List<Class<?>> ENGINE_TOOL_CLASSES = List.of(CalculatorTool.class, DateTimeTool.class, WebSearchTool.class,
            DataFormatterTool.class, WebScraperTool.class, TextSummarizerTool.class, PdfReaderTool.class, WeatherTool.class,
            FetchToolResponsePageTool.class, ReadAttachmentTool.class, ArtifactTools.class, GroupTaskTools.class, DiscoverToolsTool.class,
            UserMemoryTool.class, ConversationRecallTool.class, ConverseWithAgentTool.class, CreateSubAgentTool.class,
            FindAgentsByCapabilityTool.class, RecruitAgentTool.class, TeardownAgentTool.class);

    private static volatile Set<String> engineToolNames;

    private AnswerToolConfigValidator() {
    }

    /** Whether the task answers through the reply tool. */
    public static boolean isToolMode(LlmConfiguration.Task task) {
        return task != null && MODE_TOOL.equals(normalize(task.getAnswerMode()));
    }

    /** The reply tool's name: {@code answerToolName}, or {@code reply}. */
    public static String toolName(LlmConfiguration.Task task) {
        String configured = task != null ? task.getAnswerToolName() : null;
        return configured == null || configured.isBlank() ? DEFAULT_TOOL_NAME : configured.trim();
    }

    /**
     * Whether requests should force a tool call ({@code answerToolChoice} null or
     * {@code required}).
     */
    public static boolean forcesToolCall(LlmConfiguration.Task task) {
        String choice = normalize(task != null ? task.getAnswerToolChoice() : null);
        return choice.isEmpty() || CHOICE_REQUIRED.equals(choice);
    }

    /**
     * The {@code @Tool} names of every engine-provided tool, plus
     * {@code discover_tools}. Computed once, by reflection over the tool classes.
     */
    public static Set<String> engineToolNames() {
        Set<String> names = engineToolNames;
        if (names == null) {
            Set<String> collected = new HashSet<>();
            for (Class<?> toolClass : ENGINE_TOOL_CLASSES) {
                for (ToolSpecification spec : ToolSpecifications.toolSpecificationsFrom(toolClass)) {
                    collected.add(spec.name());
                }
            }
            collected.add("discover_tools");
            names = Set.copyOf(collected);
            engineToolNames = names;
        }
        return names;
    }

    /**
     * Validates one task's {@code answerMode} settings.
     *
     * @param task
     *            the task; null is valid
     * @param fieldPath
     *            where it sits, for the message, e.g. {@code langchain.task[0]}
     */
    public static void validate(LlmConfiguration.Task task, String fieldPath) {
        if (task == null) {
            return;
        }
        String mode = normalize(task.getAnswerMode());
        if (!mode.isEmpty() && !MODE_TEXT.equals(mode) && !MODE_TOOL.equals(mode)) {
            throw new IllegalArgumentException(fieldPath + ".answerMode must be \"text\" or \"tool\", was \"" + task.getAnswerMode() + "\"");
        }
        String choice = normalize(task.getAnswerToolChoice());
        if (!choice.isEmpty() && !CHOICE_REQUIRED.equals(choice) && !CHOICE_AUTO.equals(choice)) {
            throw new IllegalArgumentException(
                    fieldPath + ".answerToolChoice must be \"required\" or \"auto\", was \"" + task.getAnswerToolChoice() + "\"");
        }
        // Blank is the default, exactly as toolName() reads it at runtime.
        if (task.getAnswerToolName() != null && !task.getAnswerToolName().isBlank()
                && !TOOL_NAME.matcher(task.getAnswerToolName().trim()).matches()) {
            throw new IllegalArgumentException(fieldPath + ".answerToolName must be 1-64 letters, digits, '_' or '-'");
        }
        if (!MODE_TOOL.equals(mode)) {
            return;
        }
        Map<String, String> parameters = task.getParameters();
        String convertToObject = parameters != null ? parameters.get(KEY_CONVERT_TO_OBJECT) : null;
        if (convertToObject == null || !"true".equalsIgnoreCase(convertToObject.trim())) {
            throw new IllegalArgumentException(fieldPath + ".answerMode \"tool\" needs parameters.convertToObject \"true\": the reply tool's "
                    + "arguments are the parsed answer object. Set convertToObject, or use answerMode \"text\".");
        }
        String schema = parameters.get(KEY_RESPONSE_SCHEMA);
        if (schema == null || schema.isBlank()) {
            throw new IllegalArgumentException(fieldPath + ".answerMode \"tool\" needs parameters.responseSchema: the schema is the reply "
                    + "tool's parameters. Add a JSON Schema whose root is an object.");
        }
        if (ResponseSchemaConverter.convert(schema).isEmpty()) {
            throw new IllegalArgumentException(fieldPath + ".answerMode \"tool\" needs a responseSchema whose root is an \"object\" with "
                    + "at least one property, using only what native schema enforcement supports (string, integer, number, boolean, "
                    + "object, array with items; no $ref, anyOf, oneOf or type arrays).");
        }
        String name = toolName(task);
        String collision = collision(task, name);
        if (collision != null) {
            throw new IllegalArgumentException(fieldPath + ".answerToolName \"" + name + "\" collides with " + collision
                    + ". Choose another name for the reply tool.");
        }
    }

    /**
     * What the reply tool's name collides with among the tools this task
     * configuration names, or null.
     */
    static String collision(LlmConfiguration.Task task, String name) {
        if (engineToolNames().contains(name)) {
            return "the built-in tool \"" + name + "\"";
        }
        if (contains(task.getBuiltInToolsWhitelist(), name)) {
            return "builtInToolsWhitelist";
        }
        if (task.getToolRateLimits() != null && task.getToolRateLimits().containsKey(name)) {
            return "a tool named in toolRateLimits (the reply tool is never rate-limited)";
        }
        if (task.getToolPricing() != null && task.getToolPricing().containsKey(name)) {
            return "a tool named in toolPricing (the reply tool is never priced)";
        }
        if (task.getToolTimeoutsMs() != null && task.getToolTimeoutsMs().containsKey(name)) {
            return "a tool named in toolTimeoutsMs";
        }
        if (task.getToolCacheScopes() != null && task.getToolCacheScopes().containsKey(name)) {
            return "a tool named in toolCacheScopes (the reply tool is never cached)";
        }
        if (task.getToolQuotas() != null && task.getToolQuotas().getTools() != null && task.getToolQuotas().getTools().containsKey(name)) {
            return "a tool named in toolQuotas (the reply tool is never counted)";
        }
        return null;
    }

    private static boolean contains(Collection<String> values, String name) {
        return values != null && values.contains(name);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
