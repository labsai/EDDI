package ai.labs.eddi.configs.descriptor.mongo;

import ai.labs.eddi.configs.documentdescriptor.IDocumentDescriptorStore;
import ai.labs.eddi.datastore.mongo.DescriptorStore;
import ai.labs.eddi.datastore.serialization.IDocumentBuilder;
import ai.labs.eddi.models.DocumentDescriptor;
import com.mongodb.client.MongoDatabase;

import javax.inject.Inject;

/**
 * @author ginccc
 */
public class DocumentDescriptorStore extends DescriptorStore<DocumentDescriptor> implements IDocumentDescriptorStore {

    @Inject
    public DocumentDescriptorStore(MongoDatabase database, IDocumentBuilder documentBuilder) {
        super(database, documentBuilder, DocumentDescriptor.class);
    }
}
