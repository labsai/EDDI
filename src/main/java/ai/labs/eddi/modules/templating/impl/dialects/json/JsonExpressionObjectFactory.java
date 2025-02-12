package ai.labs.eddi.modules.templating.impl.dialects.json;

import org.thymeleaf.context.IExpressionContext;
import org.thymeleaf.expression.IExpressionObjectFactory;

import java.util.Collections;
import java.util.Set;

public class JsonExpressionObjectFactory implements IExpressionObjectFactory {
    private static final String JSON_OBJECT_NAME = "json";

    @Override
    public Set<String> getAllExpressionObjectNames() {
        return Collections.singleton(JSON_OBJECT_NAME);
    }

    @Override
    public Object buildObject(IExpressionContext context, String expressionObjectName) {
        if (JSON_OBJECT_NAME.equals(expressionObjectName)) {
            return new JsonWrapper();
        }

        return null;
    }

    @Override
    public boolean isCacheable(String expressionObjectName) {
        return true;
    }
}
