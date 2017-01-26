package ai.labs.runtime.bootstrap;

import ai.labs.runtime.BaseRuntime;
import ai.labs.runtime.SystemRuntime;
import com.google.inject.Provides;
import com.google.inject.TypeLiteral;
import com.google.inject.matcher.AbstractMatcher;
import com.google.inject.spi.InjectionListener;
import com.google.inject.spi.TypeEncounter;
import com.google.inject.spi.TypeListener;

import javax.inject.Named;
import javax.inject.Singleton;
import java.io.InputStream;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * @author ginccc
 */
public class RuntimeModule extends AbstractBaseModule {
    private final InputStream[] configFiles;

    public RuntimeModule(InputStream... configFiles) {
        this.configFiles = configFiles;
    }

    @Override
    protected void configure() {
        registerConfigFiles(this.configFiles);

        //init system runtime eagerly
        bind(SystemRuntime.IRuntime.class).to(BaseRuntime.class).asEagerSingleton();

        //call init method of system runtime after creation
        bindListener(HasInitMethod.INSTANCE, new TypeListener() {
            public <I> void hear(TypeLiteral<I> type, TypeEncounter<I> encounter) {
                encounter.register(InitInvoker.INSTANCE);
            }
        });
    }

    @Provides
    @Singleton
    private ExecutorService provideExecutorService(@Named("threads.corePoolSize") int corePoolSize,
                                                   @Named("threads.maximumPoolSize") int maximumPoolSize,
                                                   @Named("threads.keepAliveTimeInSeconds") int keepAliveTimeInSeconds,
                                                   @Named("threads.queueSize") int queueSize) {
        try {
            return new ThreadPoolExecutor(corePoolSize, // core size
                    maximumPoolSize, // max size
                    keepAliveTimeInSeconds, // idle timeout
                    TimeUnit.SECONDS,
                    new LinkedBlockingQueue<>(queueSize <= -1 ? Integer.MAX_VALUE : queueSize)); // queue with a size
        } catch (Exception e) {
            throw new RuntimeException(e.getLocalizedMessage(), e);
        }
    }

    static class HasInitMethod extends AbstractMatcher<TypeLiteral<?>> {
        public static final HasInitMethod INSTANCE = new HasInitMethod();

        public boolean matches(TypeLiteral<?> tpe) {
            try {
                return tpe.getRawType().getMethod("init") != null;
            } catch (Exception e) {
                return false;
            }
        }
    }

    static class InitInvoker implements InjectionListener {
        public static final InitInvoker INSTANCE = new InitInvoker();

        public void afterInjection(Object injectee) {
            try {
                Class<?> clazz = injectee.getClass();
                clazz.getMethod("init").invoke(injectee);
            } catch (Exception e) {
                System.out.println(Arrays.toString(e.getStackTrace()));
            }
        }
    }
}

