/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.tools;

import java.util.Arrays;
import java.util.Map;

/**
 * Partition a tool-result cache entry belongs to — the single source of truth
 * for the recognized {@code toolCacheScopes} / {@code defaultToolCacheScope}
 * values on an LLM task.
 * <ul>
 * <li>{@link #USER} — the default. A cached result is only ever served back to
 * the same authenticated user.</li>
 * <li>{@link #CONVERSATION} — narrower still: the result is reused only within
 * the conversation that produced it.</li>
 * <li>{@link #GLOBAL} — opt-in only. Every caller shares one partition. Correct
 * exclusively for tools whose result depends on nothing but its arguments (pure
 * computation, public reference data) and never on who is asking.</li>
 * </ul>
 * The wire/config form stays a lenient {@code String} on
 * {@code LlmConfiguration.Task} for the same forward-compatibility reasons as
 * {@code CascadingStrategy}; parse at the boundary via
 * {@link #fromConfig(String)} or {@link #resolve(Map, String, String)}.
 * <p>
 * Unrecognized tokens deliberately fall through to {@link #DEFAULT} rather than
 * failing the agent load: a typo must never silently widen a tool's audience.
 */
public enum ToolCacheScope {

    GLOBAL("global"), USER("user"), CONVERSATION("conversation");

    /**
     * Applied when no scope is configured, or the configured token is
     * null/blank/unrecognized. Deliberately the narrowest scope that still allows
     * useful reuse — widening is always an explicit, per-tool decision.
     */
    public static final ToolCacheScope DEFAULT = USER;

    private final String configValue;

    ToolCacheScope(String configValue) {
        this.configValue = configValue;
    }

    /** The canonical lowercase token as it appears in JSON configs. */
    public String configValue() {
        return configValue;
    }

    /**
     * Resolve a config token (case-insensitive, trimmed) to a scope, or
     * {@code null} when the value is null/blank/unrecognized.
     */
    public static ToolCacheScope fromConfig(String value) {
        if (value == null) {
            return null;
        }
        String v = value.trim();
        return Arrays.stream(values()).filter(s -> s.configValue.equalsIgnoreCase(v)).findFirst().orElse(null);
    }

    /**
     * Resolve the effective scope for one tool: the per-tool entry wins, then the
     * task-level default, then {@link #DEFAULT}. Never returns {@code null}.
     *
     * @param perTool
     *            per-tool overrides ({@code toolCacheScopes}), may be null
     * @param defaultScope
     *            task-level default ({@code defaultToolCacheScope}), may be null
     * @param toolName
     *            the tool being invoked, may be null
     */
    public static ToolCacheScope resolve(Map<String, String> perTool, String defaultScope, String toolName) {
        ToolCacheScope scope = null;
        if (perTool != null && toolName != null) {
            scope = fromConfig(perTool.get(toolName));
        }
        if (scope == null) {
            scope = fromConfig(defaultScope);
        }
        return scope != null ? scope : DEFAULT;
    }
}
