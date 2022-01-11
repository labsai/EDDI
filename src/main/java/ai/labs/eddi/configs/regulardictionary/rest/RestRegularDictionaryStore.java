package ai.labs.eddi.configs.regulardictionary.rest;

import ai.labs.eddi.configs.documentdescriptor.IDocumentDescriptorStore;
import ai.labs.eddi.configs.patch.PatchInstruction;
import ai.labs.eddi.configs.regulardictionary.IRegularDictionaryStore;
import ai.labs.eddi.configs.regulardictionary.IRestRegularDictionaryStore;
import ai.labs.eddi.configs.regulardictionary.model.RegularDictionaryConfiguration;
import ai.labs.eddi.configs.rest.RestVersionInfo;
import ai.labs.eddi.configs.schema.IJsonSchemaCreator;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.IRestInterfaceFactory;
import ai.labs.eddi.engine.RestInterfaceFactory;
import ai.labs.eddi.models.DocumentDescriptor;
import lombok.extern.slf4j.Slf4j;

import javax.inject.Inject;
import javax.ws.rs.InternalServerErrorException;
import javax.ws.rs.NotFoundException;
import javax.ws.rs.core.Response;
import java.util.List;

/**
 * @author ginccc
 */
@Slf4j
public class RestRegularDictionaryStore extends RestVersionInfo<RegularDictionaryConfiguration> implements IRestRegularDictionaryStore {
    private final IRegularDictionaryStore regularDictionaryStore;
    private final IJsonSchemaCreator jsonSchemaCreator;
    private IRestRegularDictionaryStore restRegularDictionaryStore;

    @Inject
    public RestRegularDictionaryStore(IRegularDictionaryStore regularDictionaryStore,
                                      IRestInterfaceFactory restInterfaceFactory,
                                      IDocumentDescriptorStore documentDescriptorStore,
                                      IJsonSchemaCreator jsonSchemaCreator) {
        super(resourceURI, regularDictionaryStore, documentDescriptorStore);
        this.regularDictionaryStore = regularDictionaryStore;
        this.jsonSchemaCreator = jsonSchemaCreator;
        initRestClient(restInterfaceFactory);
    }

    private void initRestClient(IRestInterfaceFactory restInterfaceFactory) {
        try {
            restRegularDictionaryStore = restInterfaceFactory.get(IRestRegularDictionaryStore.class);
        } catch (RestInterfaceFactory.RestInterfaceFactoryException e) {
            restRegularDictionaryStore = null;
            log.error(e.getLocalizedMessage(), e);
        }
    }

    @Override
    public Response readJsonSchema() {
        return Response.ok(jsonSchemaCreator.generateSchema(RegularDictionaryConfiguration.class)).build();
    }

    @Override
    public List<DocumentDescriptor> readRegularDictionaryDescriptors(String filter, Integer index, Integer limit) {
        return readDescriptors("ai.labs.regulardictionary", filter, index, limit);
    }

    @Override
    public RegularDictionaryConfiguration readRegularDictionary(String id, Integer version, String filter, String order, Integer index, Integer limit) {
        return read(id, version);
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
        return update(id, version, regularDictionaryConfiguration);
    }

    @Override
    public Response createRegularDictionary(RegularDictionaryConfiguration regularDictionaryConfiguration) {
        return create(regularDictionaryConfiguration);
    }

    @Override
    public Response deleteRegularDictionary(String id, Integer version) {
        return delete(id, version);
    }

    @Override
    public Response patchRegularDictionary(String id, Integer version, PatchInstruction<RegularDictionaryConfiguration>[] patchInstructions) {
        try {
            RegularDictionaryConfiguration currentRegularDictionaryConfiguration = regularDictionaryStore.read(id, version);
            RegularDictionaryConfiguration patchedRegularDictionaryConfiguration = patchDocument(currentRegularDictionaryConfiguration, patchInstructions);

            return updateRegularDictionary(id, version, patchedRegularDictionaryConfiguration);

        } catch (IResourceStore.ResourceStoreException e) {
            log.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException(e.getLocalizedMessage(), e);
        } catch (IResourceStore.ResourceNotFoundException e) {
            throw new NotFoundException(e.getLocalizedMessage(), e);
        }
    }

    private RegularDictionaryConfiguration patchDocument(RegularDictionaryConfiguration currentRegularDictionaryConfiguration, PatchInstruction<RegularDictionaryConfiguration>[] patchInstructions) throws IResourceStore.ResourceStoreException {
        for (PatchInstruction<RegularDictionaryConfiguration> patchInstruction : patchInstructions) {
            RegularDictionaryConfiguration regularDictionaryConfigurationPatch = patchInstruction.getDocument();
            switch (patchInstruction.getOperation()) {
                case SET:
                    currentRegularDictionaryConfiguration.getWords().removeAll(regularDictionaryConfigurationPatch.getWords());
                    currentRegularDictionaryConfiguration.getWords().addAll(regularDictionaryConfigurationPatch.getWords());
                    currentRegularDictionaryConfiguration.getPhrases().removeAll(regularDictionaryConfigurationPatch.getPhrases());
                    currentRegularDictionaryConfiguration.getPhrases().addAll(regularDictionaryConfigurationPatch.getPhrases());
                    break;
                case DELETE:
                    currentRegularDictionaryConfiguration.getWords().removeAll(regularDictionaryConfigurationPatch.getWords());
                    currentRegularDictionaryConfiguration.getPhrases().removeAll(regularDictionaryConfigurationPatch.getPhrases());
                    break;
                default:
                    throw new IResourceStore.ResourceStoreException("Patch operation must be either SET or DELETE!");
            }
        }

        return currentRegularDictionaryConfiguration;
    }

    @Override
    public Response duplicateRegularDictionary(String id, Integer version) {
        validateParameters(id, version);
        try {
            var regularDictionaryConfiguration = regularDictionaryStore.read(id, version);
            return restRegularDictionaryStore.createRegularDictionary(regularDictionaryConfiguration);
        } catch (IResourceStore.ResourceNotFoundException e) {
            throw new NotFoundException();
        } catch (IResourceStore.ResourceStoreException e) {
            log.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException();
        }
    }

    @Override
    protected IResourceStore.IResourceId getCurrentResourceId(String id) throws IResourceStore.ResourceNotFoundException {
        return regularDictionaryStore.getCurrentResourceId(id);
    }
}
