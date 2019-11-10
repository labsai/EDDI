package ai.labs.resources.impl.interceptors;

import ai.labs.memory.IConversationMemoryStore;
import ai.labs.memory.descriptor.IConversationDescriptorStore;
import ai.labs.models.ResourceDescriptor;
import ai.labs.persistence.IDescriptorStore;
import ai.labs.persistence.IResourceStore;
import ai.labs.resources.rest.documentdescriptor.IDocumentDescriptorStore;
import ai.labs.resources.rest.method.PATCH;
import ai.labs.runtime.DependencyInjector;
import ai.labs.runtime.ThreadContext;
import ai.labs.testing.descriptor.ITestCaseDescriptorStore;
import ai.labs.testing.descriptor.model.TestCaseDescriptor;
import ai.labs.user.IUserStore;
import ai.labs.user.impl.utilities.UserUtilities;
import ai.labs.utilities.RestUtilities;
import ai.labs.utilities.SecurityUtilities;
import lombok.extern.slf4j.Slf4j;
import org.jboss.resteasy.plugins.guice.RequestScoped;

import javax.inject.Inject;
import javax.ws.rs.BadRequestException;
import javax.ws.rs.DELETE;
import javax.ws.rs.InternalServerErrorException;
import javax.ws.rs.NotFoundException;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.container.ContainerResponseContext;
import javax.ws.rs.container.ContainerResponseFilter;
import javax.ws.rs.container.ResourceInfo;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.ext.Provider;
import java.lang.reflect.Method;
import java.net.URI;
import java.security.Principal;
import java.util.Date;

import static ai.labs.resources.impl.utilities.ResourceUtilities.createDocumentDescriptor;

/**
 * @author ginccc
 */
@Provider
@Slf4j
@RequestScoped
public class DocumentDescriptorInterceptor implements ContainerResponseFilter {
    private static final String METHOD_NAME_UPDATE_DESCRIPTOR = "updateDescriptor";
    private static final String METHOD_NAME_UPDATE_PERMISSIONS = "updatePermissions";
    private final IDocumentDescriptorStore documentDescriptorStore;
    private final IConversationDescriptorStore conversationDescriptorStore;
    private final IUserStore userStore;
    private final IConversationMemoryStore conversationMemoryStore;
    private final ITestCaseDescriptorStore testCaseDescriptorStore;

    @Inject
    @Context
    private ResourceInfo resourceInfo;

    public DocumentDescriptorInterceptor() {
        DependencyInjector injector = DependencyInjector.getInstance();
        this.userStore = injector.getInstance(IUserStore.class);
        this.documentDescriptorStore = injector.getInstance(IDocumentDescriptorStore.class);
        this.conversationDescriptorStore = injector.getInstance(IConversationDescriptorStore.class);
        this.conversationMemoryStore = injector.getInstance(IConversationMemoryStore.class);
        this.testCaseDescriptorStore = injector.getInstance(ITestCaseDescriptorStore.class);
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
                        URI userURI = UserUtilities.getUserURI(userStore, userPrincipal);

                        if (isPOST(resourceMethod)) {
                            // the resource was created successfully
                            if (httpStatus == 201) {
                                if (resourceLocationUri.startsWith("eddi://ai.labs.testcases")) {
                                    testCaseDescriptorStore.createDescriptor(resourceId.getId(), resourceId.getVersion(), createTestCaseDescriptor(createdResourceURI, userURI));
                                } else if (isResourceIdValid(resourceId) && !resourceLocationUri.startsWith("eddi://ai.labs.conversation")) {
                                    try {
                                        documentDescriptorStore.readDescriptor(resourceId.getId(), resourceId.getVersion());
                                    } catch (IResourceStore.ResourceNotFoundException e) {
                                        documentDescriptorStore.createDescriptor(resourceId.getId(), resourceId.getVersion(),
                                                createDocumentDescriptor(createdResourceURI, userURI));
                                    }
                                }
                            }

                            return;
                        }

                        if ((isPUT(resourceMethod) || isPATCH(resourceMethod)) && !isUpdateDescriptor(resourceMethod) && !isUpdatePermissions(resourceMethod)) {
                            IDescriptorStore descriptorStore = getDescriptorStore(resourceLocationUri);
                            ResourceDescriptor resourceDescriptor = (ResourceDescriptor) descriptorStore.readDescriptor(resourceId.getId(), resourceId.getVersion() - 1);
                            resourceDescriptor.setLastModifiedOn(new Date(System.currentTimeMillis()));
                            resourceDescriptor.setLastModifiedBy(UserUtilities.getUserURI(userStore, SecurityUtilities.getPrincipal(ThreadContext.getSubject())));
                            resourceDescriptor.setResource(createNewVersionOfResource(resourceDescriptor.getResource(), resourceId.getVersion()));
                            descriptorStore.updateDescriptor(resourceId.getId(), resourceId.getVersion() - 1, resourceDescriptor);
                        }
                    }
                }

                if (isDELETE(resourceMethod)) {
                    String currentResourceURI = (String) ThreadContext.get("currentResourceURI");
                    IDescriptorStore descriptorStore = getDescriptorStore(currentResourceURI);
                    IResourceStore.IResourceId resourceId = RestUtilities.extractResourceId(URI.create(currentResourceURI));
                    if (isResourceIdValid(resourceId)) {
                        ResourceDescriptor resourceDescriptor = (ResourceDescriptor) descriptorStore.readDescriptor(resourceId.getId(), resourceId.getVersion());
                        resourceDescriptor.setDeleted(true);
                        descriptorStore.setDescriptor(resourceId.getId(), resourceId.getVersion(), resourceDescriptor);
                    }
                }
            }
        } catch (IResourceStore.ResourceStoreException e) {
            log.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException();
        } catch (IResourceStore.ResourceNotFoundException e) {
            log.debug(e.getLocalizedMessage(), e);
            throw new NotFoundException(e.getLocalizedMessage());
        } catch (IResourceStore.ResourceModifiedException e) {
            log.debug(e.getLocalizedMessage(), e);
            throw new BadRequestException(e.getLocalizedMessage());
        }
    }

    private IDescriptorStore getDescriptorStore(String createdResourceURIString) {
        IDescriptorStore descriptorStore;
        if (createdResourceURIString.startsWith("eddi://ai.labs.testcases")) {
            descriptorStore = testCaseDescriptorStore;
        } else if (createdResourceURIString.startsWith("eddi://ai.labs.conversation")) {
            descriptorStore = conversationDescriptorStore;
        } else {
            descriptorStore = documentDescriptorStore;
        }
        return descriptorStore;
    }

    private static TestCaseDescriptor createTestCaseDescriptor(URI resource, URI author) {
        Date current = new Date(System.currentTimeMillis());

        TestCaseDescriptor descriptor = new TestCaseDescriptor();
        descriptor.setName("");
        descriptor.setDescription("");
        descriptor.setResource(resource);
        descriptor.setCreatedBy(author);
        descriptor.setCreatedOn(current);
        descriptor.setLastModifiedOn(current);
        descriptor.setLastModifiedBy(author);

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

    private static boolean isUpdatePermissions(Method resourceMethod) {
        return resourceMethod.getName().equals(METHOD_NAME_UPDATE_PERMISSIONS);
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
        return resourceId.getId() != null && resourceId.getVersion() > 0;
    }
}
