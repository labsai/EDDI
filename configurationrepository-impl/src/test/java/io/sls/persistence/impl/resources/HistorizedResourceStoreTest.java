package io.sls.persistence.impl.resources;

import io.sls.persistence.IResourceStorage;
import io.sls.persistence.IResourceStore;
import io.sls.persistence.impl.mongo.HistorizedResourceStore;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Created with IntelliJ IDEA.
 * User: Michael
 * Date: 14.08.12
 * Time: 15:07
 * To change this template use File | Settings | File Templates.
 */
public class HistorizedResourceStoreTest {
    private HistorizedResourceStore<DataClass> testResourceStore;
    private TestResourceStorage mockResourceStorage;

    private class DataClass {
        private String data;

        public String getData() {
            return data;
        }

        public void setData(String data) {
            this.data = data;
        }

    }

    private class TestResourceStorage implements IResourceStorage<DataClass> {
        private int highestId = 1;

        private Map<String, IResource> resources = new HashMap<String, IResource>();

        public Map<String, Map<Integer, IHistoryResource>> getHistory() {
            return history;
        }

        public Map<Integer, IHistoryResource> getHistory(String id) {
            return history.get(id);
        }

        private Map<String, Map<Integer, IHistoryResource>> history = new HashMap<String, Map<Integer, IHistoryResource>>();

        @Override
        public IResource newResource(DataClass content) throws IOException {
            return newResource(createId(), 1, content);
        }

        @Override
        public IResource newResource(String id, Integer version, DataClass content) {
            return new TestResource(id, version, content);
        }

        private synchronized String createId() {
            return String.valueOf(highestId++);
        }

        public Map<String, IResource> getResources() {
            return resources;
        }

        @Override
        public void store(IResource resource) {
            Object data = ((TestResource) resource).data;
            DataClass dataClass = new DataClass();
            dataClass.setData(((DataClass) data).getData());
            resources.put(resource.getId(), newResource(resource.getId(), resource.getVersion(), dataClass));
        }

        @Override
        public IResource read(String id, Integer version) {
            IResource resource = resources.get(id);

            if (resource == null) {
                return null;
            }

            if (version != resource.getVersion()) {
                return null;
            }

            return resource;
        }

        @Override
        public void remove(String id) {
            resources.remove(id);
        }

        @Override
        public void removeAllPermanently(String id) {
            resources.remove(id);
        }

        @Override
        public IHistoryResource readHistory(String id, Integer version) {
            Map<Integer, IHistoryResource> resourceHistory = history.get(id);

            if (resourceHistory == null) {
                return null;
            }

            return resourceHistory.get(version);

        }

        @Override
        public IHistoryResource readHistoryLatest(String id) {
            return null;  //To change body of implemented methods use File | Settings | File Templates.
        }

        @Override
        public IHistoryResource newHistoryResourceFor(IResource resource, boolean deleted) {
            Object data = ((TestResource) resource).data;
            DataClass dataClass = new DataClass();
            dataClass.setData(((DataClass) data).getData());
            return new TestHistoryResource(resource.getId(), resource.getVersion(), deleted, dataClass);  //To change body of implemented methods use File | Settings | File Templates.
        }

        @Override
        public Integer getCurrentVersion(String id) {
            IResource resource = resources.get(id);
            return resource.getVersion();
        }

        @Override
        public void store(IHistoryResource historyResource) {
            Map<Integer, IHistoryResource> resourceHistory = history.get(historyResource.getId());

            if (resourceHistory == null) {
                resourceHistory = new HashMap<Integer, IHistoryResource>();
                history.put(historyResource.getId(), resourceHistory);
            }


            resourceHistory.put(historyResource.getVersion(), historyResource);
        }

    }

    @Before
    public void setUp() {
        mockResourceStorage = new TestResourceStorage();
        testResourceStore = new HistorizedResourceStore<DataClass>(mockResourceStorage);
    }

    @Test
    public void testCreate() throws IResourceStore.ResourceStoreException {
        // setup
        DataClass dataClass = new DataClass();

        // test
        IResourceStore.IResourceId id = testResourceStore.create(dataClass);

        // assert
        Assert.assertNotNull(id.getId());
        Assert.assertEquals(new Integer(1), id.getVersion());
        Assert.assertEquals(1, mockResourceStorage.getResources().size());
    }

    @Test
    public void testUpdate() throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException, IResourceStore.ResourceModifiedException {
        // setup
        DataClass dataClass = new DataClass();
        IResourceStore.IResourceId id = testResourceStore.create(dataClass);

        // test
        DataClass dataClass1 = new DataClass();
        Integer newVersion = testResourceStore.update(id.getId(), id.getVersion(), dataClass);

        // assert
        Assert.assertEquals(new Integer(2), newVersion);
        Assert.assertEquals(1, mockResourceStorage.getResources().size());

        IResourceStorage.IResource resource = mockResourceStorage.getResources().get(id.getId());
        Assert.assertEquals(id.getId(), resource.getId());
        Assert.assertEquals(new Integer(2), resource.getVersion());

        Map<Integer, IResourceStorage.IHistoryResource> history = mockResourceStorage.getHistory().get(id.getId());
        Assert.assertEquals(1, history.size());
    }

    @Test
    public void testReadNotFound() throws IResourceStore.ResourceStoreException {
        // test
        try {
            testResourceStore.read("unknownId", 1);
            Assert.fail();
        } catch (IResourceStore.ResourceNotFoundException e) {
            // OK
        }

    }

    @Test
    public void testDelete() throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException, IResourceStore.ResourceModifiedException {
        // setup
        DataClass dataClass = new DataClass();
        IResourceStore.IResourceId id = testResourceStore.create(dataClass);

        // test
        testResourceStore.delete(id.getId(), id.getVersion());

        // assert
        Assert.assertEquals(0, mockResourceStorage.getResources().size());
        Assert.assertEquals(1, mockResourceStorage.getHistory(id.getId()).size());
    }

    @Test
    public void testRead() throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException {
        // setup
        DataClass data = new DataClass();
        data.setData("value");
        IResourceStore.IResourceId id = testResourceStore.create(data);

        // test
        DataClass read = testResourceStore.read(id.getId(), id.getVersion());

        // assert
        Assert.assertNotNull(read);
        Assert.assertNotSame(data, read);
        Assert.assertEquals("value", read.getData());

    }

    @Test
    public void testReadFromHistory() throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException, IResourceStore.ResourceModifiedException {
        // setup
        DataClass data = new DataClass();
        data.setData("value");
        IResourceStore.IResourceId id = testResourceStore.create(data);
        data.setData("updatedValue");
        testResourceStore.update(id.getId(), id.getVersion(), data);

        // test
        DataClass read = testResourceStore.read(id.getId(), id.getVersion());

        // assert
        Assert.assertNotNull(read);
        Assert.assertNotSame(data, read);
        Assert.assertEquals("value", read.getData());
    }

    private class TestHistoryResource extends TestResource implements IResourceStorage.IHistoryResource {

        private boolean deleted;

        public TestHistoryResource(String id, int version, boolean deleted, Object content) {
            super(id, version, content);
            this.deleted = deleted;
        }

        @Override
        public boolean isDeleted() {
            return deleted;
        }
    }

    private class TestResource implements IResourceStorage.IResource {
        private String id;
        private int version;

        private Object data;

        public TestResource(String id, int version, Object content) {
            this.id = id;
            this.version = version;
            this.data = content;
        }

        @Override
        public Integer getVersion() {
            return version;  //To change body of implemented methods use File | Settings | File Templates.
        }

        @Override
        public Object getData() {
            return data;
        }

        @Override
        public String getId() {
            return id;
        }
    }


}
