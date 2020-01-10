package ai.labs.resources.bootstrap;

import ai.labs.group.IGroupStore;
import ai.labs.group.impl.mongo.GroupStore;
import ai.labs.memory.rest.IRestConversationStore;
import ai.labs.permission.rest.IRestPermissionStore;
import ai.labs.resources.impl.backup.RestGitBackupStore;
import ai.labs.resources.impl.botmanagement.mongo.BotTriggerStore;
import ai.labs.resources.impl.botmanagement.mongo.UserConversationStore;
import ai.labs.resources.impl.botmanagement.rest.RestBotTriggerStore;
import ai.labs.resources.impl.botmanagement.rest.RestUserConversationStore;
import ai.labs.resources.impl.config.behavior.mongo.BehaviorStore;
import ai.labs.resources.impl.config.behavior.rest.RestBehaviorStore;
import ai.labs.resources.impl.config.bots.mongo.BotStore;
import ai.labs.resources.impl.config.bots.rest.RestBotStore;
import ai.labs.resources.impl.config.http.mongo.HttpCallsStore;
import ai.labs.resources.impl.config.http.rest.RestHttpCallsStore;
import ai.labs.resources.impl.config.output.mongo.OutputStore;
import ai.labs.resources.impl.config.output.rest.RestOutputStore;
import ai.labs.resources.impl.config.output.rest.keys.RestOutputKeys;
import ai.labs.resources.impl.config.packages.mongo.PackageStore;
import ai.labs.resources.impl.config.packages.rest.RestPackageStore;
import ai.labs.resources.impl.config.parser.mongo.ParserStore;
import ai.labs.resources.impl.config.parser.rest.RestParserStore;
import ai.labs.resources.impl.config.propertysetter.mongo.PropertySetterStore;
import ai.labs.resources.impl.config.propertysetter.rest.RestPropertySetterStore;
import ai.labs.resources.impl.config.regulardictionary.mongo.RegularDictionaryStore;
import ai.labs.resources.impl.config.regulardictionary.rest.RestRegularDictionaryStore;
import ai.labs.resources.impl.deployment.mongo.DeploymentStore;
import ai.labs.resources.impl.deployment.rest.RestDeploymentStore;
import ai.labs.resources.impl.descriptor.mongo.DocumentDescriptorStore;
import ai.labs.resources.impl.descriptor.rest.RestDocumentDescriptorStore;
import ai.labs.resources.impl.expression.RestExpression;
import ai.labs.resources.impl.extensions.RestExtensionStore;
import ai.labs.resources.impl.migration.MigrationLogStore;
import ai.labs.resources.impl.monitor.rest.RestConversationStore;
import ai.labs.resources.impl.permission.rest.RestPermissionStore;
import ai.labs.resources.impl.properties.mongo.PropertiesStore;
import ai.labs.resources.impl.properties.rest.RestPropertiesStore;
import ai.labs.resources.rest.backup.IGitBackupStore;
import ai.labs.resources.rest.botmanagement.IBotTriggerStore;
import ai.labs.resources.rest.botmanagement.IRestBotTriggerStore;
import ai.labs.resources.rest.botmanagement.IRestUserConversationStore;
import ai.labs.resources.rest.botmanagement.IUserConversationStore;
import ai.labs.resources.rest.config.behavior.IBehaviorStore;
import ai.labs.resources.rest.config.behavior.IRestBehaviorStore;
import ai.labs.resources.rest.config.bots.IBotStore;
import ai.labs.resources.rest.config.bots.IRestBotStore;
import ai.labs.resources.rest.config.http.IHttpCallsStore;
import ai.labs.resources.rest.config.http.IRestHttpCallsStore;
import ai.labs.resources.rest.config.output.IOutputStore;
import ai.labs.resources.rest.config.output.IRestOutputStore;
import ai.labs.resources.rest.config.output.keys.IRestOutputKeys;
import ai.labs.resources.rest.config.packages.IPackageStore;
import ai.labs.resources.rest.config.packages.IRestPackageStore;
import ai.labs.resources.rest.config.parser.IParserStore;
import ai.labs.resources.rest.config.parser.IRestParserStore;
import ai.labs.resources.rest.config.propertysetter.IPropertySetterStore;
import ai.labs.resources.rest.config.propertysetter.IRestPropertySetterStore;
import ai.labs.resources.rest.config.regulardictionary.IRegularDictionaryStore;
import ai.labs.resources.rest.config.regulardictionary.IRestExpression;
import ai.labs.resources.rest.config.regulardictionary.IRestRegularDictionaryStore;
import ai.labs.resources.rest.deployment.IDeploymentStore;
import ai.labs.resources.rest.deployment.IRestDeploymentStore;
import ai.labs.resources.rest.documentdescriptor.IDocumentDescriptorStore;
import ai.labs.resources.rest.documentdescriptor.IRestDocumentDescriptorStore;
import ai.labs.resources.rest.extensions.IRestExtensionStore;
import ai.labs.resources.rest.migration.IMigrationLogStore;
import ai.labs.resources.rest.properties.IPropertiesStore;
import ai.labs.resources.rest.properties.IRestPropertiesStore;
import ai.labs.runtime.bootstrap.AbstractBaseModule;
import ai.labs.user.IUserStore;
import ai.labs.user.impl.mongo.UserStore;
import com.google.inject.Scopes;

/**
 * @author ginccc
 */
public class RepositoryModule extends AbstractBaseModule {

    @Override
    protected void configure() {
        bind(IUserStore.class).to(UserStore.class).in(Scopes.SINGLETON);
        bind(IGroupStore.class).to(GroupStore.class).in(Scopes.SINGLETON);
        bind(IDocumentDescriptorStore.class).to(DocumentDescriptorStore.class).in(Scopes.SINGLETON);

        bind(IBotStore.class).to(BotStore.class).in(Scopes.SINGLETON);
        bind(IBotTriggerStore.class).to(BotTriggerStore.class).in(Scopes.SINGLETON);
        bind(IUserConversationStore.class).to(UserConversationStore.class).in(Scopes.SINGLETON);
        bind(IPackageStore.class).to(PackageStore.class).in(Scopes.SINGLETON);
        bind(IParserStore.class).to(ParserStore.class).in(Scopes.SINGLETON);
        bind(IRegularDictionaryStore.class).to(RegularDictionaryStore.class).in(Scopes.SINGLETON);
        bind(IBehaviorStore.class).to(BehaviorStore.class).in(Scopes.SINGLETON);
        bind(IOutputStore.class).to(OutputStore.class).in(Scopes.SINGLETON);
        bind(IDeploymentStore.class).to(DeploymentStore.class).in(Scopes.SINGLETON);
        bind(IHttpCallsStore.class).to(HttpCallsStore.class).in(Scopes.SINGLETON);
        bind(IPropertiesStore.class).to(PropertiesStore.class).in(Scopes.SINGLETON);
        bind(IPropertySetterStore.class).to(PropertySetterStore.class).in(Scopes.SINGLETON);

        bind(IRestPermissionStore.class).to(RestPermissionStore.class).in(Scopes.SINGLETON);
        bind(IRestDocumentDescriptorStore.class).to(RestDocumentDescriptorStore.class).in(Scopes.SINGLETON);
        bind(IRestExtensionStore.class).to(RestExtensionStore.class).in(Scopes.SINGLETON);
        bind(IRestExpression.class).to(RestExpression.class).in(Scopes.SINGLETON);
        bind(IRestDeploymentStore.class).to(RestDeploymentStore.class).in(Scopes.SINGLETON);

        bind(IRestBotStore.class).to(RestBotStore.class).in(Scopes.SINGLETON);
        bind(IRestBotTriggerStore.class).to(RestBotTriggerStore.class).in(Scopes.SINGLETON);
        bind(IRestUserConversationStore.class).to(RestUserConversationStore.class).in(Scopes.SINGLETON);
        bind(IRestPackageStore.class).to(RestPackageStore.class).in(Scopes.SINGLETON);
        bind(IRestParserStore.class).to(RestParserStore.class).in(Scopes.SINGLETON);
        bind(IRestRegularDictionaryStore.class).to(RestRegularDictionaryStore.class).in(Scopes.SINGLETON);
        bind(IRestBehaviorStore.class).to(RestBehaviorStore.class).in(Scopes.SINGLETON);
        bind(IRestOutputStore.class).to(RestOutputStore.class).in(Scopes.SINGLETON);
        bind(IRestConversationStore.class).to(RestConversationStore.class).in(Scopes.SINGLETON);
        bind(IRestHttpCallsStore.class).to(RestHttpCallsStore.class).in(Scopes.SINGLETON);
        bind(IRestPropertiesStore.class).to(RestPropertiesStore.class).in(Scopes.SINGLETON);
        bind(IRestPropertySetterStore.class).to(RestPropertySetterStore.class).in(Scopes.SINGLETON);

        bind(IRestOutputKeys.class).to(RestOutputKeys.class).in(Scopes.SINGLETON);
        bind(IMigrationLogStore.class).to(MigrationLogStore.class).in(Scopes.SINGLETON);
        bind(IGitBackupStore.class).to(RestGitBackupStore.class).in(Scopes.SINGLETON);
    }
}
