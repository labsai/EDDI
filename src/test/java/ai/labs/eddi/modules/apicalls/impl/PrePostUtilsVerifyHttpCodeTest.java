/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.apicalls.impl;

import ai.labs.eddi.configs.apicalls.model.HttpCodeValidator;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.memory.IDataFactory;
import ai.labs.eddi.engine.memory.IMemoryItemConverter;
import ai.labs.eddi.modules.templating.ITemplatingEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for {@link PrePostUtils#verifyHttpCode(HttpCodeValidator, int)} —
 * a pure function with no side effects.
 */
class PrePostUtilsVerifyHttpCodeTest {

    private PrePostUtils prePostUtils;

    @BeforeEach
    void setUp() {
        prePostUtils = new PrePostUtils(
                mock(IJsonSerialization.class),
                mock(IMemoryItemConverter.class),
                mock(ITemplatingEngine.class),
                mock(IDataFactory.class));
    }

    @Nested
    @DisplayName("verifyHttpCode with DEFAULT validator")
    class DefaultValidator {

        @Test
        @DisplayName("should accept 200 with default validator")
        void accept200() {
            assertTrue(prePostUtils.verifyHttpCode(HttpCodeValidator.DEFAULT, 200));
        }

        @Test
        @DisplayName("should accept 201 with default validator")
        void accept201() {
            assertTrue(prePostUtils.verifyHttpCode(HttpCodeValidator.DEFAULT, 201));
        }

        @Test
        @DisplayName("should reject 400 with default validator")
        void reject400() {
            assertFalse(prePostUtils.verifyHttpCode(HttpCodeValidator.DEFAULT, 400));
        }

        @Test
        @DisplayName("should reject 500 with default validator")
        void reject500() {
            assertFalse(prePostUtils.verifyHttpCode(HttpCodeValidator.DEFAULT, 500));
        }

        @Test
        @DisplayName("should reject 404 with default validator")
        void reject404() {
            assertFalse(prePostUtils.verifyHttpCode(HttpCodeValidator.DEFAULT, 404));
        }

        @Test
        @DisplayName("should reject code not in any list (e.g. 301)")
        void rejectUnlisted() {
            // 301 is neither in runOnHttpCode nor skipOnHttpCode
            assertFalse(prePostUtils.verifyHttpCode(HttpCodeValidator.DEFAULT, 301));
        }
    }

    @Nested
    @DisplayName("verifyHttpCode with null validator")
    class NullValidator {

        @Test
        @DisplayName("should use DEFAULT when validator is null")
        void nullUsesDefault() {
            // null validator → falls back to DEFAULT
            assertTrue(prePostUtils.verifyHttpCode(null, 200));
            assertFalse(prePostUtils.verifyHttpCode(null, 500));
        }
    }

    @Nested
    @DisplayName("verifyHttpCode with custom validator")
    class CustomValidator {

        @Test
        @DisplayName("should accept custom runOn codes")
        void customRunOn() {
            var validator = new HttpCodeValidator(List.of(202, 204), List.of());
            assertTrue(prePostUtils.verifyHttpCode(validator, 202));
            assertTrue(prePostUtils.verifyHttpCode(validator, 204));
            assertFalse(prePostUtils.verifyHttpCode(validator, 200)); // not in runOn
        }

        @Test
        @DisplayName("should reject code that is in both runOn AND skipOn")
        void codeInBothLists() {
            // If a code is in both runOnHttpCode AND skipOnHttpCode, skipOn wins
            var validator = new HttpCodeValidator(List.of(200), List.of(200));
            assertFalse(prePostUtils.verifyHttpCode(validator, 200));
        }

        @Test
        @DisplayName("should use defaults when runOnHttpCode is null")
        void nullRunOn() {
            var validator = new HttpCodeValidator();
            validator.setSkipOnHttpCode(List.of(500));
            // runOnHttpCode is null → should use DEFAULT runOn (200, 201)
            assertTrue(prePostUtils.verifyHttpCode(validator, 200));
        }

        @Test
        @DisplayName("should use defaults when skipOnHttpCode is null")
        void nullSkipOn() {
            var validator = new HttpCodeValidator();
            validator.setRunOnHttpCode(List.of(200, 201));
            // skipOnHttpCode is null → should use DEFAULT skipOn
            assertTrue(prePostUtils.verifyHttpCode(validator, 200));
        }
    }
}
