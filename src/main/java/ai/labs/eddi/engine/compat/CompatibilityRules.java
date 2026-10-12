/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.compat;

import ai.labs.eddi.modules.llm.capability.JsonResponseFormatPolicy;
import ai.labs.eddi.modules.llm.impl.LlmTask;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * The checks of the deploy-time compatibility lint: configurations that deploy
 * and read fine but behave differently in v6 than they did in 5.x.
 * <p>
 * Pure functions over a configuration, so each rule is testable without a
 * store. {@link AgentCompatibilityLint} walks an agent's workflows and feeds
 * them in. Every finding is advisory: the lint never blocks a deployment.
 * <p>
 * Deliberately not checked, because the engine now handles them: a template in
 * an httpcall's URL host (rendered unencoded, still SSRF-checked), and a
 * property instruction whose {@code fromObjectPath} names the task's
 * {@code responseObjectName} (resolved against the response object).
 */
public final class CompatibilityRules {

    public static final String LLM_API_KEY_TEMPLATE = "LLM_API_KEY_TEMPLATE";
    public static final String LLM_RESPONSE_FORMAT_WITHOUT_CONVERT = "LLM_RESPONSE_FORMAT_WITHOUT_CONVERT";
    public static final String LLM_JSON_MODE_WITH_TOOLS = "LLM_JSON_MODE_WITH_TOOLS";
    public static final String TEMPLATE_DOES_NOT_PARSE = "TEMPLATE_DOES_NOT_PARSE";
    public static final String THYMELEAF_NOT_CONVERTED = "THYMELEAF_NOT_CONVERTED";

    private static final String KEY_API_KEY = "apiKey";
    private static final String KEY_CONVERT_TO_OBJECT = "convertToObject";
    private static final String KEY_RESPONSE_FORMAT = "responseFormat";

    /**
     * The opening of a Qute expression or section, as the runtime engine reads it.
     */
    private static final Pattern QUTE_MARKER = Pattern.compile("\\{[a-zA-Z#/!|]");

    private static final int MAX_DEPTH = 12;

    private CompatibilityRules() {
    }

    /**
     * One advisory finding.
     *
     * @param code
     *            stable identifier of the rule
     * @param location
     *            where it was found, e.g. {@code llm task 'chat'}
     * @param message
     *            what is wrong and what to do
     */
    public record Finding(String code, String location, String message) {
        /** The single line shown in logs and in the deployment status. */
        public String render() {
            return "[" + code + "] " + location + ": " + message;
        }
    }

    /**
     * The checks that need to know how an LLM task is configured.
     *
     * @param location
     *            how the task is named in a finding
     * @param hasApiCalls
     *            the agent has an API-calls step, so its calls are offered as tools
     * @param hasMcpCalls
     *            the agent has an MCP-calls step
     */
    public static List<Finding> lintLlmTask(String location, LlmConfiguration.Task task, boolean hasApiCalls, boolean hasMcpCalls) {
        List<Finding> findings = new ArrayList<>();
        if (task == null) {
            return findings;
        }

        // Every parameter map the task can send to a provider, with how to name it.
        Map<String, Map<String, String>> parameterMaps = new LinkedHashMap<>();
        parameterMaps.put(location, task.getParameters());
        var cascade = task.getModelCascade();
        if (cascade != null && cascade.getSteps() != null) {
            int index = 0;
            for (var step : cascade.getSteps()) {
                parameterMaps.put(location + " cascade step " + index++, step.getParameters());
            }
        }
        if (cascade != null && cascade.getJudgeModel() != null) {
            parameterMaps.put(location + " judge model", cascade.getJudgeModel().getParameters());
        }

        for (var entry : parameterMaps.entrySet()) {
            Map<String, String> parameters = entry.getValue();
            if (parameters == null) {
                continue;
            }
            String apiKey = parameters.get(KEY_API_KEY);
            if (apiKey != null && containsTemplate(apiKey)) {
                findings.add(new Finding(LLM_API_KEY_TEMPLATE, entry.getKey(),
                        "apiKey contains a template. 6.x never templates credentials, so the text is sent to the provider literally "
                                + "and every call fails with an invalid key. Put the key in the vault and use ${vault:<name>}."));
            }
        }

        boolean taskConvertsToObject = isTrue(taskParameter(task, KEY_CONVERT_TO_OBJECT));
        for (var entry : parameterMaps.entrySet()) {
            Map<String, String> parameters = entry.getValue();
            if (parameters == null || !isJson(parameters.get(KEY_RESPONSE_FORMAT))) {
                continue;
            }
            boolean converts = parameters.containsKey(KEY_CONVERT_TO_OBJECT)
                    ? isTrue(parameters.get(KEY_CONVERT_TO_OBJECT))
                    : taskConvertsToObject;
            if (!converts) {
                findings.add(new Finding(LLM_RESPONSE_FORMAT_WITHOUT_CONVERT, entry.getKey(),
                        "responseFormat is \"json\" but convertToObject is not \"true\". 6.x ignores responseFormat; JSON mode is only "
                                + "switched on by convertToObject, so the model may wrap its JSON in a Markdown code block. "
                                + "Set convertToObject to \"true\"."));
            }
        }

        if (taskConvertsToObject && !"on".equalsIgnoreCase(task.getJsonResponseFormat())) {
            boolean toolsOn = task.isAgentMode() || (hasApiCalls && !Boolean.FALSE.equals(task.getEnableHttpCallTools()))
                    || (hasMcpCalls && !Boolean.FALSE.equals(task.getEnableMcpCallTools()));
            if (toolsOn) {
                Set<String> providers = new LinkedHashSet<>();
                if (task.getType() != null) {
                    providers.add(task.getType());
                }
                if (cascade != null && cascade.isEnabled() && cascade.getSteps() != null) {
                    cascade.getSteps().stream().map(LlmConfiguration.CascadeStep::getType).filter(t -> t != null).forEach(providers::add);
                }
                for (String provider : providers) {
                    if (JsonResponseFormatPolicy.supportsRequestLevelJson(provider)
                            && !JsonResponseFormatPolicy.supportsRequestLevelJsonWithTools(provider)) {
                        findings.add(new Finding(LLM_JSON_MODE_WITH_TOOLS, location,
                                "convertToObject is \"true\" and tools are enabled, but provider '" + provider
                                        + "' cannot combine JSON mode with tool calls, so JSON mode is not applied and the reply may arrive "
                                        + "in a Markdown code block. Set enableHttpCallTools and enableMcpCallTools to false if the task "
                                        + "does not need tools (5.x had none)."));
                    }
                }
            }
        }
        return findings;
    }

    /**
     * Parses every string of a configuration with the runtime template engine and
     * reports the ones that do not parse, and 5.x Thymeleaf syntax that was left
     * unconverted. Credential parameters are skipped: they are never templated.
     *
     * @param validator
     *            answers {@code null} for a string that renders, otherwise why it
     *            does not
     */
    public static void scanTemplates(String location, Object node, Function<String, String> validator, List<Finding> findings) {
        scan(location, node, validator, findings, 0);
    }

    private static void scan(String path, Object node, Function<String, String> validator, List<Finding> findings, int depth) {
        if (node == null || depth > MAX_DEPTH) {
            return;
        }
        if (node instanceof String text) {
            checkString(path, text, validator, findings);
        } else if (node instanceof Map<?, ?> map) {
            for (var entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                if (LlmTask.TEMPLATE_SKIP_PARAMS.contains(key)) {
                    continue;
                }
                scan(path + "." + key, entry.getValue(), validator, findings, depth + 1);
            }
        } else if (node instanceof Iterable<?> items) {
            int index = 0;
            for (Object item : items) {
                scan(path + "[" + index++ + "]", item, validator, findings, depth + 1);
            }
        }
    }

    private static void checkString(String path, String text, Function<String, String> validator, List<Finding> findings) {
        if (text.contains("[[${") || text.contains("[(${")) {
            findings.add(new Finding(THYMELEAF_NOT_CONVERTED, path,
                    "contains 5.x Thymeleaf syntax, which 6.x renders literally. Rewrite it as Qute ({...}, {#if}...{/if})."));
            return;
        }
        String problem = validator.apply(text);
        if (problem != null) {
            findings.add(new Finding(TEMPLATE_DOES_NOT_PARSE, path,
                    "the template does not parse (" + problem + "), so every render of it fails."));
        }
    }

    /**
     * Whether a credential value holds a template rather than a configuration
     * reference. {@code ${vault:name}}, {@code ${vars:name}} and the other
     * reference forms are resolved for credentials and are not templates.
     */
    static boolean containsTemplate(String value) {
        String withoutReferences = LlmTask.CONFIG_REF_MENTION.matcher(value).replaceAll("");
        return QUTE_MARKER.matcher(withoutReferences).find() || withoutReferences.contains("[[${") || withoutReferences.contains("[(${");
    }

    private static String taskParameter(LlmConfiguration.Task task, String key) {
        return task.getParameters() == null ? null : task.getParameters().get(key);
    }

    private static boolean isTrue(String value) {
        return value != null && Boolean.parseBoolean(value.trim());
    }

    private static boolean isJson(String value) {
        return value != null && "json".equals(value.trim().toLowerCase(Locale.ROOT));
    }

}
