package ai.labs.eddi.configs.regulardictionary.rest;

import ai.labs.eddi.configs.documentdescriptor.IDocumentDescriptorStore;
import ai.labs.eddi.configs.patch.PatchInstruction;
import ai.labs.eddi.configs.regulardictionary.IRegularDictionaryStore;
import ai.labs.eddi.configs.regulardictionary.IRestRegularDictionaryStore;
import ai.labs.eddi.configs.regulardictionary.model.RegularDictionaryConfiguration;
import ai.labs.eddi.configs.rest.RestVersionInfo;
import ai.labs.eddi.configs.schema.IJsonSchemaCreator;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.configs.documentdescriptor.model.DocumentDescriptor;
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
public class RestRegularDictionaryStore implements IRestRegularDictionaryStore {
    private final IRegularDictionaryStore regularDictionaryStore;
    private final IJsonSchemaCreator jsonSchemaCreator;
    private final RestVersionInfo<RegularDictionaryConfiguration> restVersionInfo;

    private static final Logger log = Logger.getLogger(RestRegularDictionaryStore.class);

    @Inject
    public RestRegularDictionaryStore(IRegularDictionaryStore regularDictionaryStore,
                                      IDocumentDescriptorStore documentDescriptorStore,
                                      IJsonSchemaCreator jsonSchemaCreator) {
        restVersionInfo = new RestVersionInfo<>(resourceURI, regularDictionaryStore, documentDescriptorStore);
        this.regularDictionaryStore = regularDictionaryStore;
        this.jsonSchemaCreator = jsonSchemaCreator;
    }

    @Override
    public Response readJsonSchema() {
        try {
            return Response.ok(jsonSchemaCreator.generateSchema(RegularDictionaryConfiguration.class)).build();
        } catch (Exception e) {
            log.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException();
        }
    }

    @Override
    public List<DocumentDescriptor> readRegularDictionaryDescriptors(String filter, Integer index, Integer limit) {
        return restVersionInfo.readDescriptors("ai.labs.regulardictionary", filter, index, limit);
    }

    @Override
    public RegularDictionaryConfiguration readRegularDictionary(String id, Integer version, String filter,
                                                                String order, Integer index, Integer limit) {
        return restVersionInfo.read(id, version);
    }

    @Override
    public List<String> readExpressions(String id, Integer version, String filter, String order, Integer index, Integer limit) {
        try {
            return regularDictionaryStore.readExpressions(id, version, filter, order, index, limit);
        } catch (IResourceStore.ResourceStoreException e) {
            log.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException(e.getLocalizedMessage(), e);
        } catch (IResourceStore.ResourceNotFoundException e) {
            throw new NotFoundException(e.getLocalizedMessage(), e);
        }
    }

    @Override
    public Response updateRegularDictionary(String id, Integer version, RegularDictionaryConfiguration regularDictionaryConfiguration) {
        return restVersionInfo.update(id, version, regularDictionaryConfiguration);
    }

    @Override
    public Response createRegularDictionary(RegularDictionaryConfiguration regularDictionaryConfiguration) {
        return restVersionInfo.create(regularDictionaryConfiguration);
    }

    @Override
    public Response deleteRegularDictionary(String id, Integer version) {
        return restVersionInfo.delete(id, version);
    }

    @Override
    public Response patchRegularDictionary(String id, Integer version,
                                           List<PatchInstruction<RegularDictionaryConfiguration>> patchInstructions) {
        try {
            var currentRegularDictionaryConfiguration = regularDictionaryStore.read(id, version);
            var patchedRegularDictionaryConfiguration =
                    patchDocument(currentRegularDictionaryConfiguration, patchInstructions);

            return updateRegularDictionary(id, version, patchedRegularDictionaryConfiguration);

        } catch (IResourceStore.ResourceStoreException e) {
            log.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException(e.getLocalizedMessage(), e);
        } catch (IResourceStore.ResourceNotFoundException e) {
            throw new NotFoundException(e.getLocalizedMessage(), e);
        }
    }

    private RegularDictionaryConfiguration patchDocument(
            RegularDictionaryConfiguration currentDictionaryConfig,
            List<PatchInstruction<RegularDictionaryConfiguration>> patchInstructions)
            throws IResourceStore.ResourceStoreException {

        for (var patchInstruction : patchInstructions) {
            var regularConfigPatch = patchInstruction.getDocument();
            switch (patchInstruction.getOperation()) {
                case SET -> {
                    currentDictionaryConfig.getWords().removeAll(regularConfigPatch.getWords());
                    currentDictionaryConfig.getWords().addAll(regularConfigPatch.getWords());
                    currentDictionaryConfig.getPhrases().removeAll(regularConfigPatch.getPhrases());
                    currentDictionaryConfig.getPhrases().addAll(regularConfigPatch.getPhrases());
                }
                case DELETE -> {
                    currentDictionaryConfig.getWords().removeAll(regularConfigPatch.getWords());
                    currentDictionaryConfig.getPhrases().removeAll(regularConfigPatch.getPhrases());
                }
                default ->
                        throw new IResourceStore.ResourceStoreException("Patch operation must be either SET or DELETE!");
            }
        }

        return currentDictionaryConfig;
    }

    @Override
    public Response duplicateRegularDictionary(String id, Integer version) {
        restVersionInfo.validateParameters(id, version);
        try {
            var regularDictionaryConfiguration = regularDictionaryStore.read(id, version);
            return restVersionInfo.create(regularDictionaryConfiguration);
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
        return regularDictionaryStore.getCurrentResourceId(id);
    }
}
