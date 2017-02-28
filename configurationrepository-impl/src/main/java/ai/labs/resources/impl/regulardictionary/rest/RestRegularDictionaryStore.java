package ai.labs.resources.impl.regulardictionary.rest;

import ai.labs.persistence.IResourceStore;
import ai.labs.resources.impl.resources.rest.RestVersionInfo;
import ai.labs.resources.rest.documentdescriptor.IDocumentDescriptorStore;
import ai.labs.resources.rest.documentdescriptor.model.DocumentDescriptor;
import ai.labs.resources.rest.patch.PatchInstruction;
import ai.labs.resources.rest.regulardictionary.IRegularDictionaryStore;
import ai.labs.resources.rest.regulardictionary.IRestRegularDictionaryStore;
import ai.labs.resources.rest.regulardictionary.model.RegularDictionaryConfiguration;
import lombok.extern.slf4j.Slf4j;

import javax.inject.Inject;
import javax.ws.rs.InternalServerErrorException;
import javax.ws.rs.NotFoundException;
import javax.ws.rs.core.Response;
import java.net.URI;
import java.util.List;

/**
 * @author ginccc
 */
@Slf4j
public class RestRegularDictionaryStore extends RestVersionInfo<RegularDictionaryConfiguration> implements IRestRegularDictionaryStore {
    private final IRegularDictionaryStore regularDictionaryStore;

    @Inject
    public RestRegularDictionaryStore(IRegularDictionaryStore regularDictionaryStore,
                                      IDocumentDescriptorStore documentDescriptorStore) {
        super(resourceURI, regularDictionaryStore, documentDescriptorStore);
        this.regularDictionaryStore = regularDictionaryStore;
    }

    @Override
    public List<DocumentDescriptor> readRegularDictionaryDescriptors(String filter, Integer index, Integer limit) {
        return readDescriptors("ai.labs.regulardictionary", filter, index, limit);
    }

    @Override
    public Response readRegularDictionary(String id, Integer version, String filter, String order, Integer index, Integer limit) {
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
    public URI updateRegularDictionary(String id, Integer version, RegularDictionaryConfiguration regularDictionaryConfiguration) {
        return update(id, version, regularDictionaryConfiguration);
    }

    @Override
    public Response createRegularDictionary(RegularDictionaryConfiguration regularDictionaryConfiguration) {
        return create(regularDictionaryConfiguration);
    }

    @Override
    public void deleteRegularDictionary(String id, Integer version) {
        delete(id, version);
    }

    @Override
    public URI patchRegularDictionary(String id, Integer version, PatchInstruction<RegularDictionaryConfiguration>[] patchInstructions) {
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
                    if (regularDictionaryConfigurationPatch.getLanguage() != null) {
                        currentRegularDictionaryConfiguration.setLanguage(regularDictionaryConfigurationPatch.getLanguage());
                    }
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
    protected IResourceStore.IResourceId getCurrentResourceId(String id) throws IResourceStore.ResourceNotFoundException {
        return regularDictionaryStore.getCurrentResourceId(id);
    }
}
