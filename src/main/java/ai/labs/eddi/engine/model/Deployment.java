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
         * Backwards-compatible deserialization: "production" → production, "production"
         * → production
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
