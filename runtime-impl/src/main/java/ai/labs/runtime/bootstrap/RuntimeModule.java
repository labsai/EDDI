package ai.labs.runtime.bootstrap;

import ai.labs.runtime.*;
import ai.labs.runtime.client.bots.BotStoreClientLibrary;
import ai.labs.runtime.client.bots.IBotStoreClientLibrary;
import ai.labs.runtime.client.configuration.IResourceClientLibrary;
import ai.labs.runtime.client.configuration.ResourceClientLibrary;
import ai.labs.runtime.client.packages.IPackageStoreClientLibrary;
import ai.labs.runtime.client.packages.PackageStoreClientLibrary;
import ai.labs.runtime.internal.AutoBotDeployment;
import ai.labs.runtime.internal.BotFactory;
import ai.labs.runtime.internal.PackageFactory;
import ai.labs.runtime.service.BotStoreService;
import ai.labs.runtime.service.IBotStoreService;
import ai.labs.runtime.service.IPackageStoreService;
import ai.labs.runtime.service.PackageStoreService;
import com.google.inject.Provides;
import com.google.inject.Scopes;
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
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;

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

        bind(IResourceClientLibrary.class).to(ResourceClientLibrary.class).in(Scopes.SINGLETON);
        bind(IBotStoreClientLibrary.class).to(BotStoreClientLibrary.class).in(Scopes.SINGLETON);
        bind(IPackageStoreClientLibrary.class).to(PackageStoreClientLibrary.class).in(Scopes.SINGLETON);
        bind(IPackageStoreService.class).to(PackageStoreService.class).in(Scopes.SINGLETON);
        bind(IBotStoreService.class).to(BotStoreService.class).in(Scopes.SINGLETON);

        bind(IBotFactory.class).to(BotFactory.class).in(Scopes.SINGLETON);
        bind(IPackageFactory.class).to(PackageFactory.class).in(Scopes.SINGLETON);

        bind(IAutoBotDeployment.class).to(AutoBotDeployment.class).in(Scopes.SINGLETON);

        //call init method of system runtime after creation
        bindListener(HasInitMethod.INSTANCE, new TypeListener() {
            public <I> void hear(TypeLiteral<I> type, TypeEncounter<I> encounter) {
                encounter.register(InitInvoker.INSTANCE);
            }
        });
    }

    @Provides
    @Singleton
    private ScheduledThreadPoolExecutor provideScheduledThreadPoolExecutor(@Named("threads.corePoolSize") int corePoolSize) {
        try {
            return new ScheduledThreadPoolExecutor(corePoolSize);
        } catch (Exception e) {
            throw new RuntimeException(e.getLocalizedMessage(), e);
        }
    }

    @Provides
    @Singleton
    private ThreadPoolExecutor provideThreadPoolExecutor(ScheduledThreadPoolExecutor threadPoolExecutor) {
        return threadPoolExecutor;
    }

    @Provides
    @Singleton
    private ExecutorService provideExecutorService(ScheduledThreadPoolExecutor threadPoolExecutor) {
        return threadPoolExecutor;
    }

    static class HasInitMethod extends AbstractMatcher<TypeLiteral<?>> {
        static final HasInitMethod INSTANCE = new HasInitMethod();

        public boolean matches(TypeLiteral<?> tpe) {
            try {
                return tpe.getRawType().getMethod("init") != null;
            } catch (Exception e) {
                return false;
            }
        }
    }

    static class InitInvoker implements InjectionListener {
        static final InitInvoker INSTANCE = new InitInvoker();

        public void afterInjection(Object injectee) {
            try {
                Class<?> clazz = injectee.getClass();
                if (clazz.equals(BaseRuntime.class)) {
                    clazz.getMethod("init").invoke(injectee);
                }
            } catch (Exception e) {
                System.out.println(Arrays.toString(e.getStackTrace()));
            }
        }
    }
}

