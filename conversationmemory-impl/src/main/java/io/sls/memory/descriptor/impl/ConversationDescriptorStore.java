package io.sls.memory.descriptor.impl;

import com.mongodb.DB;
import io.sls.group.IGroupStore;
import io.sls.memory.descriptor.IConversationDescriptorStore;
import io.sls.memory.descriptor.model.ConversationDescriptor;
import io.sls.permission.IPermissionStore;
import io.sls.persistence.impl.DescriptorStore;
import io.sls.serialization.IDocumentBuilder;
import io.sls.user.IUserStore;

import javax.inject.Inject;

/**
 * @author ginccc
 */
public class ConversationDescriptorStore extends DescriptorStore<ConversationDescriptor> implements IConversationDescriptorStore {

    @Inject
    public ConversationDescriptorStore(DB database, IPermissionStore permissionStore, IUserStore userStore, IGroupStore groupStore, IDocumentBuilder documentBuilder) {
        super(database, permissionStore, userStore, groupStore, documentBuilder, ConversationDescriptor.class);
    }
}
