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
 * Marks code outside resource methods (filters, augmentors, exception mappers,
 * raw Vert.x routes; plan §6.2) that touches no org data and so needs no
 * binding. The reason is mandatory: the build guard accepts this marker in
 * place of a binding, so it must say why none is needed.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface OrgNeutral {

    /** Why this code touches no org data. */
    String value();
}
