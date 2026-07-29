/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.model;

import ai.labs.eddi.utils.LogSanitizer;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import org.jboss.logging.Logger;

/**
 * @author ginccc
 */
public class Deployment {
    public enum Environment {
        production, test;

        private static final Logger LOGGER = Logger.getLogger(Environment.class);

        /** The environment names a caller may pass, for error messages. */
        public static final String VALID_ENVIRONMENTS = "production, test";

        /**
         * The single strict environment parser — the one place that knows which strings
         * map onto which environment. Only an absent (null/blank) value defaults to
         * {@link #production}; an environment the platform does not know is rejected
         * rather than silently resolving to production, so a typo such as
         * {@code "staging"} can never deploy to, undeploy from, or talk to the live
         * environment.
         * <p>
         * Callers that act on the parsed value (MCP tools, the agent setup service)
         * must use this method. {@link #fromString(String)} is the lenient
         * deserialization counterpart for data that is merely being read back.
         *
         * @param value
         *            the environment name, may be null/blank
         * @return the matching environment
         * @throws IllegalArgumentException
         *             if {@code value} is neither blank nor a known environment
         */
        public static Environment parseStrict(String value) {
            if (value == null || value.isBlank()) {
                return production;
            }
            return switch (value.trim().toLowerCase()) {
                // "unrestricted"/"restricted" are the v5 names, both folded into production
                case "production", "unrestricted", "restricted" -> production;
                case "test" -> test;
                default -> throw new IllegalArgumentException("Unknown environment '" + value + "'. Valid values: " + VALID_ENVIRONMENTS);
            };
        }

        /**
         * Backwards-compatible deserialization, used by Jackson ({@code @JsonCreator})
         * and by JAX-RS parameter conversion. Delegates the known mappings to
         * {@link #parseStrict(String)} and, unlike it, falls back to
         * {@link #production} for an unknown value — but logs a warning first, so the
         * fallback is never silent.
         * <p>
         * {@code unrestricted} and {@code restricted} predate this two-value enum and
         * still appear in stored documents and exported ZIPs; both map to
         * {@link #production}. They are aliases, not environments — {@code production}
         * and {@code test} are the only values worth offering a caller.
         * <p>
         * The fallback is deliberate and must stay: deployment documents persisted by
         * older versions are read back through this method, and a hard failure here
         * would make a single malformed row break reading the whole deployment table
         * (and with it the redeploy-on-startup pass). Strictness belongs at the
         * boundaries where an environment is <em>acted on</em> — those call
         * {@link #parseStrict(String)}.
         */
        @JsonCreator
        public static Environment fromString(String value) {
            try {
                return parseStrict(value);
            } catch (IllegalArgumentException e) {
                LOGGER.warnv("Unknown environment ''{0}'' — falling back to ''{1}''. Valid values: {2}", LogSanitizer.sanitize(value), production,
                        VALID_ENVIRONMENTS);
                return production;
            }
        }

        @JsonValue
        public String toValue() {
            return name();
        }
    }

    public enum Status {
        READY, IN_PROGRESS, NOT_FOUND, ERROR
    }
}
