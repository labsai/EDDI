/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.memory.model.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.ANNOTATION_TYPE;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * Bounds a base64-encoded inline attachment payload carried on a request body.
 * <p>
 * The ceiling is <em>not</em> hardcoded: it is derived from the deployment's
 * existing {@code eddi.attachments.max-size-bytes} setting (the same cap the
 * blob stores enforce on decoded bytes), so an operator who raises the
 * attachment limit automatically raises what a request body may carry. See
 * {@link MaxInlineAttachmentSizeValidator}.
 * <p>
 * Why a declarative constraint and not just the store's check: the store's cap
 * is applied <em>after</em> {@code Base64.getDecoder().decode(...)} has already
 * allocated the full byte array, and a store failure is logged and the
 * attachment silently skipped. Rejecting at the request boundary bounds the
 * allocation and turns a silent drop into a field-level 400.
 *
 * @since 6.2.0
 */
@Documented
@Constraint(validatedBy = MaxInlineAttachmentSizeValidator.class)
@Target({FIELD, METHOD, PARAMETER, ANNOTATION_TYPE})
@Retention(RUNTIME)
public @interface MaxInlineAttachmentSize {

    String message() default "'data' exceeds the configured maximum inline attachment size "
            + "(eddi.attachments.max-size-bytes)";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
