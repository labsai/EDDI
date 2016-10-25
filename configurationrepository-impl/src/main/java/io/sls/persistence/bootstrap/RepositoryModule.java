package io.sls.persistence.bootstrap;

import com.google.inject.Scopes;
import io.sls.group.IGroupStore;
import io.sls.group.impl.mongo.GroupStore;
import io.sls.memory.rest.IRestMonitorStore;
import io.sls.permission.rest.IRestPermissionStore;
import io.sls.persistence.impl.behavior.mongo.BehaviorStore;
import io.sls.persistence.impl.behavior.rest.RestBehaviorStore;
import io.sls.persistence.impl.bots.mongo.BotStore;
import io.sls.persistence.impl.bots.rest.RestBotStore;
import io.sls.persistence.impl.descriptor.mongo.DocumentDescriptorStore;
import io.sls.persistence.impl.documentdescriptor.rest.RestDocumentDescriptorStore;
import io.sls.persistence.impl.extensions.mongo.ExtensionStore;
import io.sls.persistence.impl.extensions.rest.RestExtensionStore;
import io.sls.persistence.impl.monitor.rest.RestMonitorStore;
import io.sls.persistence.impl.output.mongo.OutputStore;
import io.sls.persistence.impl.output.rest.RestOutputStore;
import io.sls.persistence.impl.output.rest.keys.RestOutputKeys;
import io.sls.persistence.impl.packages.mongo.PackageStore;
import io.sls.persistence.impl.packages.rest.RestPackageStore;
import io.sls.persistence.impl.permission.rest.RestPermissionStore;
import io.sls.persistence.impl.regulardictionary.mongo.RegularDictionaryStore;
import io.sls.persistence.impl.regulardictionary.rest.RestRegularDictionaryStore;
import io.sls.persistence.impl.scriptimport.RestScriptImport;
import io.sls.resources.rest.behavior.IBehaviorStore;
import io.sls.resources.rest.behavior.IRestBehaviorStore;
import io.sls.resources.rest.bots.IBotStore;
import io.sls.resources.rest.bots.IRestBotStore;
import io.sls.resources.rest.documentdescriptor.IDocumentDescriptorStore;
import io.sls.resources.rest.documentdescriptor.IRestDocumentDescriptorStore;
import io.sls.resources.rest.extensions.IExtensionStore;
import io.sls.resources.rest.extensions.IRestExtensionStore;
import io.sls.resources.rest.output.IOutputStore;
import io.sls.resources.rest.output.IRestOutputStore;
import io.sls.resources.rest.output.keys.IRestOutputKeys;
import io.sls.resources.rest.packages.IPackageStore;
import io.sls.resources.rest.packages.IRestPackageStore;
import io.sls.resources.rest.regulardictionary.IRegularDictionaryStore;
import io.sls.resources.rest.regulardictionary.IRestRegularDictionaryStore;
import io.sls.resources.rest.scriptimport.IRestScriptImport;
import io.sls.runtime.bootstrap.AbstractBaseModule;
import io.sls.user.IUserStore;
import io.sls.user.impl.mongo.UserStore;

/**
 * @author ginccc
 */
public class RepositoryModule extends AbstractBaseModule {

    @Override
    protected void configure() {
        bind(IUserStore.class).to(UserStore.class).in(Scopes.SINGLETON);
        bind(IGroupStore.class).to(GroupStore.class).in(Scopes.SINGLETON);
        bind(IDocumentDescriptorStore.class).to(DocumentDescriptorStore.class).in(Scopes.SINGLETON);
        
        bind(IExtensionStore.class).to(ExtensionStore.class).in(Scopes.SINGLETON);
        bind(IBotStore.class).to(BotStore.class).in(Scopes.SINGLETON);
        bind(IPackageStore.class).to(PackageStore.class).in(Scopes.SINGLETON);
        bind(IRegularDictionaryStore.class).to(RegularDictionaryStore.class).in(Scopes.SINGLETON);
        bind(IBehaviorStore.class).to(BehaviorStore.class).in(Scopes.SINGLETON);
        bind(IOutputStore.class).to(OutputStore.class).in(Scopes.SINGLETON);

        bind(IRestPermissionStore.class).to(RestPermissionStore.class);
        bind(IRestDocumentDescriptorStore.class).to(RestDocumentDescriptorStore.class);
        bind(IRestExtensionStore.class).to(RestExtensionStore.class);

        bind(IRestBotStore.class).to(RestBotStore.class);
        bind(IRestPackageStore.class).to(RestPackageStore.class);
        bind(IRestRegularDictionaryStore.class).to(RestRegularDictionaryStore.class);
        bind(IRestBehaviorStore.class).to(RestBehaviorStore.class);
        bind(IRestOutputStore.class).to(RestOutputStore.class);
        bind(IRestMonitorStore.class).to(RestMonitorStore.class);

        bind(IRestOutputKeys.class).to(RestOutputKeys.class);

        bind(IRestScriptImport.class).to(RestScriptImport.class);

    }
}
