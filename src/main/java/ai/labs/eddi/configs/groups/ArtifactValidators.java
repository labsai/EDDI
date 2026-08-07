/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.groups;

import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.ArtifactConfig;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.ArtifactValidator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;

/**
 * The declarative artifact validation chain (I17) — JSON Schema, regex and
 * length checks a group config can gate artifact writes behind. Declarative
 * <em>only</em>: there is deliberately no way to configure code execution here.
 * <p>
 * Two entry points, one per failure audience: {@link #requireValidSpecs} runs
 * at config save time and throws so a typo'd spec fails the save (the author's
 * problem, at the moment they can fix it); {@link #firstRejection} runs at
 * write time and returns a rejection <em>sentence</em> for the model (the write
 * is refused, nothing is stored).
 *
 * @author ginccc
 */
public final class ArtifactValidators {

    /** Bound on schema violation messages quoted back to the model. */
    private static final int MAX_QUOTED_VIOLATIONS = 3;

    /**
     * Validation-only mapper: parses candidate content and schema documents, never
     * persists anything.
     */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final JsonSchemaFactory SCHEMA_FACTORY = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);

    private ArtifactValidators() {
    }

    /**
     * Save-time spec check: every validator must carry a kind and a spec its kind
     * can actually use. Throws {@link IllegalArgumentException} (the same contract
     * as {@code HitlConfigValidation}) so the config save fails with an actionable
     * message rather than every future artifact write failing at runtime.
     */
    public static void requireValidSpecs(ArtifactConfig config) {
        if (config == null || config.validators().isEmpty()) {
            return;
        }
        List<ArtifactValidator> validators = config.validators();
        for (int i = 0; i < validators.size(); i++) {
            ArtifactValidator validator = validators.get(i);
            String path = "artifactConfig.validators[" + i + "]";
            if (validator == null || validator.kind() == null) {
                throw new IllegalArgumentException(path + " must name a kind (JSON_SCHEMA, REGEX or MAX_LENGTH)");
            }
            String spec = validator.spec();
            if (spec == null || spec.isBlank()) {
                throw new IllegalArgumentException(path + " (" + validator.kind() + ") needs a spec");
            }
            switch (validator.kind()) {
                case JSON_SCHEMA -> {
                    try {
                        SCHEMA_FACTORY.getSchema(spec);
                    } catch (Exception e) {
                        throw new IllegalArgumentException(path + " is not a valid JSON schema: " + e.getMessage());
                    }
                }
                case REGEX -> {
                    try {
                        Pattern.compile(spec);
                    } catch (PatternSyntaxException e) {
                        throw new IllegalArgumentException(path + " is not a valid regex: " + e.getMessage());
                    }
                }
                case MAX_LENGTH -> {
                    int max;
                    try {
                        max = Integer.parseInt(spec.trim());
                    } catch (NumberFormatException e) {
                        throw new IllegalArgumentException(path + " (MAX_LENGTH) spec must be a positive integer, not '" + spec + "'");
                    }
                    if (max <= 0) {
                        throw new IllegalArgumentException(path + " (MAX_LENGTH) must be > 0");
                    }
                }
            }
        }
    }

    /**
     * Write-time gate: runs the chain in config order and returns the first failure
     * as a rejection sentence for the model, or {@code null} when every validator
     * passes. A broken spec that slipped past save-time validation (hand-edited
     * storage) rejects the write rather than admitting it — the gate fails closed.
     */
    public static String firstRejection(List<ArtifactValidator> validators, String content) {
        if (validators == null || validators.isEmpty()) {
            return null;
        }
        String candidate = content != null ? content : "";
        for (ArtifactValidator validator : validators) {
            if (validator == null || validator.kind() == null || validator.spec() == null) {
                return "This discussion's artifact validation is misconfigured; the write was refused.";
            }
            String rejection = switch (validator.kind()) {
                case MAX_LENGTH -> checkMaxLength(validator.spec(), candidate);
                case REGEX -> checkRegex(validator.spec(), candidate);
                case JSON_SCHEMA -> checkJsonSchema(validator.spec(), candidate);
            };
            if (rejection != null) {
                return rejection;
            }
        }
        return null;
    }

    private static String checkMaxLength(String spec, String content) {
        int max;
        try {
            max = Integer.parseInt(spec.trim());
        } catch (NumberFormatException e) {
            return "This discussion's artifact length validator is misconfigured; the write was refused.";
        }
        if (content.length() > max) {
            return "The content is %d characters, over this discussion's %d-character limit for artifacts. Shorten it."
                    .formatted(content.length(), max);
        }
        return null;
    }

    private static String checkRegex(String spec, String content) {
        try {
            if (!Pattern.compile(spec, Pattern.DOTALL).matcher(content).find()) {
                return "The content does not match this discussion's required pattern for artifacts. Expected to find a match of: " + spec;
            }
        } catch (PatternSyntaxException e) {
            return "This discussion's artifact pattern validator is misconfigured; the write was refused.";
        }
        return null;
    }

    private static String checkJsonSchema(String spec, String content) {
        JsonSchema schema;
        try {
            schema = SCHEMA_FACTORY.getSchema(spec);
        } catch (Exception e) {
            return "This discussion's artifact schema validator is misconfigured; the write was refused.";
        }
        JsonNode node;
        try {
            node = MAPPER.readTree(content);
        } catch (Exception e) {
            return "The content must be valid JSON to pass this discussion's artifact schema, and it is not. Fix the JSON and retry.";
        }
        Set<ValidationMessage> violations = schema.validate(node);
        if (!violations.isEmpty()) {
            String quoted = violations.stream()
                    .limit(MAX_QUOTED_VIOLATIONS)
                    .map(ValidationMessage::getMessage)
                    .collect(Collectors.joining("; "));
            String suffix = violations.size() > MAX_QUOTED_VIOLATIONS
                    ? " (and " + (violations.size() - MAX_QUOTED_VIOLATIONS) + " more)"
                    : "";
            return "The content does not satisfy this discussion's artifact schema: " + quoted + suffix + ". Fix it and retry.";
        }
        return null;
    }
}
