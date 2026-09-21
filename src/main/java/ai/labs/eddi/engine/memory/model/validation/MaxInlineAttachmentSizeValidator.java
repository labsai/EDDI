/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.memory.model.validation;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Validates {@link MaxInlineAttachmentSize} by deriving the character ceiling
 * from the deployment's configured attachment size cap
 * ({@code eddi.attachments.max-size-bytes}, the same key
 * {@code GridFsAttachmentStore} / {@code PostgresAttachmentStore} enforce on
 * decoded bytes). Nothing here is a new magic number — the limit is whatever
 * the operator already chose for attachments.
 *
 * @since 6.2.0
 */
@ApplicationScoped
public class MaxInlineAttachmentSizeValidator implements ConstraintValidator<MaxInlineAttachmentSize, CharSequence> {

    /**
     * Mirrors the {@code eddi.attachments.max-size-bytes} default (20 MB). Used
     * when the property is absent or non-positive, and when the validator is
     * constructed outside CDI (no injection) — the same
     * {@code configured > 0 ? configured : DEFAULT} guard
     * {@code AttachmentTextExtractor} uses.
     */
    public static final long DEFAULT_MAX_DECODED_BYTES = 20_971_520L;

    @ConfigProperty(name = "eddi.attachments.max-size-bytes", defaultValue = "20971520") // 20 MB
    long maxDecodedBytes;

    /**
     * Number of base64 characters a payload of {@code decodedBytes} bytes encodes
     * to: 4 output characters per 3 input bytes, rounded up to the next 4-character
     * quantum (padding included).
     */
    static long base64LengthFor(long decodedBytes) {
        return 4L * ((decodedBytes + 2L) / 3L);
    }

    /**
     * The effective ceiling in base64 characters. MIME line breaks are deliberately
     * not budgeted for: the engine decodes with {@code Base64.getDecoder()} (the
     * basic decoder), which rejects line breaks outright, so a line-wrapped payload
     * could never be materialized regardless of this limit.
     */
    long maxBase64Length() {
        long decoded = maxDecodedBytes > 0 ? maxDecodedBytes : DEFAULT_MAX_DECODED_BYTES;
        return base64LengthFor(decoded);
    }

    @Override
    public boolean isValid(CharSequence value, ConstraintValidatorContext context) {
        // Absence is a separate concern (@NotBlank / the "either data or url" rule).
        if (value == null) {
            return true;
        }
        long limit = maxBase64Length();
        if (value.length() <= limit) {
            return true;
        }
        if (context != null) {
            // The template is composed exclusively of computed numbers — no caller
            // supplied text is interpolated, so this is not a reflected-value sink and
            // carries no EL expression.
            context.disableDefaultConstraintViolation();
            context.buildConstraintViolationWithTemplate(
                    "inline base64 attachment data is too large: " + value.length()
                            + " characters exceeds the limit of " + limit
                            + " (raise 'eddi.attachments.max-size-bytes' to allow larger inline files)")
                    .addConstraintViolation();
        }
        return false;
    }
}
