package ai.labs.resources.bootstrap;

import ai.labs.group.IGroupStore;
import ai.labs.group.impl.mongo.GroupStore;
import ai.labs.memory.rest.IRestConversationStore;
import ai.labs.permission.rest.IRestPermissionStore;
import ai.labs.resources.impl.behavior.mongo.BehaviorStore;
import ai.labs.resources.impl.behavior.rest.RestBehaviorStore;
import ai.labs.resources.impl.botmanagement.mongo.BotTriggerStore;
import ai.labs.resources.impl.botmanagement.mongo.UserConversationStore;
import ai.labs.resources.impl.botmanagement.rest.RestBotTriggerStore;
import ai.labs.resources.impl.botmanagement.rest.RestUserConversationStore;
import ai.labs.resources.impl.bots.mongo.BotStore;
import ai.labs.resources.impl.bots.rest.RestBotStore;
import ai.labs.resources.impl.deployment.mongo.DeploymentStore;
import ai.labs.resources.impl.deployment.rest.RestDeploymentStore;
import ai.labs.resources.impl.descriptor.mongo.DocumentDescriptorStore;
import ai.labs.resources.impl.descriptor.rest.RestDocumentDescriptorStore;
import ai.labs.resources.impl.expression.RestExpression;
import ai.labs.resources.impl.extensions.RestExtensionStore;
import ai.labs.resources.impl.http.mongo.HttpCallsStore;
import ai.labs.resources.impl.http.rest.RestHttpCallsStore;
import ai.labs.resources.impl.monitor.rest.RestConversationStore;
import ai.labs.resources.impl.output.mongo.OutputStore;
import ai.labs.resources.impl.output.rest.RestOutputStore;
import ai.labs.resources.impl.output.rest.keys.RestOutputKeys;
import ai.labs.resources.impl.packages.mongo.PackageStore;
import ai.labs.resources.impl.packages.rest.RestPackageStore;
import ai.labs.resources.impl.parser.mongo.ParserStore;
import ai.labs.resources.impl.parser.rest.RestParserStore;
import ai.labs.resources.impl.permission.rest.RestPermissionStore;
import ai.labs.resources.impl.regulardictionary.mongo.RegularDictionaryStore;
import ai.labs.resources.impl.regulardictionary.rest.RestRegularDictionaryStore;
import ai.labs.resources.rest.behavior.IBehaviorStore;
import ai.labs.resources.rest.behavior.IRestBehaviorStore;
import ai.labs.resources.rest.botmanagement.IBotTriggerStore;
import ai.labs.resources.rest.botmanagement.IRestBotTriggerStore;
import ai.labs.resources.rest.botmanagement.IRestUserConversationStore;
import ai.labs.resources.rest.botmanagement.IUserConversationStore;
import ai.labs.resources.rest.bots.IBotStore;
import ai.labs.resources.rest.bots.IRestBotStore;
import ai.labs.resources.rest.deployment.IDeploymentStore;
import ai.labs.resources.rest.deployment.IRestDeploymentStore;
import ai.labs.resources.rest.documentdescriptor.IDocumentDescriptorStore;
import ai.labs.resources.rest.documentdescriptor.IRestDocumentDescriptorStore;
import ai.labs.resources.rest.expression.IRestExpression;
import ai.labs.resources.rest.extensions.IRestExtensionStore;
import ai.labs.resources.rest.http.IHttpCallsStore;
import ai.labs.resources.rest.http.IRestHttpCallsStore;
import ai.labs.resources.rest.output.IOutputStore;
import ai.labs.resources.rest.output.IRestOutputStore;
import ai.labs.resources.rest.output.keys.IRestOutputKeys;
import ai.labs.resources.rest.packages.IPackageStore;
import ai.labs.resources.rest.packages.IRestPackageStore;
import ai.labs.resources.rest.parser.IParserStore;
import ai.labs.resources.rest.parser.IRestParserStore;
import ai.labs.resources.rest.regulardictionary.IRegularDictionaryStore;
import ai.labs.resources.rest.regulardictionary.IRestRegularDictionaryStore;
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

        bind(IRestPermissionStore.class).to(RestPermissionStore.class);
        bind(IRestDocumentDescriptorStore.class).to(RestDocumentDescriptorStore.class);
        bind(IRestExtensionStore.class).to(RestExtensionStore.class);
        bind(IRestExpression.class).to(RestExpression.class);
        bind(IRestDeploymentStore.class).to(RestDeploymentStore.class);

        bind(IRestBotStore.class).to(RestBotStore.class);
        bind(IRestBotTriggerStore.class).to(RestBotTriggerStore.class);
        bind(IRestUserConversationStore.class).to(RestUserConversationStore.class);
        bind(IRestPackageStore.class).to(RestPackageStore.class);
        bind(IRestParserStore.class).to(RestParserStore.class);
        bind(IRestRegularDictionaryStore.class).to(RestRegularDictionaryStore.class);
        bind(IRestBehaviorStore.class).to(RestBehaviorStore.class);
        bind(IRestOutputStore.class).to(RestOutputStore.class);
        bind(IRestConversationStore.class).to(RestConversationStore.class);
        bind(IRestHttpCallsStore.class).to(RestHttpCallsStore.class);

        bind(IRestOutputKeys.class).to(RestOutputKeys.class);
    }
}
