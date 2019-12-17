package ai.labs.runtime;

import com.google.inject.Module;
import com.google.inject.*;

/**
 * @author ginccc
 */
public class DependencyInjector {
    public enum Environment {
        DEVELOPMENT,
        PRODUCTION
    }

    private static DependencyInjector instance = null;
    private static Injector injector;

    private static boolean isInit = false;

    public static DependencyInjector init(Environment environment, Module... modules) throws DependencyInjectorException {
        if (isInit) {
            throw new DependencyInjectorException("DependencyInjector has already been initialized.");
        }

        instance = new DependencyInjector(environment, modules);
        return instance;
    }

    public static boolean isInitialized() {
        return isInit;
    }

    public static DependencyInjector getInstance() {
        return instance;
    }

    private DependencyInjector(Environment environment, Module... internalModules) {
        injector = Guice.createInjector(Stage.valueOf(environment.toString()), internalModules);
        injector.getAllBindings();
        isInit = true;
    }

    public <T> T getInstance(Class<T> clazz) {
        return injector.getInstance(clazz);
    }

    public <T> T getInstance(Key<T> key) {
        return injector.getInstance(key);
    }

    public static class DependencyInjectorException extends Exception {
        DependencyInjectorException(String message) {
            super(message);
        }
    }
}
