package ai.labs.resources.impl.descriptor.mongo;

import ai.labs.group.IGroupStore;
import ai.labs.models.DocumentDescriptor;
import ai.labs.permission.IPermissionStore;
import ai.labs.persistence.DescriptorStore;
import ai.labs.resources.rest.documentdescriptor.IDocumentDescriptorStore;
import ai.labs.serialization.IDocumentBuilder;
import ai.labs.user.IUserStore;
import com.mongodb.client.MongoDatabase;

import javax.inject.Inject;

/**
 * @author ginccc
 */
public class DocumentDescriptorStore extends DescriptorStore<DocumentDescriptor> implements IDocumentDescriptorStore {

    @Inject
    public DocumentDescriptorStore(MongoDatabase database, IPermissionStore permissionStore, IUserStore userStore, IGroupStore groupStore, IDocumentBuilder documentBuilder) {
        super(database, permissionStore, userStore, groupStore, documentBuilder, DocumentDescriptor.class);
    }
}
