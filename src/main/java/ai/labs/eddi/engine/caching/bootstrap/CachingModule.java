package ai.labs.eddi.engine.caching.bootstrap;

import org.infinispan.configuration.global.GlobalConfigurationBuilder;
import org.infinispan.manager.DefaultCacheManager;
import org.infinispan.manager.EmbeddedCacheManager;

import javax.enterprise.context.ApplicationScoped;
import javax.ws.rs.Produces;

/**
 * @author ginccc
 */
@ApplicationScoped
public class CachingModule {
    @Produces
    @ApplicationScoped
    EmbeddedCacheManager provideEmbeddedCacheManager() {
        GlobalConfigurationBuilder global = GlobalConfigurationBuilder.defaultClusteredBuilder();
        return new DefaultCacheManager(global.build());
    }
}
