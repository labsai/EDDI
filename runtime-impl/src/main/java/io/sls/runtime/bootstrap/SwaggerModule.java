package io.sls.runtime.bootstrap;

import com.google.inject.multibindings.Multibinder;
import com.google.inject.name.Names;
import com.google.inject.servlet.ServletModule;
import io.sls.runtime.SwaggerServletContextListener;
import io.swagger.jaxrs.listing.ApiListingResource;
import io.swagger.jaxrs.listing.SwaggerSerializers;
import lombok.extern.slf4j.Slf4j;

import javax.servlet.ServletContextListener;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * @author ginccc
 */
@Slf4j
public final class SwaggerModule extends ServletModule {
    private final InputStream[] configFiles;

    public SwaggerModule(InputStream... configFiles) {
        this.configFiles = configFiles;
    }

    @Override
    protected void configureServlets() {
        registerConfigFiles(this.configFiles);

        Multibinder.newSetBinder(binder(), ServletContextListener.class)
                .addBinding().to(SwaggerServletContextListener.class);
        bind(SwaggerSerializers.class);
        bind(ApiListingResource.class);
    }

    private void registerConfigFiles(InputStream[] configFiles) {
        try {
            for (InputStream configFile : configFiles) {
                Properties properties = new Properties();
                properties.load(configFile);
                Names.bindProperties(binder(), properties);
            }
        } catch (IOException e) {
            log.error(e.getLocalizedMessage(), e);
        }
    }
}
