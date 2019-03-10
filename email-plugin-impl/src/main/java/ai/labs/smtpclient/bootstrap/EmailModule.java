package ai.labs.smtpclient.bootstrap;


import ai.labs.lifecycle.ILifecycleTask;
import ai.labs.runtime.bootstrap.AbstractBaseModule;
import ai.labs.smtpclient.impl.SendEmailTask;
import ai.labs.smtpclient.impl.SendMail;
import ai.labs.stmpplugin.ISendMail;
import com.google.inject.Scopes;
import com.google.inject.multibindings.MapBinder;

public class EmailModule extends AbstractBaseModule {
    @Override
    protected void configure() {
        MapBinder<String, ILifecycleTask> lifecycleTaskPlugins
                = MapBinder.newMapBinder(binder(), String.class, ILifecycleTask.class);
        lifecycleTaskPlugins.addBinding("ai.labs.sendmail").to(SendEmailTask.class);

        bind(ISendMail.class).to(SendMail.class).in(Scopes.SINGLETON);
    }
}
