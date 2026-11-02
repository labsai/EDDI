/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.tools.quota;

import ai.labs.eddi.modules.llm.model.ToolQuotaConfig;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Save-time validation of {@code toolQuotas}. A violation is reported as an
 * {@link IllegalArgumentException}, which the store layer answers with a 400
 * like the other LLM configuration checks.
 */
public final class ToolQuotaConfigValidator {

    static final int MAX_POOL_LENGTH = 64;
    static final int MAX_TOOL_NAME_LENGTH = 128;
    static final int MAX_SUBJECT_TEMPLATE_LENGTH = 1000;

    private static final Pattern POOL_NAME = Pattern.compile("[A-Za-z0-9_.-]+");

    private ToolQuotaConfigValidator() {
    }

    /**
     * @param quotas
     *            the task's {@code toolQuotas}; {@code null} is valid (no quotas)
     * @param fieldPath
     *            where it sits, for the message, e.g.
     *            {@code langchain.task[0].toolQuotas}
     */
    public static void validate(ToolQuotaConfig quotas, String fieldPath) {
        if (quotas == null) {
            return;
        }
        if (quotas.getSubject() != null && quotas.getSubject().length() > MAX_SUBJECT_TEMPLATE_LENGTH) {
            throw new IllegalArgumentException(fieldPath + ".subject is longer than " + MAX_SUBJECT_TEMPLATE_LENGTH + " characters");
        }
        Map<String, ToolQuotaConfig.Rule> tools = quotas.getTools();
        if (tools == null || tools.isEmpty()) {
            throw new IllegalArgumentException(fieldPath + ".tools is empty: name at least one tool, or remove " + fieldPath);
        }
        // pool -> the first rule seen, which every later rule of the pool must equal
        Map<String, PoolTerms> pools = new HashMap<>();
        for (Map.Entry<String, ToolQuotaConfig.Rule> entry : tools.entrySet()) {
            String tool = entry.getKey();
            String path = fieldPath + ".tools['" + tool + "']";
            if (tool == null || tool.isBlank()) {
                throw new IllegalArgumentException(fieldPath + ".tools has a blank tool name");
            }
            if (tool.length() > MAX_TOOL_NAME_LENGTH) {
                throw new IllegalArgumentException(path + " is longer than " + MAX_TOOL_NAME_LENGTH + " characters");
            }
            ToolQuotaConfig.Rule rule = entry.getValue();
            if (rule == null) {
                throw new IllegalArgumentException(path + " must not be null");
            }
            if (rule.getLimit() == null || rule.getLimit() < 1) {
                throw new IllegalArgumentException(path + ".limit must be a positive integer");
            }
            Duration window;
            try {
                window = ToolQuotaRules.parseWindow(rule.getWindow());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(path + "." + e.getMessage());
            }
            String pool = rule.getPool();
            if (pool == null) {
                continue;
            }
            pool = pool.strip();
            if (pool.isEmpty() || pool.length() > MAX_POOL_LENGTH || !POOL_NAME.matcher(pool).matches()) {
                throw new IllegalArgumentException(path + ".pool must be 1-" + MAX_POOL_LENGTH + " characters of letters, digits, '_', '.' or '-'");
            }
            PoolTerms terms = new PoolTerms(tool, rule.getLimit(), window);
            PoolTerms first = pools.putIfAbsent(pool, terms);
            if (first != null && (first.limit() != terms.limit() || !first.window().equals(terms.window()))) {
                throw new IllegalArgumentException(path + " is in pool '" + pool + "' with limit " + terms.limit() + " and window "
                        + rule.getWindow() + ", but '" + first.tool() + "' in the same pool has limit " + first.limit() + " and window "
                        + first.window() + ": the tools of a pool share one counter, so they must share limit and window");
            }
        }
    }

    private record PoolTerms(String tool, int limit, Duration window) {
    }
}
