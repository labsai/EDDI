/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.pii;

import ai.labs.eddi.utils.RegexDeadline;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * {@code builtin} — the in-process detector: pattern plus check digit (or
 * pattern plus keyword) for the types in {@link PiiEntities}. It never leaves
 * the JVM and needs no configuration. Parameters:
 * <ul>
 * <li>{@code entities} — comma-separated types (default: the types
 * {@link DetectorContext#entities()} asks for, or, when it asks for none, the
 * standard set {@link PiiCountries#STANDARD});</li>
 * <li>{@code countries} — comma-separated ISO 3166 codes whose identifiers are
 * added ({@link PiiCountries}).</li>
 * </ul>
 * Both are narrowed further by {@link DetectorContext#entities()}. Past the
 * context's deadline it throws {@link DetectorUnavailableException} with reason
 * {@value #REASON_TIMEOUT}.
 */
@ApplicationScoped
public class BuiltinPiiDetector implements PiiDetector {

    public static final String TYPE = BuiltinRecognizers.DETECTOR_ID;
    public static final String REASON_TIMEOUT = "timeout";

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public String validate(Map<String, String> parameters) {
        List<String> problems = new ArrayList<>();
        List<String> unknown = split(parameters != null ? parameters.get("entities") : null).stream()
                .filter(entity -> !BuiltinRecognizers.supports(entity.toUpperCase(Locale.ROOT))).toList();
        if (!unknown.isEmpty()) {
            problems.add("unknown entity type(s) " + unknown + " — supported: " + String.join(", ", BuiltinRecognizers.entities()));
        }
        List<String> unknownCountries = split(parameters != null ? parameters.get("countries") : null).stream()
                .filter(code -> !PiiCountries.isCountry(code)).toList();
        if (!unknownCountries.isEmpty()) {
            problems.add("unknown country code(s) " + unknownCountries + " — supported: " + String.join(", ", PiiCountries.codes()));
        }
        return problems.isEmpty() ? null : "builtin detector: " + String.join("; ", problems);
    }

    @Override
    public List<PiiSpan> detect(String text, DetectorContext context) throws DetectorUnavailableException {
        Set<String> configured = configuredEntities(context.parameters().get("entities"), context.parameters().get("countries"), context.entities());
        List<PiiSpan> spans = new ArrayList<>();
        try {
            for (String entity : BuiltinRecognizers.entities()) {
                if (context.wants(entity) && configured.contains(entity)) {
                    spans.addAll(BuiltinRecognizers.find(entity, text, context.deadlineNanos()));
                }
            }
        } catch (RegexDeadline.MatchDeadlineExceededException e) {
            throw new DetectorUnavailableException(TYPE, REASON_TIMEOUT);
        }
        return spans;
    }

    /**
     * The {@code entities} parameter; without it, the types the caller asked for,
     * or the standard set when it asked for none. Plus every type of
     * {@code countries}.
     */
    private static Set<String> configuredEntities(String entities, String countries, Set<String> requested) {
        Set<String> configured = new HashSet<>();
        List<String> named = split(entities);
        if (named.isEmpty()) {
            configured.addAll(requested.isEmpty() ? PiiCountries.STANDARD : requested);
        }
        named.forEach(entity -> configured.add(entity.toUpperCase(Locale.ROOT)));
        split(countries).forEach(code -> configured.addAll(PiiCountries.entitiesOf(code)));
        return configured;
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
}
