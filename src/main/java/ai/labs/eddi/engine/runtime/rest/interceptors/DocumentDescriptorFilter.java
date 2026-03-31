package ai.labs.eddi.engine.runtime.rest.interceptors;

import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.serialization.IDescriptorStore;
import ai.labs.eddi.engine.memory.descriptor.IConversationDescriptorStore;
import ai.labs.eddi.configs.descriptors.model.ResourceDescriptor;
import ai.labs.eddi.utils.RestUtilities;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.HttpMethod;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.ext.Provider;
import org.jboss.logging.Logger;

import java.net.URI;
import java.util.Date;

import static ai.labs.eddi.configs.descriptors.IRestDocumentDescriptorStore.DESCRIPTOR_STORE_PATH;
import static ai.labs.eddi.configs.descriptors.ResourceUtilities.createDocumentDescriptor;
import static ai.labs.eddi.engine.exception.SneakyThrow.sneakyThrow;

/**
 * @author ginccc
 */

@Provider
public class DocumentDescriptorFilter implements ContainerResponseFilter {
    private final IDocumentDescriptorStore documentDescriptorStore;
    private final IConversationDescriptorStore conversationDescriptorStore;

    private static final Logger log = Logger.getLogger(DocumentDescriptorFilter.class);

    @Inject
    UriInfo uriInfo;

    @Inject
    public DocumentDescriptorFilter(IDocumentDescriptorStore documentDescriptorStore, IConversationDescriptorStore conversationDescriptorStore) {
        this.documentDescriptorStore = documentDescriptorStore;
        this.conversationDescriptorStore = conversationDescriptorStore;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void filter(ContainerRequestContext contextRequest, ContainerResponseContext contextResponse) {
        try {
            int httpStatus = contextResponse.getStatus();

            if (httpStatus < 200 || httpStatus >= 300) {
                return;
            }

            var invokedHttpMethod = contextRequest.getMethod();
            if ((isPUT(invokedHttpMethod) || isPATCH(invokedHttpMethod) || isPOST(invokedHttpMethod) || isDELETE(invokedHttpMethod))) {

                String resourceLocationUri = contextResponse.getHeaderString(HttpHeaders.LOCATION);
                if (resourceLocationUri != null) {
                    if (resourceLocationUri.contains("://")) {
                        URI createdResourceURI = URI.create(resourceLocationUri);
                        IResourceStore.IResourceId resourceId = RestUtilities.extractResourceId(createdResourceURI);

                        if (isPOST(invokedHttpMethod)) {
                            // the resource was created successfully
                            if (httpStatus == 201) {
                                if (isResourceIdValid(resourceId) && !resourceLocationUri.startsWith("eddi://ai.labs.conversation")) {
                                    try {
                                        documentDescriptorStore.readDescriptor(resourceId.getId(), resourceId.getVersion());
                                    } catch (IResourceStore.ResourceNotFoundException e) {
                                        documentDescriptorStore.createDescriptor(resourceId.getId(), resourceId.getVersion(),
                                                createDocumentDescriptor(createdResourceURI));
                                    }
                                }
                            }

                            return;
                        }

                        if ((isPUT(invokedHttpMethod) || isPATCH(invokedHttpMethod)) && !isDescriptorStore(uriInfo.getPath())
                                && isResourceIdValid(resourceId)) {
                            var descriptorStore = getDescriptorStore(resourceLocationUri);
                            var resourceDescriptor = (ResourceDescriptor) descriptorStore.readDescriptor(resourceId.getId(),
                                    resourceId.getVersion() - 1);
                            resourceDescriptor.setLastModifiedOn(new Date(System.currentTimeMillis()));
                            resourceDescriptor.setResource(createNewVersionOfResource(resourceDescriptor.getResource(), resourceId.getVersion()));
                            descriptorStore.updateDescriptor(resourceId.getId(), resourceId.getVersion() - 1, resourceDescriptor);
                        }
                    }
                }

                if (isDELETE(invokedHttpMethod)) {
                    String currentResourceURI = uriInfo.getRequestUri().toString();
                    var descriptorStore = getDescriptorStore(currentResourceURI);
                    IResourceStore.IResourceId resourceId = RestUtilities.extractResourceId(URI.create(currentResourceURI));
                    if (isResourceIdValid(resourceId)) {
                        ResourceDescriptor resourceDescriptor = (ResourceDescriptor) descriptorStore.readDescriptor(resourceId.getId(),
                                resourceId.getVersion());
                        resourceDescriptor.setDeleted(true);
                        descriptorStore.setDescriptor(resourceId.getId(), resourceId.getVersion(), resourceDescriptor);
                    }
                }
            }
        } catch (IResourceStore.ResourceNotFoundException e) {
            log.debug(e.getLocalizedMessage(), e);
            throw new NotFoundException(e.getLocalizedMessage());
        } catch (IResourceStore.ResourceModifiedException e) {
            log.debug(e.getLocalizedMessage(), e);
            throw new BadRequestException(e.getLocalizedMessage());
        } catch (Exception e) {
            throw sneakyThrow(e);
        }
    }

    @SuppressWarnings("rawtypes")
    private IDescriptorStore getDescriptorStore(String createdResourceURIString) {
        IDescriptorStore descriptorStore;
        if (createdResourceURIString.contains("conversation")) {
            descriptorStore = conversationDescriptorStore;
        } else {
            descriptorStore = documentDescriptorStore;
        }
        return descriptorStore;
    }

    private static URI createNewVersionOfResource(final URI resource, Integer version) {
        String resourceURIString = resource.toString();
        if (resourceURIString.contains("version")) {
            resourceURIString = resourceURIString.substring(0, resourceURIString.lastIndexOf("=") + 1);
            resourceURIString += version;
        } else {
            resourceURIString += "?version=" + version;
        }

        return URI.create(resourceURIString);
    }

    private static boolean isDescriptorStore(String uriPath) {
        return uriPath != null && uriPath.startsWith(DESCRIPTOR_STORE_PATH);
    }

    private static boolean isPUT(String resourceMethod) {
        return HttpMethod.PUT.equalsIgnoreCase(resourceMethod);
    }

    private static boolean isPATCH(String resourceMethod) {
        return HttpMethod.PATCH.equalsIgnoreCase(resourceMethod);
    }

    private static boolean isPOST(String resourceMethod) {
        return HttpMethod.POST.equalsIgnoreCase(resourceMethod);
    }

    private static boolean isDELETE(String resourceMethod) {
        return HttpMethod.DELETE.equalsIgnoreCase(resourceMethod);
    }

    private static boolean isResourceIdValid(IResourceStore.IResourceId resourceId) {
        return resourceId != null && resourceId.getId() != null && resourceId.getVersion() > 0;
    }
}
