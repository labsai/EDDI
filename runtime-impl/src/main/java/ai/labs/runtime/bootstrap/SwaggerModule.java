package ai.labs.runtime.bootstrap;

import ai.labs.runtime.SwaggerServletContextListener;
import com.google.inject.multibindings.Multibinder;
import io.swagger.jaxrs.listing.ApiListingResource;
import io.swagger.jaxrs.listing.SwaggerSerializers;
import lombok.extern.slf4j.Slf4j;

import javax.servlet.ServletContextListener;
import java.io.InputStream;

/**
 * @author ginccc
 */
@Slf4j
public final class SwaggerModule extends AbstractBaseModule {
    private final InputStream[] configFiles;

    public SwaggerModule(InputStream... configFiles) {
        this.configFiles = configFiles;
    }

    @Override
    protected void configure() {
        registerConfigFiles(this.configFiles);


        Multibinder.newSetBinder(binder(), ServletContextListener.class)
                .addBinding().to(SwaggerServletContextListener.class);
        bind(ApiListingResource.class);
        bind(SwaggerSerializers.class);
    }
}
