package ai.labs.bootstrap;

import ai.labs.group.IGroupStore;
import ai.labs.group.impl.mongo.GroupStore;
import ai.labs.group.impl.rest.RestGroupStore;
import ai.labs.group.rest.IRestGroupStore;
import ai.labs.runtime.bootstrap.AbstractBaseModule;
import ai.labs.user.IUserStore;
import ai.labs.user.impl.mongo.UserStore;
import ai.labs.user.impl.rest.RestUserStore;
import ai.labs.user.rest.IRestUserStore;
import com.google.inject.Scopes;

/**
 * @author ginccc
 */
public class UserModule extends AbstractBaseModule {
    @Override
    protected void configure() {
        bind(IGroupStore.class).to(GroupStore.class).in(Scopes.SINGLETON);
        bind(IUserStore.class).to(UserStore.class).in(Scopes.SINGLETON);

        bind(IRestGroupStore.class).to(RestGroupStore.class).in(Scopes.SINGLETON);
        bind(IRestUserStore.class).to(RestUserStore.class).in(Scopes.SINGLETON);
    }
}
