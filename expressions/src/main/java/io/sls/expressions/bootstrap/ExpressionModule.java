package io.sls.expressions.bootstrap;

import com.google.inject.Scopes;
import io.sls.expressions.ExpressionFactory;
import io.sls.expressions.IExpressionFactory;
import io.sls.expressions.utilities.ExpressionUtilities;
import io.sls.expressions.utilities.IExpressionUtilities;
import io.sls.runtime.bootstrap.AbstractBaseModule;

/**
 * Created by jariscgr on 09.08.2016.
 */
public class ExpressionModule extends AbstractBaseModule {
    @Override
    protected void configure() {
        bind(IExpressionUtilities.class).to(ExpressionUtilities.class).in(Scopes.SINGLETON);
        bind(IExpressionFactory.class).to(ExpressionFactory.class).in(Scopes.SINGLETON);
    }
}
