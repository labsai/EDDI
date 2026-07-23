/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.tools;

import org.jboss.logging.Logger;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

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
 * {@link #fromConfig(String)} or {@link #resolve(Map, String, String, String)}.
 * <p>
 * Unrecognized tokens deliberately fall through to {@link #DEFAULT} rather than
 * failing the agent load: a typo must never silently widen a tool's audience.
 * That guarantee is enforced by {@link #resolve}, which treats a per-tool entry
 * that is <em>present but unparseable</em> as {@link #DEFAULT} instead of
 * letting it fall through to a possibly wider task-level default.
 */
public enum ToolCacheScope {

    GLOBAL("global"), USER("user"), CONVERSATION("conversation");

    private static final Logger LOGGER = Logger.getLogger(ToolCacheScope.class);

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
     * Resolve the effective scope for one tool call: the per-tool entry wins — its
     * dispatch name before its canonical slug — then the task-level default, then
     * {@link #DEFAULT}. Never returns {@code null}.
     *
     * <p>
     * Both names are accepted because {@code toolCacheScopes} shares a
     * configuration vocabulary with {@code toolRateLimits} and {@code toolPricing}
     * on the same task: a built-in is dispatched under its {@code @Tool} method
     * name ({@code searchWeb}) but configured under its whitelist slug
     * ({@code websearch}). A slug-keyed override that bound the rate limit but not
     * the cache scope would leave the tool on the task default — possibly
     * {@code global} — which is exactly the cross-user partition an override like
     * that is usually written to avoid. Dispatch name first keeps a config that
     * pins one operation more specific than one covering the whole tool.
     * </p>
     *
     * <p>
     * <strong>A per-tool entry that is present but unparseable fails safe to
     * {@link #DEFAULT}</strong> and never falls through to the task default: a typo
     * in {@code {"getUserProfile": "usr"}} must not silently promote that tool to
     * the surrounding {@code defaultToolCacheScope: "global"}. The bad token is
     * logged at WARN so it stays discoverable instead of merely inert.
     * </p>
     *
     * @param perTool
     *            per-tool overrides ({@code toolCacheScopes}), may be null
     * @param defaultScope
     *            task-level default ({@code defaultToolCacheScope}), may be null
     * @param dispatchName
     *            the name the model invoked, may be null
     * @param canonicalName
     *            the tool's configuration slug, may be null
     */
    public static ToolCacheScope resolve(Map<String, String> perTool, String defaultScope, String dispatchName, String canonicalName) {
        ToolCacheScope perToolScope = resolvePerTool(perTool, dispatchName);
        if (perToolScope == null) {
            perToolScope = resolvePerTool(perTool, canonicalName);
        }
        if (perToolScope != null) {
            return perToolScope;
        }

        ToolCacheScope taskScope = fromConfig(defaultScope);
        if (taskScope == null && defaultScope != null) {
            LOGGER.warnf("Unrecognized defaultToolCacheScope '%s' — falling back to '%s'.", sanitize(defaultScope), DEFAULT.configValue);
        }
        return taskScope != null ? taskScope : DEFAULT;
    }

    /**
     * Looks up one name in the per-tool map.
     *
     * @return the configured scope; {@link #DEFAULT} when an entry exists but its
     *         token is unrecognized; {@code null} only when there is NO entry for
     *         this name — the sole case that may fall through to a wider scope.
     */
    private static ToolCacheScope resolvePerTool(Map<String, String> perTool, String toolName) {
        if (perTool == null || toolName == null || !perTool.containsKey(toolName)) {
            return null;
        }

        String token = perTool.get(toolName);
        ToolCacheScope scope = fromConfig(token);
        if (scope != null) {
            return scope;
        }

        LOGGER.warnf("toolCacheScopes entry for tool '%s' has unrecognized value '%s' — falling back to '%s' rather than to "
                + "defaultToolCacheScope, so a typo can never widen a tool's cache audience.", sanitize(toolName), sanitize(token),
                DEFAULT.configValue);
        return DEFAULT;
    }
}
