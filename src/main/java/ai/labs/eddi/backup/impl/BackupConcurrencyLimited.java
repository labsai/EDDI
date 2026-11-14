/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.backup.impl;

import jakarta.interceptor.InterceptorBinding;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Runs every call into the annotated bean under {@link BackupConcurrencyGate}:
 * at most {@value BackupConcurrencyGate#MAX_CONCURRENT_PROPERTY} imports,
 * previews and syncs at once, the rest refused with {@code 429}. See
 * {@link BackupConcurrencyInterceptor}.
 */
@InterceptorBinding
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface BackupConcurrencyLimited {
}
