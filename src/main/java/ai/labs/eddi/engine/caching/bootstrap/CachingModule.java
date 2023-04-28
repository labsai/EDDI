package ai.labs.eddi.engine.caching.bootstrap;

import org.infinispan.manager.DefaultCacheManager;
import org.infinispan.manager.EmbeddedCacheManager;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.Produces;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * @author ginccc
 */
@ApplicationScoped
public class CachingModule {
    @Produces
    @ApplicationScoped
    EmbeddedCacheManager provideEmbeddedCacheManager() throws IOException {
        Path path = Paths.get("infinispan.xml");
        return new DefaultCacheManager(path.toString(), true);
    }
}
