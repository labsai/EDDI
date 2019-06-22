package ai.labs.runtime.bootstrap;

import com.google.inject.AbstractModule;
import com.google.inject.name.Names;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Properties;

/**
 * @author ginccc
 */

public abstract class AbstractBaseModule extends AbstractModule {
    protected final InputStream[] configFiles;

    public AbstractBaseModule() {
        configFiles = new InputStream[0];
    }

    public AbstractBaseModule(InputStream... configFiles) {
        this.configFiles = configFiles;
    }

    protected void registerConfigFiles(InputStream... configFiles) {
        try {
            /**
             * Allow *.properties config to be overridden by system properties
             */
            final Properties systemProperties = System.getProperties();
            for (InputStream configFile : configFiles) {
                Properties properties = new Properties();
                properties.load(configFile);
                properties.entrySet().forEach(propertyEntry -> {
                    if (systemProperties.containsKey(propertyEntry.getKey())) {
                        propertyEntry.setValue(systemProperties.getProperty((String) propertyEntry.getKey()));
                    }
                });
                Names.bindProperties(binder(), properties);
                systemProperties.putAll(properties);
            }
        } catch (IOException e) {
            System.out.println(Arrays.toString(e.getStackTrace()));
        }
    }
}
