package io.sls.persistence.impl.documentdescriptor.mongo;

import com.mongodb.DB;
import io.sls.group.IGroupStore;
import io.sls.permission.IPermissionStore;
import io.sls.persistence.impl.DescriptorStore;
import io.sls.resources.rest.documentdescriptor.IDocumentDescriptorStore;
import io.sls.resources.rest.documentdescriptor.model.DocumentDescriptor;
import io.sls.serialization.IDocumentBuilder;
import io.sls.serialization.JSONSerialization;
import io.sls.user.IUserStore;
import org.codehaus.jackson.type.TypeReference;

import java.io.IOException;

/**
 * User: jarisch
 * Date: 06.09.12
 * Time: 09:50
 */
public class DocumentDescriptorStore extends DescriptorStore<DocumentDescriptor> implements IDocumentDescriptorStore {

    public DocumentDescriptorStore(DB database, IPermissionStore permissionStore, IUserStore userStore, IGroupStore groupStore) {
        super(database, permissionStore, userStore, groupStore, new IDocumentBuilder<DocumentDescriptor>() {
            @Override
            public DocumentDescriptor build(String doc) throws IOException {
                return JSONSerialization.deserialize(doc, new TypeReference<DocumentDescriptor>() {});
            }
        });
    }
}
