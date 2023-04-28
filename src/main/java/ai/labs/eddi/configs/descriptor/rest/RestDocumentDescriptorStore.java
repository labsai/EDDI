package ai.labs.eddi.configs.descriptor.rest;

import ai.labs.eddi.configs.documentdescriptor.IDocumentDescriptorStore;
import ai.labs.eddi.configs.documentdescriptor.IRestDocumentDescriptorStore;
import ai.labs.eddi.configs.patch.PatchInstruction;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.models.DocumentDescriptor;
import ai.labs.eddi.models.SimpleDocumentDescriptor;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.jboss.logging.Logger;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.NotFoundException;
import java.util.List;

/**
 * @author ginccc
 */

@ApplicationScoped
public class RestDocumentDescriptorStore implements IRestDocumentDescriptorStore {
    private final IDocumentDescriptorStore documentDescriptorStore;

    private static final Logger log = Logger.getLogger(RestDocumentDescriptorStore.class);

    @Inject
    public RestDocumentDescriptorStore(IDocumentDescriptorStore documentDescriptorStore) {
        this.documentDescriptorStore = documentDescriptorStore;
    }

    @Override
    public List<DocumentDescriptor> readDescriptors(String type, String filter, Integer index, Integer limit) {
        try {
            return documentDescriptorStore.readDescriptors(type, filter, index, limit, false);
        } catch (IResourceStore.ResourceStoreException e) {
            log.error(e.getLocalizedMessage(), e);
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
            log.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException(e.getLocalizedMessage(), e);
        } catch (IResourceStore.ResourceNotFoundException e) {
            throw new NotFoundException(e.getLocalizedMessage(), e);
        }
    }

    @Override
    public SimpleDocumentDescriptor readSimpleDescriptor(String id, @Parameter(name = "version", required = true, example = "1") Integer version) {
        DocumentDescriptor documentDescriptor = readDescriptor(id, version);
        return new SimpleDocumentDescriptor(documentDescriptor.getName(), documentDescriptor.getDescription());
    }

    @Override
    public void patchDescriptor(String id, Integer version, PatchInstruction<DocumentDescriptor> patchInstruction) {
        try {
            DocumentDescriptor documentDescriptor = documentDescriptorStore.readDescriptor(id, version);
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
            log.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException(e.getLocalizedMessage(), e);
        } catch (IResourceStore.ResourceNotFoundException e) {
            throw new NotFoundException(e.getLocalizedMessage(), e);
        }
    }
}
