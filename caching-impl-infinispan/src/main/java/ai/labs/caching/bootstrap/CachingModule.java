package ai.labs.caching.bootstrap;

import ai.labs.caching.ICacheFactory;
import ai.labs.caching.impl.CacheFactory;
import ai.labs.runtime.bootstrap.AbstractBaseModule;
import com.google.inject.Provides;
import com.google.inject.Scopes;
import com.google.inject.Singleton;

import java.io.InputStream;

/**
 * @author ginccc
 */
public class CachingModule extends AbstractBaseModule {
    private final InputStream cacheConfig;

    public CachingModule(InputStream cacheConfig) {
        this.cacheConfig = cacheConfig;
    }


    @Override
    protected void configure() {
        bind(ICacheFactory.class).to(CacheFactory.class).in(Scopes.SINGLETON);
    }

    @Provides
    @Singleton
    private CacheFactory provideCacheFactory() {
        return new CacheFactory(cacheConfig);
    }
}
