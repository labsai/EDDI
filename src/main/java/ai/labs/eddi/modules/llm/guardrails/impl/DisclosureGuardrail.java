/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.guardrails.impl;

import ai.labs.eddi.modules.llm.guardrails.ContentGuardrail;
import ai.labs.eddi.modules.llm.guardrails.GuardrailContext;
import ai.labs.eddi.modules.llm.guardrails.GuardrailPhase;
import ai.labs.eddi.modules.llm.guardrails.GuardrailVerdict;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * {@code disclosure} — tool-call arguments and output. Makes sure a text
 * carries a fixed label, e.g. "— AI-assisted reply" at the end of a public
 * comment the agent is about to post. Used with action {@code redact} (the only
 * action it makes sense with, enforced at save and deploy time): a text without
 * the label is rewritten with the label added.
 * <h3>Parameters (plain strings, never templated)</h3>
 * <ul>
 * <li>{@code text} — the label (required, at most {@value #MAX_TEXT_CHARS}
 * characters);</li>
 * <li>{@code position} — {@code append} (default) or {@code prepend};</li>
 * <li>{@code separator} — what goes between the text and the label (default a
 * blank line).</li>
 * </ul>
 * <h3>Idempotent</h3> A text that already ends (for {@code prepend}: starts)
 * with the label, ignoring surrounding whitespace, is left alone — so the label
 * is added once however often the text is screened (a reprompt, a resume, a
 * cascade step). On an approved tool call nothing is rewritten: a human's edit
 * that dropped the label is refused there with reason {@value #REASON_ADDED},
 * which reads as "label missing".
 *
 * @since 6.8.0
 */
@ApplicationScoped
public class DisclosureGuardrail implements ContentGuardrail {

    public static final String TYPE = "disclosure";

    /** The reason of a text the label was (or had to be) added to. */
    public static final String REASON_ADDED = "disclosure_added";

    static final int MAX_TEXT_CHARS = 500;
    static final String DEFAULT_SEPARATOR = "\n\n";
    static final String POSITION_APPEND = "append";
    static final String POSITION_PREPEND = "prepend";

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public Set<GuardrailPhase> phases() {
        return Set.of(GuardrailPhase.OUTPUT, GuardrailPhase.TOOL_CALL);
    }

    @Override
    public String validate(Map<String, String> parameters) {
        String text = parameters != null ? parameters.get("text") : null;
        if (text == null || text.isBlank()) {
            return "disclosure guardrail: parameter 'text' (the label to add) is required";
        }
        if (text.length() > MAX_TEXT_CHARS) {
            return "disclosure guardrail: parameter 'text' must be at most " + MAX_TEXT_CHARS + " characters";
        }
        String position = parameters.get("position");
        if (position != null && !position.isBlank() && !POSITION_APPEND.equalsIgnoreCase(position.trim())
                && !POSITION_PREPEND.equalsIgnoreCase(position.trim())) {
            return "disclosure guardrail: parameter 'position' must be 'append' or 'prepend'";
        }
        return null;
    }

    @Override
    public GuardrailVerdict evaluate(String text, GuardrailContext context) {
        String label = context.parameter("text", null);
        if (label == null || label.isBlank()) {
            // Refused at deploy time; a text with nothing to add needs nothing.
            return GuardrailVerdict.allow();
        }
        String trimmedLabel = label.strip();
        boolean prepend = POSITION_PREPEND.equals(context.parameter("position", POSITION_APPEND).trim().toLowerCase(Locale.ROOT));
        String separator = context.parameters().getOrDefault("separator", DEFAULT_SEPARATOR);
        String current = text != null ? text : "";
        String stripped = current.strip();
        if (prepend ? stripped.startsWith(trimmedLabel) : stripped.endsWith(trimmedLabel)) {
            return GuardrailVerdict.allow();
        }
        String labelled;
        if (stripped.isEmpty()) {
            labelled = label;
        } else {
            labelled = prepend ? label + separator + current.stripLeading() : current.stripTrailing() + separator + label;
        }
        return GuardrailVerdict.rewrite(labelled, REASON_ADDED);
    }
}
