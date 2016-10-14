package io.sls.testing.impl.rest;

import io.sls.memory.IConversationMemoryStore;
import io.sls.memory.model.ConversationMemorySnapshot;
import io.sls.persistence.IResourceStore;
import io.sls.resources.rest.patch.PatchInstruction;
import io.sls.runtime.ThreadContext;
import io.sls.testing.ITestCaseStore;
import io.sls.testing.descriptor.ITestCaseDescriptorStore;
import io.sls.testing.descriptor.model.SimpleTestCaseDescriptor;
import io.sls.testing.descriptor.model.TestCaseDescriptor;
import io.sls.testing.model.TestCase;
import io.sls.testing.model.TestCaseState;
import io.sls.testing.rest.IRestTestCaseStore;
import io.sls.utilities.RestUtilities;
import io.sls.utilities.RuntimeUtilities;
import org.jboss.resteasy.plugins.guice.RequestScoped;
import org.jboss.resteasy.spi.HttpResponse;
import org.jboss.resteasy.spi.NoLogWebApplicationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.ws.rs.InternalServerErrorException;
import javax.ws.rs.NotFoundException;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;
import java.net.URI;
import java.util.LinkedList;
import java.util.List;

/**
 * Copyright by Spoken Language System. All rights reserved.
 * User: jarisch
 * Date: 22.11.12
 * Time: 15:35
 */
@RequestScoped
public class RestTestCaseStore implements IRestTestCaseStore {
    private final HttpResponse httpResponse;
    private final ITestCaseStore testCaseStore;
    private final ITestCaseDescriptorStore testCaseDescriptorStore;
    private final IConversationMemoryStore conversationMemoryStore;

    private Logger logger = LoggerFactory.getLogger(this.getClass());


    @Inject
    public RestTestCaseStore(@Context HttpResponse httpResponse,
                             ITestCaseStore testCaseStore,
                             ITestCaseDescriptorStore testCaseDescriptorStore,
                             IConversationMemoryStore conversationMemoryStore) {
        this.httpResponse = httpResponse;
        this.testCaseStore = testCaseStore;
        this.testCaseDescriptorStore = testCaseDescriptorStore;
        this.conversationMemoryStore = conversationMemoryStore;
    }

    @Override
    public List<TestCaseDescriptor> readTestCaseDescriptors(String botId, Integer botVersion, Integer index, Integer limit) {
        try {
            List<TestCaseDescriptor> retConversationDescriptors = new LinkedList<TestCaseDescriptor>();
            List<TestCaseDescriptor> testCaseDescriptors;
            do {
                testCaseDescriptors = testCaseDescriptorStore.readDescriptors("io.sls.testcases", null, index, limit, false);
                for (TestCaseDescriptor testCaseDescriptor : testCaseDescriptors) {
                    TestCase testCase = readTestCase(RestUtilities.extractResourceId(testCaseDescriptor.getResource()).getId());
                    if (!RuntimeUtilities.isNullOrEmpty(botId)) {
                        if (!botId.equals(testCase.getBotId())) {
                            continue;
                        }
                    }

                    if (!RuntimeUtilities.isNullOrEmpty(botVersion)) {
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
            logger.error(e.getMessage(), e);
            throw new InternalServerErrorException(e.getMessage(), e);
        } catch (IResourceStore.ResourceNotFoundException e) {
            throw new NoLogWebApplicationException(e);
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
            logger.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException(e.getLocalizedMessage(), e);
        } catch (IResourceStore.ResourceNotFoundException e) {
            throw new NotFoundException(e.getLocalizedMessage(), e);
        }
    }

    @Override
    public TestCase readTestCase(String id) {
        try {
            return testCaseStore.read(id, 0);
        } catch (IResourceStore.ResourceStoreException e) {
            logger.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException(e);
        } catch (IResourceStore.ResourceNotFoundException e) {
            throw new NoLogWebApplicationException(Response.Status.NOT_FOUND);
        }
    }

    @Override
    public URI createTestCase(String conversationId) {
        try {
            ConversationMemorySnapshot conversationMemorySnapshot = conversationMemoryStore.loadConversationMemorySnapshot(conversationId);

            TestCase testCase = new TestCase();
            testCase.setBotId(conversationMemorySnapshot.getBotId());
            testCase.setBotVersion(conversationMemorySnapshot.getBotVersion());
            testCase.setTestCaseState(TestCaseState.SUCCESS);
            testCase.setActual(conversationMemorySnapshot);
            testCase.setExpected(conversationMemorySnapshot);

            IResourceStore.IResourceId resourceId = testCaseStore.create(testCase);
            httpResponse.setStatus(Response.Status.CREATED.getStatusCode());
            return RestUtilities.createURI(resourceURI, resourceId.getId(), versionQueryParam, 0);
        } catch (IResourceStore.ResourceStoreException e) {
            logger.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException(e);
        } catch (IResourceStore.ResourceNotFoundException e) {
            throw new NoLogWebApplicationException(Response.Status.NOT_FOUND);
        }
    }

    @Override
    public URI updateTestCase(String id, TestCase testCase) {
        try {
            testCaseStore.update(id, 0, testCase);
            return RestUtilities.createURI(resourceURI, id, versionQueryParam, 0);
        } catch (IResourceStore.ResourceStoreException e) {
            logger.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException(e);
        } catch (IResourceStore.ResourceNotFoundException e) {
            throw new NoLogWebApplicationException(Response.Status.NOT_FOUND);
        } catch (IResourceStore.ResourceModifiedException e) {
            throw new NoLogWebApplicationException(Response.Status.CONFLICT);
        }
    }

    @Override
    public void deleteTestCase(String id) {
        try {
            testCaseStore.delete(id, 0);
        } catch (IResourceStore.ResourceStoreException e) {
            logger.error(e.getLocalizedMessage(), e);
            throw new InternalServerErrorException(e);
        } catch (IResourceStore.ResourceModifiedException e) {
            throw new NoLogWebApplicationException(Response.Status.CONFLICT);
        } catch (IResourceStore.ResourceNotFoundException e) {
            throw new NoLogWebApplicationException(Response.Status.NOT_FOUND);
        }
    }
}
