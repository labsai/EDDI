package io.sls.persistence.impl.descriptor.mongo;

import com.mongodb.DB;
import io.sls.group.IGroupStore;
import io.sls.permission.IPermissionStore;
import io.sls.persistence.impl.DescriptorStore;
import io.sls.resources.rest.documentdescriptor.IDocumentDescriptorStore;
import io.sls.resources.rest.documentdescriptor.model.DocumentDescriptor;
import io.sls.serialization.JSONSerialization;
import io.sls.user.IUserStore;
import org.codehaus.jackson.type.TypeReference;

import javax.inject.Inject;

/**
 * @author ginccc
 */
public class DocumentDescriptorStore extends DescriptorStore<DocumentDescriptor> implements IDocumentDescriptorStore {

    @Inject
    public DocumentDescriptorStore(DB database, IPermissionStore permissionStore, IUserStore userStore, IGroupStore groupStore) {
        super(database, permissionStore, userStore, groupStore,
                doc -> JSONSerialization.deserialize(doc, new TypeReference<DocumentDescriptor>() {
                }));
    }
}
