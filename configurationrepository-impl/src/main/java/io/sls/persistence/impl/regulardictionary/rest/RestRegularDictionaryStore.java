package io.sls.persistence.impl.regulardictionary.rest;

import io.sls.persistence.IResourceStore;
import io.sls.persistence.impl.resources.rest.RestVersionInfo;
import io.sls.resources.rest.documentdescriptor.IDocumentDescriptorStore;
import io.sls.resources.rest.documentdescriptor.model.DocumentDescriptor;
import io.sls.resources.rest.patch.PatchInstruction;
import io.sls.resources.rest.regulardictionary.IRegularDictionaryStore;
import io.sls.resources.rest.regulardictionary.IRestRegularDictionaryStore;
import io.sls.resources.rest.regulardictionary.model.RegularDictionaryConfiguration;
import io.sls.utilities.RestUtilities;
import lombok.extern.slf4j.Slf4j;

import javax.inject.Inject;
import javax.ws.rs.InternalServerErrorException;
import javax.ws.rs.NotFoundException;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Response;
import java.net.URI;
import java.util.List;

/**
 * User: jarisch
 * Date: 04.06.12
 * Time: 22:32
 */
@Slf4j
public class RestRegularDictionaryStore extends RestVersionInfo implements IRestRegularDictionaryStore {
    private final IRegularDictionaryStore regularDictionaryStore;
    private final IDocumentDescriptorStore documentDescriptorStore;

    @Inject
    public RestRegularDictionaryStore(IRegularDictionaryStore regularDictionaryStore,
                                      IDocumentDescriptorStore documentDescriptorStore) {
        this.regularDictionaryStore = regularDictionaryStore;
        this.documentDescriptorStore = documentDescriptorStore;
    }

    @Override
    public List<DocumentDescriptor> readRegularDictionaryDescriptors(String filter, Integer index, Integer limit) {
        try {
            return documentDescriptorStore.readDescriptors("io.sls.regulardictionary", filter, index, limit, false);
        } catch (IResourceStore.ResourceStoreException e) {
            log.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException(e.getLocalizedMessage(), e);
        } catch (IResourceStore.ResourceNotFoundException e) {
            throw new WebApplicationException(Response.Status.NOT_FOUND);
        }
    }

    @Override
    public RegularDictionaryConfiguration readRegularDictionary(String id, Integer version, String filter, String order, Integer index, Integer limit) {
        try {
            return regularDictionaryStore.read(id, version, filter, order, index, limit);
        } catch (IResourceStore.ResourceNotFoundException e) {
            throw new NotFoundException(e.getLocalizedMessage(), e);
        } catch (IResourceStore.ResourceStoreException e) {
            log.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException(e.getLocalizedMessage(), e);
        }
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
        try {
            Integer newVersion = regularDictionaryStore.update(id, version, regularDictionaryConfiguration);
            return RestUtilities.createURI(resourceURI, id, versionQueryParam, newVersion);
        } catch (IResourceStore.ResourceStoreException e) {
            log.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException(e.getLocalizedMessage(), e);
        } catch (IResourceStore.ResourceModifiedException e) {
            try {
                IResourceStore.IResourceId currentId = regularDictionaryStore.getCurrentResourceId(id);
                throw RestUtilities.createConflictException(resourceURI, currentId);
            } catch (IResourceStore.ResourceNotFoundException e1) {
                throw new NotFoundException(e.getLocalizedMessage(), e);
            }
        } catch (IResourceStore.ResourceNotFoundException e) {
            throw new NotFoundException(e.getLocalizedMessage(), e);
        }
    }

    @Override
    public Response createRegularDictionary(RegularDictionaryConfiguration regularDictionaryConfiguration) {
        try {
            IResourceStore.IResourceId resourceId = regularDictionaryStore.create(regularDictionaryConfiguration);
            URI createdUri = RestUtilities.createURI(resourceURI, resourceId.getId(), versionQueryParam, resourceId.getVersion());
            return Response.created(createdUri).entity(createdUri).build();
        } catch (IResourceStore.ResourceStoreException e) {
            log.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException(e.getLocalizedMessage(), e);
        }
    }

    @Override
    public void deleteRegularDictionary(String id, Integer version) {
        try {
            regularDictionaryStore.delete(id, version);
        } catch (IResourceStore.ResourceStoreException e) {
            log.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException(e.getLocalizedMessage(), e);
        } catch (IResourceStore.ResourceModifiedException e) {
            try {
                IResourceStore.IResourceId currentId = regularDictionaryStore.getCurrentResourceId(id);
                throw RestUtilities.createConflictException(resourceURI, currentId);
            } catch (IResourceStore.ResourceNotFoundException e1) {
                throw new NotFoundException(e.getLocalizedMessage(), e);
            }
        } catch (IResourceStore.ResourceNotFoundException e) {
            throw new NotFoundException(e.getLocalizedMessage(), e);
        }
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
