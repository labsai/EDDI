package ai.labs.expressions.bootstrap;

import ai.labs.expressions.ExpressionFactory;
import ai.labs.expressions.IExpressionFactory;
import ai.labs.expressions.utilities.ExpressionProvider;
import ai.labs.expressions.utilities.IExpressionProvider;
import ai.labs.runtime.bootstrap.AbstractBaseModule;
import com.google.inject.Scopes;

/**
 * @author ginccc
 */
public class ExpressionModule extends AbstractBaseModule {
    @Override
    protected void configure() {
        bind(IExpressionProvider.class).to(ExpressionProvider.class).in(Scopes.SINGLETON);
        bind(IExpressionFactory.class).to(ExpressionFactory.class).in(Scopes.SINGLETON);
    }
}
