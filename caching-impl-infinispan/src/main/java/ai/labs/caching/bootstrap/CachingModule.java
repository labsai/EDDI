package ai.labs.caching.bootstrap;

import ai.labs.caching.ICacheFactory;
import ai.labs.caching.impl.CacheFactory;
import ai.labs.runtime.bootstrap.AbstractBaseModule;
import com.google.inject.Provides;
import com.google.inject.Scopes;
import com.google.inject.Singleton;
import org.infinispan.configuration.parsing.ParserRegistry;
import org.infinispan.manager.DefaultCacheManager;
import org.infinispan.manager.EmbeddedCacheManager;
import org.infinispan.persistence.mongodb.configuration.MongoDBStoreConfigurationBuilder;

import javax.inject.Named;
import java.io.InputStream;
import java.util.Arrays;
import java.util.stream.Collectors;

import static ai.labs.utilities.RuntimeUtilities.isNullOrEmpty;

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
    private EmbeddedCacheManager provideEmbeddedCacheManager(@Named("mongodb.hosts") String hosts,
                                                             @Named("mongodb.port") Integer port,
                                                             @Named("mongodb.source") String source,
                                                             @Named("mongodb.database") String database,
                                                             @Named("mongodb.username") String username,
                                                             @Named("mongodb.password") String password) {

        var builder = new ParserRegistry().parse(cacheConfig);
        var configurationBuilder = builder.getNamedConfigurationBuilders().get("differ.ackAwaitingCommands");

        configurationBuilder.persistence().
                addStore(MongoDBStoreConfigurationBuilder.class).
                connectionURI(createConnectionString(hosts, port, database, username, password, source)).
                collection("infinispan_cachestore_ackAwaitingCommands");

        return new DefaultCacheManager(builder, true);
    }

    private String createConnectionString(String hosts, Integer port, String database, String username, String password, String source) {
        if (!isNullOrEmpty(username) && !isNullOrEmpty(password)) {
            return String.format("mongodb://%s:%s@%s/%s?w=0&connectTimeoutMS=2000&authSource=%s", username, password, hostsToString(hosts, port), database, source);
        } else {
            return String.format("mongodb://%s/%s?w=0&connectTimeoutMS=2000", hostsToString(hosts, port), database);
        }
    }

    private static String hostsToString(String hosts, Integer port) {
        return Arrays.stream(hosts.split(",")).map(host -> host.trim() + ":" + port).collect(Collectors.joining());
    }
}
