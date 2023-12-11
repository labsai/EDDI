package ai.labs.eddi.testing.internal.impl.rest;

import ai.labs.eddi.configs.patch.PatchInstruction;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.runtime.ThreadContext;
import ai.labs.eddi.testing.ITestCaseStore;
import ai.labs.eddi.testing.descriptor.ITestCaseDescriptorStore;
import ai.labs.eddi.testing.descriptor.model.SimpleTestCaseDescriptor;
import ai.labs.eddi.testing.descriptor.model.TestCaseDescriptor;
import ai.labs.eddi.testing.model.TestCase;
import ai.labs.eddi.testing.model.TestCaseState;
import ai.labs.eddi.testing.rest.IRestTestCaseStore;
import ai.labs.eddi.utils.RestUtilities;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.net.URI;
import java.util.LinkedList;
import java.util.List;

import static ai.labs.eddi.utils.RuntimeUtilities.isNullOrEmpty;

/**
 * @author ginccc
 */
@ApplicationScoped
public class RestTestCaseStore implements IRestTestCaseStore {
    private final ITestCaseStore testCaseStore;
    private final ITestCaseDescriptorStore testCaseDescriptorStore;
    private final IConversationMemoryStore conversationMemoryStore;

    private static final Logger log = Logger.getLogger(RestTestCaseStore.class);

    @Inject
    public RestTestCaseStore(ITestCaseStore testCaseStore,
                             ITestCaseDescriptorStore testCaseDescriptorStore,
                             IConversationMemoryStore conversationMemoryStore) {
        this.testCaseStore = testCaseStore;
        this.testCaseDescriptorStore = testCaseDescriptorStore;
        this.conversationMemoryStore = conversationMemoryStore;
    }

    @Override
    public List<TestCaseDescriptor> readTestCaseDescriptors(String botId, Integer botVersion, Integer index, Integer limit) {
        try {
            List<TestCaseDescriptor> retConversationDescriptors = new LinkedList<>();
            List<TestCaseDescriptor> testCaseDescriptors;
            do {
                testCaseDescriptors = testCaseDescriptorStore.readDescriptors("ai.labs.testcases", null, index, limit, false);
                for (TestCaseDescriptor testCaseDescriptor : testCaseDescriptors) {
                    TestCase testCase = readTestCase(RestUtilities.extractResourceId(testCaseDescriptor.getResource()).getId());
                    if (!isNullOrEmpty(botId)) {
                        if (!botId.equals(testCase.getBotId())) {
                            continue;
                        }
                    }

                    if (!isNullOrEmpty(botVersion)) {
                        if (!botVersion.equals(testCase.getBotVersion())) {
                            continue;
                        }
                    }

                    String userId = ThreadContext.get("currentuser:userid").toString();

                    testCaseDescriptor.setLastModifiedBy(createNewUserURI(testCaseDescriptor.getLastModifiedBy(), userId));
                    testCaseDescriptor.setLastModifiedOn(testCase.getLastRun());
                    testCaseDescriptor.setTestCaseState(testCase.getTestCaseState());
                    retConversationDescriptors.add(testCaseDescriptor);
                }

                index++;
            } while (!testCaseDescriptors.isEmpty() && retConversationDescriptors.size() < limit);

            return retConversationDescriptors;

        } catch (IResourceStore.ResourceStoreException e) {
            log.error(e.getMessage(), e);
            throw new InternalServerErrorException(e.getMessage(), e);
        } catch (IResourceStore.ResourceNotFoundException e) {
            log.debug(e.getLocalizedMessage(), e);
            throw new NotFoundException();
        }
    }

    private URI createNewUserURI(URI userURI, String userId) {
        String userURIString = userURI.toString();
        return URI.create(userURIString.substring(userURIString.lastIndexOf("/")) + userId);
    }

    @Override
    public void patchDescriptor(String id, Integer version, PatchInstruction<SimpleTestCaseDescriptor> patchInstruction) {
        try {
            TestCaseDescriptor testCaseDescriptor = testCaseDescriptorStore.readDescriptor(id, version);
            SimpleTestCaseDescriptor simpleTestCaseDescriptor = patchInstruction.getDocument();

            if (patchInstruction.getOperation().equals(PatchInstruction.PatchOperation.SET)) {
                testCaseDescriptor.setName(simpleTestCaseDescriptor.getName());
                testCaseDescriptor.setDescription(simpleTestCaseDescriptor.getDescription());
            } else {
                testCaseDescriptor.setName("");
                testCaseDescriptor.setDescription("");
            }

            testCaseDescriptorStore.setDescriptor(id, version, testCaseDescriptor);
        } catch (IResourceStore.ResourceStoreException e) {
            log.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException(e.getLocalizedMessage(), e);
        } catch (IResourceStore.ResourceNotFoundException e) {
            log.debug(e.getLocalizedMessage(), e);
            throw new NotFoundException(e.getLocalizedMessage(), e);
        }
    }

    @Override
    public TestCase readTestCase(String id) {
        try {
            return testCaseStore.read(id, 0);
        } catch (IResourceStore.ResourceStoreException e) {
            log.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException(e);
        } catch (IResourceStore.ResourceNotFoundException e) {
            throw new NotFoundException();
        }
    }

    @Override
    public Response createTestCase(String id) {
        try {
            var conversationMemorySnapshot = conversationMemoryStore.loadConversationMemorySnapshot(id);

            TestCase testCase = new TestCase();
            testCase.setBotId(conversationMemorySnapshot.getBotId());
            testCase.setBotVersion(conversationMemorySnapshot.getBotVersion());
            testCase.setTestCaseState(TestCaseState.SUCCESS);
            testCase.setActual(conversationMemorySnapshot);
            testCase.setExpected(conversationMemorySnapshot);

            IResourceStore.IResourceId resourceId = testCaseStore.create(testCase);
            URI createdUri = RestUtilities.createURI(resourceURI, resourceId.getId(), versionQueryParam, 0);
            return Response.created(createdUri).build();
        } catch (IResourceStore.ResourceStoreException e) {
            log.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException(e);
        } catch (IResourceStore.ResourceNotFoundException e) {
            log.debug(e.getLocalizedMessage(), e);
            throw new NotFoundException();
        }
    }

    @Override
    public URI updateTestCase(String id, TestCase testCase) {
        try {
            testCaseStore.update(id, 0, testCase);
            return RestUtilities.createURI(resourceURI, id, versionQueryParam, 0);
        } catch (IResourceStore.ResourceStoreException e) {
            log.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException(e);
        } catch (IResourceStore.ResourceNotFoundException e) {
            log.debug(e.getLocalizedMessage(), e);
            throw new NotFoundException();
        } catch (IResourceStore.ResourceModifiedException e) {
            log.debug(e.getLocalizedMessage(), e);
            throw new WebApplicationException(Response.Status.CONFLICT);
        }
    }

    @Override
    public void deleteTestCase(String id) {
        try {
            testCaseStore.delete(id, 0);
        } catch (IResourceStore.ResourceStoreException e) {
            log.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException(e);
        } catch (IResourceStore.ResourceModifiedException e) {
            log.debug(e.getLocalizedMessage(), e);
            throw new WebApplicationException(Response.Status.CONFLICT);
        } catch (IResourceStore.ResourceNotFoundException e) {
            log.debug(e.getLocalizedMessage(), e);
            throw new NotFoundException();
        }
    }
}
