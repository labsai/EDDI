package io.sls.bootstrap;

import com.google.inject.Scopes;
import io.sls.group.IGroupStore;
import io.sls.group.impl.mongo.GroupStore;
import io.sls.group.impl.rest.RestGroupStore;
import io.sls.group.rest.IRestGroupStore;
import io.sls.runtime.bootstrap.AbstractBaseModule;
import io.sls.user.IUserStore;
import io.sls.user.impl.mongo.UserStore;
import io.sls.user.impl.rest.RestUserStore;
import io.sls.user.rest.IRestUserStore;

/**
 * Created by jariscgr on 09.08.2016.
 */
public class UserModule extends AbstractBaseModule {
    @Override
    protected void configure() {
        bind(IGroupStore.class).to(GroupStore.class).in(Scopes.SINGLETON);
        bind(IUserStore.class).to(UserStore.class).in(Scopes.SINGLETON);

        bind(IRestGroupStore.class).to(RestGroupStore.class);
        bind(IRestUserStore.class).to(RestUserStore.class);
    }
}
