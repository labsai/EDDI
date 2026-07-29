/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * @author ginccc
 */
public class Deployment {
    public enum Environment {
        production, test;

        /**
         * Backwards-compatible deserialization of the legacy environment names.
         * <p>
         * {@code unrestricted} and {@code restricted} predate this two-value enum and
         * still appear in stored documents and exported ZIPs; both map to
         * {@link #production}. An unrecognised or {@code null} value maps there too
         * rather than throwing, so an old document can never block a deployment from
         * loading.
         * <p>
         * These are aliases, not environments: {@code production} and {@code test} are
         * the only values worth offering a caller.
         */
        @JsonCreator
        public static Environment fromString(String value) {
            if (value == null) {
                return production;
            }
            return switch (value.toLowerCase()) {
                case "unrestricted", "restricted" -> production;
                case "production" -> production;
                case "test" -> test;
                default -> production;
            };
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
