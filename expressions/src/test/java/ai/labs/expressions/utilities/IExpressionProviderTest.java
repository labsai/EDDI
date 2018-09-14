package ai.labs.expressions.utilities;

import ai.labs.expressions.Expression;
import ai.labs.expressions.ExpressionFactory;
import org.junit.Assert;
import org.junit.Test;

import java.util.List;

public class IExpressionProviderTest {

    @Test
    public void parseExpressions() {
        //setup
        IExpressionProvider expressionProvider = new ExpressionProvider(new ExpressionFactory());

        //test
        String expressions = "property(community_id(3243-23432-1-2121-12313)), property(topic_id(12w-21213211-122112-12121))";
        List<Expression> result = expressionProvider.parseExpressions(expressions);

        //assert
        Assert.assertEquals("community_id", result.get(0).getSubExpressions()[0].getExpressionName());
        Assert.assertEquals("3243-23432-1-2121-12313", result.get(0).getSubExpressions()[0].getSubExpressions()[0].getExpressionName());

        Assert.assertEquals("topic_id", result.get(1).getSubExpressions()[0].getExpressionName());
        Assert.assertEquals("12w-21213211-122112-12121", result.get(1).getSubExpressions()[0].getSubExpressions()[0].getExpressionName());
    }
}