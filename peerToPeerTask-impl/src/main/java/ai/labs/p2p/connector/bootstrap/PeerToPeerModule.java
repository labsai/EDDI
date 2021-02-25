package ai.labs.p2p.connector.bootstrap;

import ai.labs.lifecycle.ILifecycleTask;
import ai.labs.p2p.connector.impl.PeerToPeerTask;
import ai.labs.runtime.bootstrap.AbstractBaseModule;
import com.google.inject.multibindings.MapBinder;


/**
 * @author rpi
 */
public class PeerToPeerModule extends AbstractBaseModule {
    @Override
    protected void configure() {
        MapBinder<String, ILifecycleTask> lifecycleTaskPlugins
                = MapBinder.newMapBinder(binder(), String.class, ILifecycleTask.class);
        lifecycleTaskPlugins.addBinding("ai.labs.p2p").to(PeerToPeerTask.class);
    }
}
