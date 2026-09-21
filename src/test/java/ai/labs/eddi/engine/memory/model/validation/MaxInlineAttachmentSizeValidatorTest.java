/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.memory.model.validation;

import jakarta.validation.ConstraintValidatorContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for the config-driven ceiling on inline base64 attachment
 * payloads. The point of the constraint is that the limit is NOT a magic
 * number: it is derived from {@code eddi.attachments.max-size-bytes}, the cap
 * the blob stores already enforce on decoded bytes.
 */
class MaxInlineAttachmentSizeValidatorTest {

    private static MaxInlineAttachmentSizeValidator validatorWithCap(long maxDecodedBytes) {
        var validator = new MaxInlineAttachmentSizeValidator();
        validator.maxDecodedBytes = maxDecodedBytes;
        return validator;
    }

    private static ConstraintValidatorContext context() {
        return mock(ConstraintValidatorContext.class, RETURNS_DEEP_STUBS);
    }

    @Test
    @DisplayName("base64 length is 4 characters per 3 bytes, rounded up to the 4-character quantum")
    void base64LengthMath() {
        assertEquals(0, MaxInlineAttachmentSizeValidator.base64LengthFor(0));
        assertEquals(4, MaxInlineAttachmentSizeValidator.base64LengthFor(1));
        assertEquals(4, MaxInlineAttachmentSizeValidator.base64LengthFor(3));
        assertEquals(8, MaxInlineAttachmentSizeValidator.base64LengthFor(4));
        // The 20 MB default of eddi.attachments.max-size-bytes.
        assertEquals(27_962_028L,
                MaxInlineAttachmentSizeValidator.base64LengthFor(
                        MaxInlineAttachmentSizeValidator.DEFAULT_MAX_DECODED_BYTES));
    }

    @Test
    @DisplayName("the ceiling tracks the configured attachment size — it is not hardcoded")
    void ceilingIsDerivedFromConfiguration() {
        assertEquals(12, validatorWithCap(9).maxBase64Length());
        assertEquals(400, validatorWithCap(300).maxBase64Length());
        // A non-positive / uninjected value falls back to the documented default
        // instead of rejecting everything.
        assertEquals(27_962_028L, validatorWithCap(0).maxBase64Length());
        assertEquals(27_962_028L, validatorWithCap(-1).maxBase64Length());
    }

    @Test
    @DisplayName("a payload at the derived ceiling is accepted")
    void payloadAtCeilingIsValid() {
        var validator = validatorWithCap(9); // → 12 base64 characters
        var ctx = context();

        assertTrue(validator.isValid("a".repeat(12), ctx));

        verify(ctx, never()).disableDefaultConstraintViolation();
    }

    @Test
    @DisplayName("a payload one character over the derived ceiling is rejected, naming the config key")
    void payloadOverCeilingIsRejected() {
        var validator = validatorWithCap(9); // → 12 base64 characters
        var ctx = context();

        assertFalse(validator.isValid("a".repeat(13), ctx));

        verify(ctx).disableDefaultConstraintViolation();
        var template = ArgumentCaptor.forClass(String.class);
        verify(ctx).buildConstraintViolationWithTemplate(template.capture());
        assertTrue(template.getValue().contains("13"), template.getValue());
        assertTrue(template.getValue().contains("12"), template.getValue());
        assertTrue(template.getValue().contains("eddi.attachments.max-size-bytes"), template.getValue());
        // The message must never interpolate caller-supplied text or an EL
        // expression — it is composed of computed numbers only.
        assertFalse(template.getValue().contains("${"), template.getValue());
    }

    @Test
    @DisplayName("absent data is not this constraint's concern")
    void nullIsValid() {
        assertTrue(validatorWithCap(9).isValid(null, context()));
    }

    @Test
    @DisplayName("raising the configured attachment size raises what a request body may carry")
    void raisingConfigurationRaisesTheLimit() {
        String payload = "a".repeat(13);

        assertFalse(validatorWithCap(9).isValid(payload, context()));
        assertTrue(validatorWithCap(300).isValid(payload, context()));
    }
}
