/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.nlp.expressions;

/**
 * @author ginccc
 */
public interface IExpressionFactory {
    Expression[] getExpression(Expression... expressions);

    Expression getExpression(Expression exp);
}
