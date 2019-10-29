package ai.labs.core.rest.utilities;

import ai.labs.memory.descriptor.IConversationDescriptorStore;
import ai.labs.memory.descriptor.model.ConversationDescriptor;
import ai.labs.permission.IAuthorization;
import ai.labs.permission.IPermissionStore;
import ai.labs.permission.model.AuthorizedUser;
import ai.labs.permission.model.Permissions;
import ai.labs.permission.utilities.PermissionUtilities;
import ai.labs.persistence.IDescriptorStore;
import ai.labs.persistence.IResourceStore;
import ai.labs.resources.impl.utilities.ResourceUtilities;
import ai.labs.resources.rest.config.bots.IRestBotStore;
import ai.labs.runtime.IBot;
import ai.labs.runtime.ThreadContext;
import ai.labs.user.IUserStore;
import ai.labs.user.impl.utilities.UserUtilities;
import ai.labs.utilities.SecurityUtilities;
import org.apache.commons.lang3.RandomStringUtils;

import javax.inject.Inject;
import java.net.URI;
import java.security.Principal;

import static ai.labs.utilities.RestUtilities.createURI;
import static ai.labs.utilities.RuntimeUtilities.isNullOrEmpty;

public class ConversationSetup implements IConversationSetup {
    private IDescriptorStore<ConversationDescriptor> conversationDescriptorStore;
    private IPermissionStore permissionStore;
    private final IUserStore userStore;

    @Inject
    public ConversationSetup(IConversationDescriptorStore conversationDescriptorStore,
                             IPermissionStore permissionStore,
                             IUserStore userStore) {
        this.conversationDescriptorStore = conversationDescriptorStore;
        this.permissionStore = permissionStore;
        this.userStore = userStore;
    }

    @Override
    public URI createConversationDescriptor(String botId, IBot latestBot, String conversationId, URI conversationUri)
            throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException {

        var botVersion = latestBot.getBotVersion();
        var botResourceUri = createURI(IRestBotStore.resourceURI, botId, IRestBotStore.versionQueryParam, botVersion);
        Principal userPrincipal = SecurityUtilities.getPrincipal(ThreadContext.getSubject());
        URI userUri = UserUtilities.getUserURI(userStore, userPrincipal);
        conversationDescriptorStore.createDescriptor(conversationId, 0,
                ResourceUtilities.createConversationDescriptor(conversationUri, botResourceUri, userUri));
        return userUri;
    }

    @Override
    public void createPermissions(String conversationId, URI userURI) throws IResourceStore.ResourceStoreException {
        Permissions permissions = new Permissions();
        if (userURI != null) {
            PermissionUtilities.addAuthorizedUser(permissions, IAuthorization.Type.WRITE, new AuthorizedUser(userURI, null));
        }
        permissionStore.createPermissions(conversationId, permissions);
    }

    @Override
    public String computeAnonymousUserIdIfEmpty(String userId) {
        return isNullOrEmpty(userId) ?
                "anonymous-" + RandomStringUtils.randomAlphanumeric(10) : userId;
    }
}
