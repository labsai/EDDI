/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.pii;

import ai.labs.eddi.utils.RegexDeadline;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@code builtin} — the in-process detector: pattern plus check digit (or
 * pattern plus keyword) for the types in {@link PiiEntities}. It never leaves
 * the JVM and needs no configuration. Parameters:
 * <ul>
 * <li>{@code entities} — comma-separated subset of the supported types
 * (default: all), applied on top of {@link DetectorContext#entities()}.</li>
 * </ul>
 * Past the context's deadline it throws {@link DetectorUnavailableException}
 * with reason {@value #REASON_TIMEOUT}.
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
        String raw = parameters != null ? parameters.get("entities") : null;
        if (raw == null || raw.isBlank()) {
            return null;
        }
        List<String> unknown = new ArrayList<>();
        for (String part : raw.split(",")) {
            String entity = part.trim().toUpperCase(Locale.ROOT);
            if (!entity.isEmpty() && !BuiltinRecognizers.supports(entity)) {
                unknown.add(part.trim());
            }
        }
        return unknown.isEmpty()
                ? null
                : "builtin detector: unknown entity type(s) " + unknown + " — supported: " + String.join(", ", BuiltinRecognizers.entities());
    }

    @Override
    public List<PiiSpan> detect(String text, DetectorContext context) throws DetectorUnavailableException {
        List<String> configured = configuredEntities(context.parameters().get("entities"));
        List<PiiSpan> spans = new ArrayList<>();
        try {
            for (String entity : BuiltinRecognizers.entities()) {
                if (context.wants(entity) && (configured.isEmpty() || configured.contains(entity))) {
                    spans.addAll(BuiltinRecognizers.find(entity, text, context.deadlineNanos()));
                }
            }
        } catch (RegexDeadline.MatchDeadlineExceededException e) {
            throw new DetectorUnavailableException(TYPE, REASON_TIMEOUT);
        }
        return spans;
    }

    private static List<String> configuredEntities(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<String> entities = new ArrayList<>();
        for (String part : raw.split(",")) {
            String entity = part.trim().toUpperCase(Locale.ROOT);
            if (!entity.isEmpty()) {
                entities.add(entity);
            }
        }
        return entities;
    }
}
