package ai.labs.caching.bootstrap;

import ai.labs.caching.ICacheFactory;
import ai.labs.caching.impl.CacheFactory;
import com.google.inject.Scopes;
import io.sls.runtime.bootstrap.AbstractBaseModule;

/**
 * @author ginccc
 */
public class CachingModule extends AbstractBaseModule {

    @Override
    protected void configure() {
        bind(ICacheFactory.class).to(CacheFactory.class).in(Scopes.SINGLETON);
    }
}
