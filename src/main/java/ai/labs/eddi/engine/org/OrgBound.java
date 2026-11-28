/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.org;

import jakarta.interceptor.InterceptorBinding;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Binds the request's org around every business method of the annotated bean
 * ({@link OrgBoundInterceptor}, plan §6.2).
 * <p>
 * It goes on JAX-RS <em>implementation</em> classes and MCP tool classes, never
 * on {@code IRest*} interfaces: interceptor bindings on an interface are not
 * inherited by its implementation.
 */
@Inherited
@InterceptorBinding
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface OrgBound {
}
