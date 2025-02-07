package ai.labs.eddi.modules.templating.impl.dialects.uuid;

import org.thymeleaf.dialect.AbstractDialect;
import org.thymeleaf.dialect.IExpressionObjectDialect;
import org.thymeleaf.expression.IExpressionObjectFactory;

public class UUIDDialect extends AbstractDialect implements IExpressionObjectDialect {
    public UUIDDialect() {
        super("UUIDDialect");
    }

    @Override
    public IExpressionObjectFactory getExpressionObjectFactory() {
        return new UUIDExpressionObjectFactory();
    }
}
