/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.nlp.expressions.utilities;

import ai.labs.eddi.modules.nlp.expressions.Expression;
import ai.labs.eddi.modules.nlp.expressions.Expressions;
import ai.labs.eddi.modules.nlp.expressions.value.Value;

import java.util.List;

public interface IExpressionProvider {
    Expression createExpression(String predicate, Object... values);

    Expressions parseExpressions(String expressions);

    /**
     * The longest expression string this provider reads back; {@code 0} when it
     * does not bound the length. A caller that builds an expression string from
     * user text keeps it below this.
     */
    default int maxExpressionLength() {
        return 0;
    }

    Expression parseExpression(String expression);

    void extractAllValues(Expression exp, List<Value> ret);
}
