package ai.labs.eddi.modules.templating.impl.dialects.uuid;

import org.thymeleaf.context.IExpressionContext;
import org.thymeleaf.expression.IExpressionObjectFactory;

import java.util.Collections;
import java.util.Set;

public class UUIDExpressionObjectFactory implements IExpressionObjectFactory {
    private static final String UUID_OBJECT_NAME = "uuidUtils";

    @Override
    public Set<String> getAllExpressionObjectNames() {
        return Collections.singleton(UUID_OBJECT_NAME);
    }

    @Override
    public Object buildObject(IExpressionContext context, String expressionObjectName) {
        if (UUID_OBJECT_NAME.equals(expressionObjectName)) {
            return new UUIDWrapper();
        }

        return null;
    }

    @Override
    public boolean isCacheable(String expressionObjectName) {
        return true;
    }
}
