package ai.labs.eddi.modules.templating.impl.dialects.encoding;

import org.thymeleaf.context.IExpressionContext;
import org.thymeleaf.expression.IExpressionObjectFactory;

import java.util.Collections;
import java.util.Set;

public class EncoderExpressionObjectFactory implements IExpressionObjectFactory {
    private static final String ENCODER_OBJECT_NAME = "encoder";

    @Override
    public Set<String> getAllExpressionObjectNames() {
        return Collections.singleton(ENCODER_OBJECT_NAME);
    }

    @Override
    public Object buildObject(IExpressionContext context, String expressionObjectName) {
        if (ENCODER_OBJECT_NAME.equals(expressionObjectName)) {
            return new EncoderWrapper();
        }

        return null;
    }

    @Override
    public boolean isCacheable(String expressionObjectName) {
        return true;
    }
}
