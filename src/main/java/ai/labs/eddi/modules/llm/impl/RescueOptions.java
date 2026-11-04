/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.modules.llm.model.LlmConfiguration.ResponseValidation;
import ai.labs.eddi.modules.llm.model.LlmConfiguration.Task;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * The local, free (no model call) recovery options of
 * {@link ModelOutputParser}, resolved once from a task's configuration so every
 * caller of the shared parse path (live turn, HITL resume, the format-retry
 * runner's detection, the circuit breaker, a cascade step) applies exactly the
 * same rescue to the same reply.
 *
 * <ul>
 * <li>{@code wrapField} (with {@code wrapDefaults}, {@code wrapMaxChars}): a
 * non-blank reply that is not JSON becomes
 * <code>{ "&lt;wrapField&gt;": "&lt;the text&gt;" }</code>
 * ({@code responseValidation.onInvalidJson: "wrap"});</li>
 * <li>{@code doubleEscape}: one-level unescape of a double-escaped JSON object,
 * on once {@code onInvalidJson} is {@code wrap} or {@code retry};</li>
 * <li>{@code fieldAliases}: rename a top-level key to its configured target
 * when the target is absent (the task's {@code fieldAliases}).</li>
 * </ul>
 *
 * {@link #NONE} (no options) makes the parser behave exactly as it did before
 * these options existed.
 *
 * @param wrapField
 *            top-level property the plain text is wrapped into, or null (no
 *            wrapping)
 * @param wrapDefaults
 *            other top-level properties merged into a wrapped reply; never null
 * @param wrapMaxChars
 *            longest text that is wrapped
 * @param doubleEscape
 *            whether the double-escape repair runs
 * @param fieldAliases
 *            alias to target key renames; never null
 */
public record RescueOptions(String wrapField, Map<String, Object> wrapDefaults, int wrapMaxChars, boolean doubleEscape,
        Map<String, String> fieldAliases) {

    /** Default of {@code responseValidation.wrapMaxChars}. */
    public static final int DEFAULT_WRAP_MAX_CHARS = 20_000;

    /** The {@code onInvalidJson} value that selects wrapping. */
    public static final String WRAP = "wrap";

    /** No rescue at all: today's behaviour. */
    public static final RescueOptions NONE = new RescueOptions(null, Map.of(), DEFAULT_WRAP_MAX_CHARS, false, Map.of());

    /** A top-level property name: no dots, no spaces, no brackets. */
    private static final Pattern PLAIN_NAME = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$-]{0,127}");

    public RescueOptions {
        wrapField = wrapField == null || wrapField.isBlank() ? null : wrapField;
        wrapDefaults = wrapDefaults == null ? Map.of() : wrapDefaults;
        fieldAliases = fieldAliases == null ? Map.of() : fieldAliases;
    }

    /** Whether non-JSON text is wrapped. */
    public boolean wraps() {
        return wrapField != null;
    }

    /** Whether this is {@link #NONE}-equivalent (no option set). */
    public boolean isEmpty() {
        return wrapField == null && !doubleEscape && fieldAliases.isEmpty();
    }

    /**
     * The options a task runs under. Wrapping and the double-escape repair need an
     * enabled {@code responseValidation} (as every other policy); aliases are
     * independent of it, like {@code nonBlankFields}.
     */
    public static RescueOptions of(Task task) {
        if (task == null) {
            return NONE;
        }
        return of(task.getResponseValidation(), task.getFieldAliases());
    }

    /** As {@link #of(Task)} from the pieces. */
    public static RescueOptions of(ResponseValidation validation, Map<String, String> fieldAliases) {
        Map<String, String> aliases = fieldAliases == null ? Map.of() : Map.copyOf(fieldAliases);
        if (validation == null || !validation.isEnabled()) {
            return aliases.isEmpty() ? NONE : new RescueOptions(null, null, DEFAULT_WRAP_MAX_CHARS, false, aliases);
        }
        String onInvalid = validation.getOnInvalidJson();
        String wrapField = validation.getWrapField();
        boolean wrap = WRAP.equalsIgnoreCase(onInvalid) && wrapField != null && !wrapField.isBlank();
        boolean doubleEscape = wrap || "retry".equalsIgnoreCase(onInvalid);
        if (!doubleEscape && aliases.isEmpty()) {
            return NONE;
        }
        return new RescueOptions(wrap ? wrapField : null, wrap ? validation.getWrapDefaults() : null, validation.getWrapMaxChars(), doubleEscape,
                aliases);
    }

    /**
     * Save-time validation of one task. A violation is an
     * {@link IllegalArgumentException}, which the store layer answers with a 400.
     *
     * @param fieldPath
     *            where the task sits, for example {@code langchain.task[0]}
     */
    public static void validate(ResponseValidation validation, Map<String, String> fieldAliases, String fieldPath) {
        if (validation != null) {
            String wrapField = validation.getWrapField();
            boolean wrap = WRAP.equalsIgnoreCase(validation.getOnInvalidJson());
            if (wrap && (wrapField == null || wrapField.isBlank())) {
                throw new IllegalArgumentException(fieldPath + ".responseValidation.onInvalidJson is \"wrap\" but wrapField is not set: name the "
                        + "top-level property the plain-text reply is wrapped into, for example \"wrapField\": \"htmlResponseText\"");
            }
            if (wrapField != null && !wrapField.isBlank() && !PLAIN_NAME.matcher(wrapField).matches()) {
                throw new IllegalArgumentException(fieldPath + ".responseValidation.wrapField \"" + wrapField
                        + "\" must be a plain top-level property name (letters, digits, '_', '-', '$'; no dots or spaces)");
            }
            if (validation.getWrapDefaults() != null) {
                for (Map.Entry<String, Object> e : validation.getWrapDefaults().entrySet()) {
                    if (e.getKey() == null || e.getKey().isBlank()) {
                        throw new IllegalArgumentException(fieldPath + ".responseValidation.wrapDefaults has a blank property name");
                    }
                }
                if (wrapField != null && validation.getWrapDefaults().containsKey(wrapField)) {
                    throw new IllegalArgumentException(fieldPath + ".responseValidation.wrapDefaults must not contain wrapField \"" + wrapField
                            + "\": the wrapped text goes there");
                }
            }
            if (validation.getWrapMaxChars() < 1) {
                throw new IllegalArgumentException(fieldPath + ".responseValidation.wrapMaxChars must be at least 1");
            }
        }
        if (fieldAliases != null) {
            for (Map.Entry<String, String> e : fieldAliases.entrySet()) {
                String alias = e.getKey();
                String target = e.getValue();
                if (alias == null || !PLAIN_NAME.matcher(alias).matches() || target == null || !PLAIN_NAME.matcher(target).matches()) {
                    throw new IllegalArgumentException(fieldPath + ".fieldAliases entry \"" + alias + "\" -> \"" + target
                            + "\" must map one plain top-level property name to another");
                }
                if (alias.equals(target)) {
                    throw new IllegalArgumentException(fieldPath + ".fieldAliases maps \"" + alias + "\" to itself");
                }
            }
        }
    }
}
