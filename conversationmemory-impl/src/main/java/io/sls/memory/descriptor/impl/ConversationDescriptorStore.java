package io.sls.memory.descriptor.impl;

import com.mongodb.DB;
import io.sls.group.IGroupStore;
import io.sls.memory.descriptor.IConversationDescriptorStore;
import io.sls.memory.descriptor.model.ConversationDescriptor;
import io.sls.permission.IPermissionStore;
import io.sls.persistence.impl.DescriptorStore;
import io.sls.serialization.IDocumentBuilder;
import io.sls.serialization.JSONSerialization;
import io.sls.user.IUserStore;
import org.codehaus.jackson.type.TypeReference;

import javax.inject.Inject;
import java.io.IOException;

/**
 * User: jarisch
 * Date: 19.11.12
 * Time: 17:28
 */
public class ConversationDescriptorStore extends DescriptorStore<ConversationDescriptor> implements IConversationDescriptorStore {

    @Inject
    public ConversationDescriptorStore(DB database, IPermissionStore permissionStore, IUserStore userStore, IGroupStore groupStore) {
        super(database, permissionStore, userStore, groupStore, new IDocumentBuilder<ConversationDescriptor>() {
            @Override
            public ConversationDescriptor build(String doc) throws IOException {
                return JSONSerialization.deserialize(doc, new TypeReference<ConversationDescriptor>() {});
            }
        });
    }
}
