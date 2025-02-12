package ai.labs.eddi.modules.templating.impl.dialects.json;

import org.thymeleaf.dialect.AbstractDialect;
import org.thymeleaf.dialect.IExpressionObjectDialect;
import org.thymeleaf.expression.IExpressionObjectFactory;

public class JsonDialect extends AbstractDialect implements IExpressionObjectDialect {
    public JsonDialect() {
        super("JsonDialect");
    }

    @Override
    public IExpressionObjectFactory getExpressionObjectFactory() {
        return new JsonExpressionObjectFactory();
    }
}
