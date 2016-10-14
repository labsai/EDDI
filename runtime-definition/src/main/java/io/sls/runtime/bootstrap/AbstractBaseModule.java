package io.sls.runtime.bootstrap;

import com.google.inject.AbstractModule;
import com.google.inject.name.Names;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Created by jariscgr on 09.08.2016.
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
            for (InputStream configFile : configFiles) {
                Properties properties = new Properties();
                properties.load(configFile);
                Names.bindProperties(binder(), properties);
            }
        } catch (IOException e) {
            /*logger.error(e.getLocalizedMessage(), e);*/
        }
    }
}
