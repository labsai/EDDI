/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.org;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a class or method that belongs to the platform plane (plan §9.3): it
 * reads or writes only platform data (Appendix B), or crosses orgs on purpose
 * through {@link OrgScope#platform}. The build guards (plan §14.2) accept it in
 * place of {@link OrgBound} on REST and MCP classes, filters and
 * {@code @Scheduled} methods.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface PlatformPlane {

    /** Why this belongs to the platform plane, when the name does not say it. */
    String value() default "";
}
