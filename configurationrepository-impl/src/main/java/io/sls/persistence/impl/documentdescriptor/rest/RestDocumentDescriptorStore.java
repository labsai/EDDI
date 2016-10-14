package io.sls.persistence.impl.documentdescriptor.rest;

import io.sls.persistence.IResourceStore;
import io.sls.persistence.impl.resources.rest.RestVersionInfo;
import io.sls.resources.rest.documentdescriptor.IDocumentDescriptorStore;
import io.sls.resources.rest.documentdescriptor.IRestDocumentDescriptorStore;
import io.sls.resources.rest.documentdescriptor.model.DocumentDescriptor;
import io.sls.resources.rest.documentdescriptor.model.SimpleDocumentDescriptor;
import io.sls.resources.rest.patch.PatchInstruction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.ws.rs.InternalServerErrorException;
import javax.ws.rs.NotFoundException;
import java.util.List;

/**
 * User: jarisch
 * Date: 06.09.12
 * Time: 09:51
 */
public class RestDocumentDescriptorStore extends RestVersionInfo implements IRestDocumentDescriptorStore {
    private final IDocumentDescriptorStore documentDescriptorStore;
    private Logger logger = LoggerFactory.getLogger(RestDocumentDescriptorStore.class);

    @Inject
    public RestDocumentDescriptorStore(IDocumentDescriptorStore documentDescriptorStore) {
        this.documentDescriptorStore = documentDescriptorStore;
    }

    @Override
    public List<DocumentDescriptor> readDescriptors(String type, String filter, Integer index, Integer limit) {
        try {
            return documentDescriptorStore.readDescriptors(type, filter, index, limit, false);
        } catch (IResourceStore.ResourceStoreException e) {
            logger.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException(e.getLocalizedMessage(), e);
        } catch (IResourceStore.ResourceNotFoundException e) {
            throw new NotFoundException(e.getLocalizedMessage(), e);
        }
    }

    @Override
    public DocumentDescriptor readDescriptor(String id, Integer version) {
        try {
            return documentDescriptorStore.readDescriptor(id, version);
        } catch (IResourceStore.ResourceStoreException e) {
            logger.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException(e.getLocalizedMessage(), e);
        } catch (IResourceStore.ResourceNotFoundException e) {
            throw new NotFoundException(e.getLocalizedMessage(), e);
        }
    }

    @Override
    public SimpleDocumentDescriptor readSimpleDescriptor(String id, Integer version) {
        DocumentDescriptor documentDescriptor = readDescriptor(id, version);
        return new SimpleDocumentDescriptor(documentDescriptor.getName(), documentDescriptor.getDescription());
    }

    @Override
    public void patchDescriptor(String id, Integer version, PatchInstruction<DocumentDescriptor> patchInstruction) {
        try {
            DocumentDescriptor documentDescriptor = readDescriptor(id, version);
            DocumentDescriptor simpleDescriptor = patchInstruction.getDocument();

            if (patchInstruction.getOperation().equals(PatchInstruction.PatchOperation.SET)) {
                documentDescriptor.setName(simpleDescriptor.getName());
                documentDescriptor.setDescription(simpleDescriptor.getDescription());
            } else {
                documentDescriptor.setName("");
                documentDescriptor.setDescription("");
            }

            documentDescriptorStore.setDescriptor(id, version, documentDescriptor);
        } catch (IResourceStore.ResourceStoreException e) {
            logger.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException(e.getLocalizedMessage(), e);
        } catch (IResourceStore.ResourceNotFoundException e) {
            throw new NotFoundException(e.getLocalizedMessage(), e);
        }
    }


    @Override
    protected IResourceStore.IResourceId getCurrentResourceId(String id) throws IResourceStore.ResourceNotFoundException {
        return documentDescriptorStore.getCurrentResourceId(id);
    }
}
