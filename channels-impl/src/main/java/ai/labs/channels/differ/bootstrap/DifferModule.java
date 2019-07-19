package ai.labs.channels.differ.bootstrap;

import ai.labs.channels.differ.*;
import ai.labs.runtime.bootstrap.AbstractBaseModule;
import com.google.inject.Scopes;

public class DifferModule extends AbstractBaseModule {
    @Override
    protected void configure() {
        bind(IDifferEndpoint.class).to(DifferEndpoint.class).in(Scopes.SINGLETON);
        bind(IDifferOutputTransformer.class).to(DifferOutputTransformer.class).in(Scopes.SINGLETON);
        bind(IDifferPublisher.class).to(DifferPublisher.class).in(Scopes.SINGLETON);
    }
}
