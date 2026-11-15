/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.modules.llm.model.LlmConfiguration.ResponseValidation;
import ai.labs.eddi.modules.llm.model.LlmConfiguration.Task;

import java.util.LinkedHashSet;
import java.util.List;
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
 * when the target is absent (the task's {@code fieldAliases});</li>
 * <li>{@code recoverFields}: for a reply that starts with {@code {} but does
 * not parse, lift the listed string fields out of the broken text ({@code
 * responseValidation.recoverFields}).</li>
 * </ul>
 *
 * {@link #NONE} (no options) makes the parser behave exactly as it did before
 * these options existed.
 *
 * @param wrapField top-level property the plain text is wrapped into, or null
 * (no wrapping)
 *
 * @param wrapDefaults
 *            other top-level properties merged into a wrapped reply; never null
 * @param wrapMaxChars
 *            longest text that is wrapped
 * @param doubleEscape
 *            whether the double-escape repair runs
 * @param fieldAliases
 *            alias to target key renames; never null
 * @param recoverFields
 *            top-level string fields lifted out of a broken JSON reply; never
 *            null, empty = no recovery
 */
public record RescueOptions(String wrapField, Map<String, Object> wrapDefaults, int wrapMaxChars, boolean doubleEscape,
        Map<String, String> fieldAliases, List<String> recoverFields) {

    /** Upper bound of {@code recoverFields}: a handful of fields, not a schema. */
    public static final int MAX_RECOVER_FIELDS = 10;

    /** The {@code onUnwrappable} values. */
    public static final String ON_UNWRAPPABLE_FALLBACK = "fallback";
    public static final String ON_UNWRAPPABLE_RETRY = "retry";

    /** Default of {@code responseValidation.wrapMaxChars}. */
    public static final int DEFAULT_WRAP_MAX_CHARS = 20_000;

    /** The {@code onInvalidJson} value that selects wrapping. */
    public static final String WRAP = "wrap";

    /** No rescue at all: today's behaviour. */
    public static final RescueOptions NONE = new RescueOptions(null, Map.of(), DEFAULT_WRAP_MAX_CHARS, false, Map.of(), List.of());

    /** A top-level property name: no dots, no spaces, no brackets. */
    private static final Pattern PLAIN_NAME = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$-]{0,127}");

    public RescueOptions {
        wrapField = wrapField == null || wrapField.isBlank() ? null : wrapField;
        wrapDefaults = wrapDefaults == null ? Map.of() : wrapDefaults;
        fieldAliases = fieldAliases == null ? Map.of() : fieldAliases;
        recoverFields = recoverFields == null ? List.of() : List.copyOf(new LinkedHashSet<>(recoverFields));
    }

    /** The options without field recovery. */
    public RescueOptions(String wrapField, Map<String, Object> wrapDefaults, int wrapMaxChars, boolean doubleEscape,
            Map<String, String> fieldAliases) {
        this(wrapField, wrapDefaults, wrapMaxChars, doubleEscape, fieldAliases, List.of());
    }

    /** Whether non-JSON text is wrapped. */
    public boolean wraps() {
        return wrapField != null;
    }

    /** Whether broken JSON is searched for the configured string fields. */
    public boolean recovers() {
        return !recoverFields.isEmpty();
    }

    /**
     * Whether the stored text can differ from what the model sent (wrap or field
     * recovery): such a reply must not be streamed to the user as it arrives.
     */
    public boolean rewritesReply() {
        return wraps() || recovers();
    }

    /** Whether this is {@link #NONE}-equivalent (no option set). */
    public boolean isEmpty() {
        return wrapField == null && !doubleEscape && fieldAliases.isEmpty() && recoverFields.isEmpty();
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
            return aliases.isEmpty() ? NONE : new RescueOptions(null, null, DEFAULT_WRAP_MAX_CHARS, false, aliases, null);
        }
        String onInvalid = validation.getOnInvalidJson();
        String wrapField = validation.getWrapField();
        boolean wrap = WRAP.equalsIgnoreCase(onInvalid) && wrapField != null && !wrapField.isBlank();
        boolean doubleEscape = wrap || "retry".equalsIgnoreCase(onInvalid);
        List<String> recover = validation.getRecoverFields();
        if (!doubleEscape && aliases.isEmpty() && (recover == null || recover.isEmpty())) {
            return NONE;
        }
        return new RescueOptions(wrap ? wrapField : null, validation.getWrapDefaults(), validation.getWrapMaxChars(), doubleEscape,
                aliases, recover);
    }

    /**
     * Save-time validation of one task. A violation is an
     * {@link IllegalArgumentException}, which the store layer answers with a 400.
     *
     * @param fieldPath
     *            where the task sits, for example {@code langchain.task[0]}
     */
    public static void validate(ResponseValidation validation, Map<String, String> fieldAliases, String fieldPath) {
        validate(validation, fieldAliases, fieldPath, true);
    }

    /**
     * As above; {@code convertsToObject} says whether the task parses its reply as
     * JSON ({@code convertToObject}), which {@code recoverFields} and
     * {@code onUnwrappable: "retry"} both need.
     */
    public static void validate(ResponseValidation validation, Map<String, String> fieldAliases, String fieldPath, boolean convertsToObject) {
        if (validation != null) {
            validateRecovery(validation, fieldPath, convertsToObject);
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

    private static void validateRecovery(ResponseValidation validation, String fieldPath, boolean convertsToObject) {
        String where = fieldPath + ".responseValidation.";
        List<String> recover = validation.getRecoverFields();
        if (recover != null && !recover.isEmpty()) {
            if (!convertsToObject) {
                throw new IllegalArgumentException(where + "recoverFields needs the task parameter convertToObject: \"true\": there is no JSON "
                        + "reply to recover fields from otherwise");
            }
            if (recover.size() > MAX_RECOVER_FIELDS) {
                throw new IllegalArgumentException(where + "recoverFields lists " + recover.size() + " fields; the limit is " + MAX_RECOVER_FIELDS);
            }
            for (String name : recover) {
                if (name == null || !PLAIN_NAME.matcher(name).matches()) {
                    throw new IllegalArgumentException(where + "recoverFields entry \"" + name
                            + "\" must be a plain top-level property name (letters, digits, '_', '-', '$'; no dots or spaces)");
                }
            }
        }
        String onUnwrappable = validation.getOnUnwrappable();
        if (onUnwrappable != null && !onUnwrappable.isBlank()) {
            boolean retry = ON_UNWRAPPABLE_RETRY.equalsIgnoreCase(onUnwrappable);
            if (!retry && !ON_UNWRAPPABLE_FALLBACK.equalsIgnoreCase(onUnwrappable)) {
                throw new IllegalArgumentException(where + "onUnwrappable \"" + onUnwrappable + "\" is not one of \"fallback\", \"retry\"");
            }
            if (retry && !WRAP.equalsIgnoreCase(validation.getOnInvalidJson())) {
                throw new IllegalArgumentException(where + "onUnwrappable \"retry\" only applies with onInvalidJson \"wrap\" (with "
                        + "onInvalidJson \"retry\" every invalid reply is already re-asked); remove it or set onInvalidJson to \"wrap\"");
            }
            if (retry && !convertsToObject) {
                throw new IllegalArgumentException(where + "onUnwrappable \"retry\" needs the task parameter convertToObject: \"true\"");
            }
        }
    }
}
