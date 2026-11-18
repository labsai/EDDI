/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.nlp.expressions.value;

import ai.labs.eddi.modules.nlp.expressions.Expression;

/**
 * @author ginccc
 */
public class AnyValue extends Expression {
    /**
     * The name that makes an expression the wildcard: it equals every expression.
     */
    public static final String NAME = "*";

    public AnyValue() {
        this.expressionName = NAME;
    }
}
