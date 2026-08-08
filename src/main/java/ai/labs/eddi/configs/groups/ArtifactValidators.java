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
import com.networknt.schema.SchemaId;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
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

    /**
     * The 2020-12 meta-schema, resolved from the library's bundled copy (no network
     * I/O). {@code getSchema(spec)} alone only parses — it accepts schemas with
     * invalid keyword values — so save-time validation additionally validates the
     * spec <em>as an instance</em> against this. Null only if the bundled resource
     * could not load, in which case save-time validation degrades to parse-only
     * rather than rejecting every config.
     */
    private static final JsonSchema META_SCHEMA;

    static {
        JsonSchema metaSchema = null;
        try {
            metaSchema = SCHEMA_FACTORY.getSchema(SchemaLocation.of(SchemaId.V202012));
        } catch (Exception e) {
            Logger.getLogger(ArtifactValidators.class)
                    .warnf("2020-12 meta-schema unavailable; schema specs are checked by parse only: %s", e.getMessage());
        }
        META_SCHEMA = metaSchema;
    }

    /**
     * Wall-clock budget for one config-authored regex against one artifact's
     * content. A catastrophically backtracking pattern must abort instead of
     * pinning the member turn for its full timeout.
     */
    private static final long REGEX_DEADLINE_NANOS = TimeUnit.MILLISECONDS.toNanos(500);

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
                    String problem = schemaSpecProblem(spec);
                    if (problem != null) {
                        throw new IllegalArgumentException(path + " is not a valid JSON schema: " + problem);
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
            if (!Pattern.compile(spec, Pattern.DOTALL).matcher(deadlineGuarded(content)).find()) {
                return "The content does not match this discussion's required pattern for artifacts. Expected to find a match of: " + spec;
            }
        } catch (PatternSyntaxException e) {
            return "This discussion's artifact pattern validator is misconfigured; the write was refused.";
        } catch (MatchDeadlineExceededException e) {
            return "This discussion's artifact pattern validator did not finish in time; the write was refused.";
        }
        return null;
    }

    /**
     * Save-time schema check, two layers: the spec must parse, and it must itself
     * satisfy the 2020-12 meta-schema — {@code getSchema(spec)} alone accepts e.g.
     * {@code {"type": "strng"}} and only misbehaves at write time. Returns the
     * problem, or {@code null} for a valid spec.
     */
    private static String schemaSpecProblem(String spec) {
        JsonNode schemaNode;
        try {
            schemaNode = MAPPER.readTree(spec);
        } catch (Exception e) {
            return e.getMessage();
        }
        if (META_SCHEMA != null) {
            Set<ValidationMessage> violations = META_SCHEMA.validate(schemaNode);
            if (!violations.isEmpty()) {
                return violations.stream()
                        .limit(MAX_QUOTED_VIOLATIONS)
                        .map(ValidationMessage::getMessage)
                        .collect(Collectors.joining("; "));
            }
        }
        try {
            SCHEMA_FACTORY.getSchema(spec);
        } catch (Exception e) {
            return e.getMessage();
        }
        return null;
    }

    /**
     * Thrown by {@link #deadlineGuarded}'s wrapper when the match budget is spent.
     */
    private static final class MatchDeadlineExceededException extends RuntimeException {
        MatchDeadlineExceededException() {
            super("regex match exceeded its time budget", null, false, false);
        }
    }

    /**
     * Wraps content so a regex match aborts once {@link #REGEX_DEADLINE_NANOS} is
     * spent. Backtracking re-reads characters, so the deadline check in
     * {@code charAt} (sampled, to keep the fast path cheap) is hit constantly by
     * exactly the pathological patterns it exists to stop. Single-matcher use only
     * — the sampling counter is not thread-safe, matching a Matcher's own contract.
     */
    private static CharSequence deadlineGuarded(String content) {
        long deadline = System.nanoTime() + REGEX_DEADLINE_NANOS;
        return new CharSequence() {
            private int accesses;

            @Override
            public int length() {
                return content.length();
            }

            @Override
            public char charAt(int index) {
                if ((++accesses & 0x3FF) == 0 && System.nanoTime() > deadline) {
                    throw new MatchDeadlineExceededException();
                }
                return content.charAt(index);
            }

            @Override
            public CharSequence subSequence(int start, int end) {
                return content.subSequence(start, end);
            }

            @Override
            public String toString() {
                return content;
            }
        };
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
