package io.sls.persistence.impl.descriptor.mongo;

import com.mongodb.DB;
import io.sls.group.IGroupStore;
import io.sls.permission.IPermissionStore;
import io.sls.persistence.impl.DescriptorStore;
import io.sls.resources.rest.documentdescriptor.IDocumentDescriptorStore;
import io.sls.resources.rest.documentdescriptor.model.DocumentDescriptor;
import io.sls.serialization.IDocumentBuilder;
import io.sls.user.IUserStore;

import javax.inject.Inject;

/**
 * @author ginccc
 */
public class DocumentDescriptorStore extends DescriptorStore<DocumentDescriptor> implements IDocumentDescriptorStore {

    @Inject
    public DocumentDescriptorStore(DB database, IPermissionStore permissionStore, IUserStore userStore, IGroupStore groupStore, IDocumentBuilder documentBuilder) {
        super(database, permissionStore, userStore, groupStore, documentBuilder, DocumentDescriptor.class);
    }
}
