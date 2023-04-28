package ai.labs.eddi.configs.output.rest;

import ai.labs.eddi.configs.documentdescriptor.IDocumentDescriptorStore;
import ai.labs.eddi.configs.output.IOutputStore;
import ai.labs.eddi.configs.output.IRestOutputStore;
import ai.labs.eddi.configs.output.model.OutputConfigurationSet;
import ai.labs.eddi.configs.patch.PatchInstruction;
import ai.labs.eddi.configs.rest.RestVersionInfo;
import ai.labs.eddi.configs.schema.IJsonSchemaCreator;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.models.DocumentDescriptor;
import org.jboss.logging.Logger;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.Response;
import java.util.List;

/**
 * @author ginccc
 */

@ApplicationScoped
public class RestOutputStore implements IRestOutputStore {
    private final IOutputStore outputStore;
    private final IJsonSchemaCreator jsonSchemaCreator;
    private final RestVersionInfo<OutputConfigurationSet> restVersionInfo;

    private static final Logger log = Logger.getLogger(RestOutputStore.class);

    @Inject
    public RestOutputStore(IOutputStore outputStore,
                           IDocumentDescriptorStore documentDescriptorStore,
                           IJsonSchemaCreator jsonSchemaCreator) {
        restVersionInfo = new RestVersionInfo<>(resourceURI, outputStore, documentDescriptorStore);
        this.outputStore = outputStore;
        this.jsonSchemaCreator = jsonSchemaCreator;
    }

    @Override
    public Response readJsonSchema() {
        try {
            return Response.ok(jsonSchemaCreator.generateSchema(OutputConfigurationSet.class)).build();
        } catch (Exception e) {
            log.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException();
        }
    }

    @Override
    public List<DocumentDescriptor> readOutputDescriptors(String filter, Integer index, Integer limit) {
        return restVersionInfo.readDescriptors("ai.labs.output", filter, index, limit);
    }

    @Override
    public OutputConfigurationSet readOutputSet(String id, Integer version,
                                                String filter, String order, Integer index, Integer limit) {
        try {
            return outputStore.read(id, version, filter, order, index, limit);
        } catch (IResourceStore.ResourceNotFoundException e) {
            throw new NotFoundException(e.getLocalizedMessage(), e);
        } catch (IResourceStore.ResourceStoreException e) {
            log.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException(e.getLocalizedMessage(), e);
        }
    }

    @Override
    public List<String> readOutputKeys(String id, Integer version, String filter, Integer limit) {
        try {
            return outputStore.readActions(id, version, filter, limit);
        } catch (IResourceStore.ResourceStoreException e) {
            log.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException(e.getLocalizedMessage(), e);
        } catch (IResourceStore.ResourceNotFoundException e) {
            throw new NotFoundException(e.getLocalizedMessage(), e);
        }
    }

    @Override
    public Response updateOutputSet(String id, Integer version, OutputConfigurationSet outputConfigurationSet) {
        return restVersionInfo.update(id, version, outputConfigurationSet);
    }

    @Override
    public Response createOutputSet(OutputConfigurationSet outputConfigurationSet) {
        return restVersionInfo.create(outputConfigurationSet);
    }

    @Override
    public Response deleteOutputSet(String id, Integer version) {
        return restVersionInfo.delete(id, version);
    }

    @Override
    public Response patchOutputSet(String id, Integer version,
                                   List<PatchInstruction<OutputConfigurationSet>> patchInstructions) {
        try {
            OutputConfigurationSet currentOutputConfigurationSet = outputStore.read(id, version);
            OutputConfigurationSet patchedOutputConfigurationSet =
                    patchDocument(currentOutputConfigurationSet, patchInstructions);

            return updateOutputSet(id, version, patchedOutputConfigurationSet);

        } catch (IResourceStore.ResourceStoreException e) {
            log.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException(e.getLocalizedMessage(), e);
        } catch (IResourceStore.ResourceNotFoundException e) {
            throw new NotFoundException(e.getLocalizedMessage(), e);
        }
    }

    private OutputConfigurationSet patchDocument(OutputConfigurationSet currentOutputConfigurationSet,
                                                 List<PatchInstruction<OutputConfigurationSet>> patchInstructions)
            throws IResourceStore.ResourceStoreException {

        for (var patchInstruction : patchInstructions) {
            var outputConfigurationSetPatch = patchInstruction.getDocument();
            switch (patchInstruction.getOperation()) {
                case SET:
                    currentOutputConfigurationSet.getOutputSet().removeAll(outputConfigurationSetPatch.getOutputSet());
                    currentOutputConfigurationSet.getOutputSet().addAll(outputConfigurationSetPatch.getOutputSet());
                    break;
                case DELETE:
                    currentOutputConfigurationSet.getOutputSet().removeAll(outputConfigurationSetPatch.getOutputSet());
                    break;
                default:
                    throw new IResourceStore.ResourceStoreException("Patch operation must be either SET or DELETE!");
            }
        }

        return currentOutputConfigurationSet;
    }

    @Override
    public Response duplicateOutputSet(String id, Integer version) {
        restVersionInfo.validateParameters(id, version);
        try {
            var outputConfigurationSet = outputStore.read(id, version);
            return restVersionInfo.create(outputConfigurationSet);
        } catch (IResourceStore.ResourceNotFoundException e) {
            throw new NotFoundException();
        } catch (IResourceStore.ResourceStoreException e) {
            log.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException();
        }
    }

    @Override
    public String getResourceURI() {
        return restVersionInfo.getResourceURI();
    }

    @Override
    public IResourceStore.IResourceId getCurrentResourceId(String id) throws IResourceStore.ResourceNotFoundException {
        return outputStore.getCurrentResourceId(id);
    }
}
