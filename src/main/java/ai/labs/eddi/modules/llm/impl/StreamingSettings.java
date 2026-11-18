/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import org.eclipse.microprofile.config.ConfigProvider;

/**
 * The deployment defaults of the streaming contract, read once from the
 * configuration. A task's {@code streaming} block overrides each of them (see
 * {@link ai.labs.eddi.modules.llm.model.StreamingConfig}).
 * <p>
 * Read through {@link ConfigProvider} rather than injected because the
 * streaming executors are plain objects built by {@code LlmTask}; the same
 * idiom {@code PageBounds} uses. Without a configuration source (plain unit
 * tests) the built-in defaults apply.
 *
 * @param requireFinishReason
 *            {@code eddi.llm.streaming.require-finish-reason}; {@code null}
 *            when unset — each provider's own default then applies
 * @param idleTimeoutSeconds
 *            {@code eddi.llm.streaming.idle-timeout-seconds}; {@code 0} = off
 */
record StreamingSettings(Boolean requireFinishReason, long idleTimeoutSeconds) {

    static final String REQUIRE_FINISH_REASON_PROPERTY = "eddi.llm.streaming.require-finish-reason";
    static final String IDLE_TIMEOUT_PROPERTY = "eddi.llm.streaming.idle-timeout-seconds";

    /**
     * Unset: the provider decides (see
     * {@code StreamPolicy.STRICT_FINISH_REASON_TYPES}).
     */
    static final Boolean DEFAULT_REQUIRE_FINISH_REASON = null;
    static final long DEFAULT_IDLE_TIMEOUT_SECONDS = 30L;

    /** The built-in defaults, without reading the configuration. */
    static final StreamingSettings DEFAULTS = new StreamingSettings(DEFAULT_REQUIRE_FINISH_REASON, DEFAULT_IDLE_TIMEOUT_SECONDS);

    private static volatile StreamingSettings current;

    /** The deployment's settings, read on first use. */
    static StreamingSettings global() {
        StreamingSettings value = current;
        if (value == null) {
            value = read();
            current = value;
        }
        return value;
    }

    /** Pins the settings, or with {@code null} reads them again. For tests. */
    static void override(StreamingSettings settings) {
        current = settings;
    }

    private static StreamingSettings read() {
        try {
            var config = ConfigProvider.getConfig();
            Boolean requireFinishReason = config.getOptionalValue(REQUIRE_FINISH_REASON_PROPERTY, Boolean.class)
                    .orElse(DEFAULT_REQUIRE_FINISH_REASON);
            long idle = config.getOptionalValue(IDLE_TIMEOUT_PROPERTY, Long.class).orElse(DEFAULT_IDLE_TIMEOUT_SECONDS);
            return new StreamingSettings(requireFinishReason, Math.max(0L, idle));
        } catch (RuntimeException | NoClassDefFoundError e) {
            // No config source on this classpath (plain unit tests): the defaults.
            return DEFAULTS;
        }
    }
}
