package ai.labs.eddi.engine.caching.bootstrap;

import org.infinispan.manager.DefaultCacheManager;
import org.infinispan.manager.EmbeddedCacheManager;

import javax.enterprise.context.ApplicationScoped;
import javax.ws.rs.Produces;
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
        Path path = Paths.get("src", "main", "resources", "infinispan.xml");
        return new DefaultCacheManager(path.toString(), true);
    }
}
