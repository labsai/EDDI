package ai.labs.injection;

import io.quarkus.arc.Arc;
import io.quarkus.arc.ArcContainer;

/**
 * @author ginccc
 */
public class DependencyInjector {
    public enum Environment {
        DEVELOPMENT,
        PRODUCTION
    }

    private static DependencyInjector instance = null;
    private static ArcContainer injector;

    private static boolean isInit = false;

    public static DependencyInjector init() throws DependencyInjectorException {
        if (isInit) {
            throw new DependencyInjectorException("DependencyInjector has already been initialized.");
        }

        instance = new DependencyInjector();
        return instance;
    }

    public static boolean isInitialized() {
        return isInit;
    }

    public static DependencyInjector getInstance() {
        return instance;
    }

    private DependencyInjector() {
        injector = Arc.container();
        isInit = true;
    }

    public <T> T getInstance(Class<T> clazz) {
        return injector.instance(clazz).get();
    }


    public static class DependencyInjectorException extends Exception {
        DependencyInjectorException(String message) {
            super(message);
        }
    }
}
