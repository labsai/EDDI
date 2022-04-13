package ai.labs.eddi.engine.runtime.rest.interceptors;

import ai.labs.eddi.configs.documentdescriptor.IDocumentDescriptorStore;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.serialization.IDescriptorStore;
import ai.labs.eddi.engine.memory.descriptor.IConversationDescriptorStore;
import ai.labs.eddi.engine.runtime.ThreadContext;
import ai.labs.eddi.models.ResourceDescriptor;
import ai.labs.eddi.testing.descriptor.ITestCaseDescriptorStore;
import ai.labs.eddi.testing.descriptor.model.TestCaseDescriptor;
import ai.labs.eddi.utils.RestUtilities;
import ai.labs.eddi.utils.SecurityUtilities;
import org.jboss.logging.Logger;

import javax.inject.Inject;
import javax.ws.rs.*;
import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.container.ContainerResponseContext;
import javax.ws.rs.container.ContainerResponseFilter;
import javax.ws.rs.container.ResourceInfo;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.UriInfo;
import javax.ws.rs.ext.Provider;
import java.lang.reflect.Method;
import java.net.URI;
import java.security.Principal;
import java.util.Date;

import static ai.labs.eddi.configs.utilities.ResourceUtilities.createDocumentDescriptor;

/**
 * @author ginccc
 */

@Provider
public class DocumentDescriptorInterceptor implements ContainerResponseFilter {
    private static final String METHOD_NAME_UPDATE_DESCRIPTOR = "updateDescriptor";
    private static final String METHOD_NAME_UPDATE_PERMISSIONS = "updatePermissions";
    private final IDocumentDescriptorStore documentDescriptorStore;
    private final IConversationDescriptorStore conversationDescriptorStore;
    private final ITestCaseDescriptorStore testCaseDescriptorStore;

    private static final Logger log = Logger.getLogger(DocumentDescriptorInterceptor.class);

    @Context
    ResourceInfo resourceInfo;

    @Context
    UriInfo uriInfo;

    @Inject
    public DocumentDescriptorInterceptor(IDocumentDescriptorStore documentDescriptorStore,
                                         IConversationDescriptorStore conversationDescriptorStore,
                                         ITestCaseDescriptorStore testCaseDescriptorStore) {
        this.documentDescriptorStore = documentDescriptorStore;
        this.conversationDescriptorStore = conversationDescriptorStore;
        this.testCaseDescriptorStore = testCaseDescriptorStore;
    }

    @Override
    public void filter(ContainerRequestContext contextRequest, ContainerResponseContext contextResponse) {
        try {
            int httpStatus = contextResponse.getStatus();

            if (httpStatus < 200 || httpStatus >= 300 || resourceInfo == null) {
                return;
            }

            Method resourceMethod = resourceInfo.getResourceMethod();
            if (resourceMethod != null && (isPUT(resourceMethod) || isPATCH(resourceMethod) || isPOST(resourceMethod) || isDELETE(resourceMethod))) {
                String resourceLocationUri = contextResponse.getHeaderString(HttpHeaders.LOCATION);
                if (resourceLocationUri != null) {
                    if (resourceLocationUri.contains("://")) {
                        URI createdResourceURI = URI.create(resourceLocationUri);
                        IResourceStore.IResourceId resourceId = RestUtilities.extractResourceId(createdResourceURI);
                        Principal userPrincipal = SecurityUtilities.getPrincipal(ThreadContext.getSubject());

                        if (isPOST(resourceMethod)) {
                            // the resource was created successfully
                            if (httpStatus == 201) {
                                if (resourceLocationUri.startsWith("eddi://ai.labs.testcases")) {
                                    testCaseDescriptorStore.createDescriptor(
                                            resourceId.getId(), resourceId.getVersion(),
                                            createTestCaseDescriptor(createdResourceURI));
                                } else if (isResourceIdValid(resourceId) &&
                                        !resourceLocationUri.startsWith("eddi://ai.labs.conversation")) {
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

                        if ((isPUT(resourceMethod) || isPATCH(resourceMethod)) && !isUpdateDescriptor(resourceMethod)) {
                            var descriptorStore = getDescriptorStore(resourceLocationUri);
                            ResourceDescriptor resourceDescriptor = (ResourceDescriptor) descriptorStore.readDescriptor(resourceId.getId(), resourceId.getVersion() - 1);
                            resourceDescriptor.setLastModifiedOn(new Date(System.currentTimeMillis()));
                            /*resourceDescriptor.setLastModifiedBy(UserUtilities.getUserURI(userStore, SecurityUtilities.getPrincipal(ThreadContext.getSubject())));*/
                            resourceDescriptor.setResource(createNewVersionOfResource(resourceDescriptor.getResource(), resourceId.getVersion()));
                            descriptorStore.updateDescriptor(resourceId.getId(), resourceId.getVersion() - 1, resourceDescriptor);
                        }
                    }
                }

                if (isDELETE(resourceMethod)) {
                    String currentResourceURI = uriInfo.getRequestUri().toString();
                    var descriptorStore = getDescriptorStore(currentResourceURI);
                    IResourceStore.IResourceId resourceId = RestUtilities.extractResourceId(URI.create(currentResourceURI));
                    if (isResourceIdValid(resourceId)) {
                        ResourceDescriptor resourceDescriptor = (ResourceDescriptor) descriptorStore.readDescriptor(resourceId.getId(), resourceId.getVersion());
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
            log.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException();
        }
    }

    private IDescriptorStore getDescriptorStore(String createdResourceURIString) {
        IDescriptorStore descriptorStore;
        if (createdResourceURIString.contains("testcases")) {
            descriptorStore = testCaseDescriptorStore;
        } else if (createdResourceURIString.contains("conversation")) {
            descriptorStore = conversationDescriptorStore;
        } else {
            descriptorStore = documentDescriptorStore;
        }
        return descriptorStore;
    }

    private static TestCaseDescriptor createTestCaseDescriptor(URI resource/*, URI author*/) {
        Date current = new Date(System.currentTimeMillis());

        TestCaseDescriptor descriptor = new TestCaseDescriptor();
        descriptor.setName("");
        descriptor.setDescription("");
        descriptor.setResource(resource);
        descriptor.setCreatedOn(current);
        descriptor.setLastModifiedOn(current);
        /*descriptor.setCreatedBy(author);
        descriptor.setLastModifiedBy(author);*/

        return descriptor;
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

    private static boolean isUpdateDescriptor(Method resourceMethod) {
        return resourceMethod.getName().equals(METHOD_NAME_UPDATE_DESCRIPTOR);
    }

    private static boolean isPUT(Method resourceMethod) {
        return resourceMethod.isAnnotationPresent(PUT.class);
    }

    private static boolean isPATCH(Method resourceMethod) {
        return resourceMethod.isAnnotationPresent(PATCH.class);
    }

    private static boolean isPOST(Method resourceMethod) {
        return resourceMethod.isAnnotationPresent(POST.class);
    }

    private static boolean isDELETE(Method resourceMethod) {
        return resourceMethod.isAnnotationPresent(DELETE.class);
    }

    private static boolean isResourceIdValid(IResourceStore.IResourceId resourceId) {
        return resourceId != null && resourceId.getId() != null && resourceId.getVersion() > 0;
    }
}
