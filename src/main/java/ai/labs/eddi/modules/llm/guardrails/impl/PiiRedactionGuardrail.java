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
import ai.labs.eddi.modules.llm.pii.PiiCountries;
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
 * A kind is an entity type in lower case ({@code iban}, {@code fr_nir},
 * {@code de_tax_id}, …); the four original kinds keep their names
 * {@code email}, {@code ssn}, {@code creditcard} and {@code phone}. What runs:
 * <ul>
 * <li>{@code patterns} — comma-separated kinds; absent, the standard set
 * ({@link PiiCountries#STANDARD}: email, ssn, creditcard, phone, iban). Entity
 * names ({@code CREDIT_CARD}) are accepted as aliases;</li>
 * <li>{@code countries} — comma-separated ISO 3166 codes ({@code DE,FR,GB});
 * every identifier of each country is added to {@code patterns};</li>
 * <li>{@code replacement} — the replacement text; {@code {kind}} is replaced by
 * the kind (default {@code [REDACTED:{kind}]}).</li>
 * </ul>
 * National identifiers are opt-in because each matches only its country's
 * format; those whose check digit is weak also need a keyword just before the
 * value. Unknown kinds and countries are deploy warnings.
 * <p>
 * Kinds are applied one after another on the text the previous one left, in the
 * fixed order of {@link BuiltinRecognizers#entities()} — e-mail first, so an
 * address is redacted whole even when its local part is an IBAN; the loose
 * phone pattern last, so it cannot take an identifier's digit groups — and for
 * a text with none of the newer kinds the result is exactly what the four
 * original kinds produced.
 * <p>
 * With action {@code redact} the rewrite is applied; with {@code block},
 * {@code reprompt} or {@code warn} any finding counts as a violation of that
 * kind. Pattern-based: it finds well-formed identifiers, not names or
 * addresses, and is no substitute for a data-loss-prevention service. It runs
 * without a time budget, unlike {@code regex-filter}: its patterns are EDDI's,
 * fixed and bounded, so a scan is linear in the text and a budget would only
 * make long texts undecided.
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
        return Set.of(GuardrailPhase.INPUT, GuardrailPhase.OUTPUT, GuardrailPhase.TOOL_CALL);
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
     * Names in {@code patterns} that are no kind, and codes in {@code countries}
     * that are no supported country: they select nothing, so a typo silently
     * narrows the guardrail.
     */
    @Override
    public List<String> warnings(Map<String, String> parameters) {
        List<String> unknownKinds = new ArrayList<>();
        for (String name : split(parameters != null ? parameters.get("patterns") : null)) {
            if (entityOf(name) == null) {
                unknownKinds.add(name);
            }
        }
        List<String> unknownCountries = new ArrayList<>();
        for (String code : split(parameters != null ? parameters.get("countries") : null)) {
            if (!PiiCountries.isCountry(code)) {
                unknownCountries.add(code);
            }
        }
        List<String> warnings = new ArrayList<>();
        if (!unknownKinds.isEmpty()) {
            warnings.add("pii-redaction guardrail: unknown kind(s) " + unknownKinds + " in patterns — known: " + String.join(", ", kinds()));
        }
        if (!unknownCountries.isEmpty()) {
            warnings.add("pii-redaction guardrail: unknown country code(s) " + unknownCountries + " in countries — supported: "
                    + String.join(", ", PiiCountries.codes()));
        }
        return warnings;
    }

    @Override
    public GuardrailVerdict evaluate(String text, GuardrailContext context) {
        String replacement = context.parameter("replacement", DEFAULT_REPLACEMENT);
        String result = text;
        Set<String> found = new TreeSet<>();
        for (String entity : enabledEntities(context.parameter("patterns", null), context.parameter("countries", null))) {
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

    /**
     * The entities {@code patterns} (default: the standard set) and
     * {@code countries} select, in application order.
     */
    static List<String> enabledEntities(String patterns, String countries) {
        Set<String> wanted = new LinkedHashSet<>();
        List<String> names = split(patterns);
        if (names.isEmpty()) {
            wanted.addAll(PiiCountries.STANDARD);
        }
        for (String name : names) {
            String entity = entityOf(name);
            if (entity != null) {
                wanted.add(entity);
            }
        }
        for (String code : split(countries)) {
            wanted.addAll(PiiCountries.entitiesOf(code));
        }
        return BuiltinRecognizers.entities().stream().filter(wanted::contains).toList();
    }

    private static List<String> split(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<String> parts = new ArrayList<>();
        for (String part : raw.split(",")) {
            if (!part.isBlank()) {
                parts.add(part.trim());
            }
        }
        return parts;
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
