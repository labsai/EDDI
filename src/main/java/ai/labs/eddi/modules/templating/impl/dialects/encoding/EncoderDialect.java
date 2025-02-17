package ai.labs.eddi.modules.templating.impl.dialects.encoding;

import org.thymeleaf.dialect.AbstractDialect;
import org.thymeleaf.dialect.IExpressionObjectDialect;
import org.thymeleaf.expression.IExpressionObjectFactory;

public class EncoderDialect extends AbstractDialect implements IExpressionObjectDialect {
    public EncoderDialect() {
        super("Encoder");
    }

    @Override
    public IExpressionObjectFactory getExpressionObjectFactory() {
        return new EncoderExpressionObjectFactory();
    }
}
