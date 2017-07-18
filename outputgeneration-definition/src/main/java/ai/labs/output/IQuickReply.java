package ai.labs.output;

import ai.labs.expressions.Expression;

import java.util.List;

/**
 * @author ginccc
 */
public interface IQuickReply {
    String getValue();

    List<Expression> getExpressions();
}
