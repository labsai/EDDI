/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.guardrails.impl;

import ai.labs.eddi.modules.llm.guardrails.ContentGuardrail;
import ai.labs.eddi.modules.llm.guardrails.GuardrailContext;
import ai.labs.eddi.modules.llm.guardrails.GuardrailPhase;
import ai.labs.eddi.modules.llm.guardrails.GuardrailVerdict;
import ai.labs.eddi.modules.llm.pii.BuiltinRecognizers;
import ai.labs.eddi.modules.llm.pii.PiiEntities;
import ai.labs.eddi.modules.llm.pii.PiiSpan;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * {@code pii-redaction} — input and output. Finds personal identifiers with the
 * built-in detector ({@link BuiltinRecognizers}) and reports a rewrite with
 * each one replaced by {@code [REDACTED:<kind>]}.
 * <p>
 * Kinds: {@code email}, {@code ssn} (US), {@code creditcard} (Luhn-checked),
 * {@code phone}, {@code iban} (ISO 13616 mod-97), {@code de_tax_id} (Steuer-ID
 * check digit), {@code de_social_security} (Rentenversicherungsnummer check
 * digit), {@code de_id_card} (ID card or passport serial next to a keyword such
 * as "Ausweis") and {@code date_of_birth} (a date next to a keyword such as
 * "geb." or "born"). The entity names ({@code CREDIT_CARD}, {@code US_SSN}, …)
 * are accepted as aliases.
 * <p>
 * Kinds are applied one after another on the text the previous one left —
 * e-mail first, so an address is redacted whole even when its local part is an
 * IBAN; then the structured identifiers, so the looser phone pattern cannot
 * take the digit groups of an IBAN — and for a text with none of the newer
 * kinds the result is exactly what the four original kinds produced.
 * <p>
 * With action {@code redact} the rewrite is applied; with {@code block},
 * {@code reprompt} or {@code warn} any finding counts as a violation of that
 * kind. Pattern-based: it finds well-formed identifiers, not names or
 * addresses, and is no substitute for a data-loss-prevention service. It runs
 * without a time budget, unlike {@code regex-filter}: its patterns are EDDI's,
 * fixed and bounded, so a scan is linear in the text and a budget would only
 * make long texts undecided. Parameters:
 * <ul>
 * <li>{@code patterns} — comma-separated subset of the kinds (default:
 * all);</li>
 * <li>{@code replacement} — the replacement text; {@code {kind}} is replaced by
 * the kind (default {@code [REDACTED:{kind}]}).</li>
 * </ul>
 */
@ApplicationScoped
public class PiiRedactionGuardrail implements ContentGuardrail {

    public static final String TYPE = "pii-redaction";
    static final String DEFAULT_REPLACEMENT = "[REDACTED:{kind}]";

    /**
     * The kind names the guardrail used before it had a shared detector; they stay
     * its names for those entities, in {@code {kind}} and in the reason.
     */
    private static final Map<String, String> LEGACY_KINDS = Map.of(PiiEntities.EMAIL, "email", PiiEntities.US_SSN, "ssn",
            PiiEntities.CREDIT_CARD, "creditcard", PiiEntities.PHONE, "phone");

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public Set<GuardrailPhase> phases() {
        return Set.of(GuardrailPhase.INPUT, GuardrailPhase.OUTPUT);
    }

    /** The guardrail's name for {@code entity}: {@code email}, {@code iban}, … */
    public static String kindOf(String entity) {
        return LEGACY_KINDS.getOrDefault(entity, entity.toLowerCase(Locale.ROOT));
    }

    /** Every kind, in the order they are applied. */
    public static List<String> kinds() {
        return BuiltinRecognizers.entities().stream().map(PiiRedactionGuardrail::kindOf).toList();
    }

    /**
     * Names in {@code patterns} that are no kind: they select nothing, so a typo
     * silently narrows the guardrail.
     */
    @Override
    public List<String> warnings(Map<String, String> parameters) {
        String raw = parameters != null ? parameters.get("patterns") : null;
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<String> unknown = new ArrayList<>();
        for (String part : raw.split(",")) {
            String name = part.trim();
            if (!name.isEmpty() && entityOf(name) == null) {
                unknown.add(name);
            }
        }
        return unknown.isEmpty()
                ? List.of()
                : List.of("pii-redaction guardrail: unknown kind(s) " + unknown + " in patterns — known: " + String.join(", ", kinds()));
    }

    @Override
    public GuardrailVerdict evaluate(String text, GuardrailContext context) {
        String replacement = context.parameter("replacement", DEFAULT_REPLACEMENT);
        String result = text;
        Set<String> found = new TreeSet<>();
        for (String entity : enabledEntities(context.parameter("patterns", null))) {
            List<PiiSpan> spans = BuiltinRecognizers.find(entity, result, BuiltinRecognizers.NO_DEADLINE);
            if (!spans.isEmpty()) {
                String kind = kindOf(entity);
                result = replace(result, spans, replacement.replace("{kind}", kind));
                found.add(kind);
            }
        }
        if (found.isEmpty()) {
            return GuardrailVerdict.allow();
        }
        return GuardrailVerdict.rewrite(result, reason(found));
    }

    private static String reason(Set<String> kinds) {
        return "pii:" + String.join(",", kinds);
    }

    /** The entity a {@code patterns} entry names (kind or entity name), or null. */
    private static String entityOf(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, String> legacy : LEGACY_KINDS.entrySet()) {
            if (legacy.getValue().equals(lower)) {
                return legacy.getKey();
            }
        }
        String upper = name.toUpperCase(Locale.ROOT);
        return BuiltinRecognizers.supports(upper) ? upper : null;
    }

    private static List<String> enabledEntities(String parameter) {
        if (parameter == null || parameter.isBlank()) {
            return BuiltinRecognizers.entities();
        }
        Set<String> wanted = new LinkedHashSet<>();
        for (String part : parameter.split(",")) {
            String entity = entityOf(part.trim());
            if (entity != null) {
                wanted.add(entity);
            }
        }
        return BuiltinRecognizers.entities().stream().filter(wanted::contains).toList();
    }

    /** {@code text} with each span (sorted, disjoint) replaced. */
    private static String replace(String text, List<PiiSpan> spans, String replacement) {
        StringBuilder out = new StringBuilder(text.length());
        int copied = 0;
        for (PiiSpan span : spans) {
            out.append(text, copied, span.start()).append(replacement);
            copied = span.end();
        }
        out.append(text, copied, text.length());
        return out.toString();
    }
}
