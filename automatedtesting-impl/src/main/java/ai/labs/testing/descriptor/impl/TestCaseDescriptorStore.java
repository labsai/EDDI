package ai.labs.testing.descriptor.impl;

import ai.labs.group.IGroupStore;
import ai.labs.permission.IPermissionStore;
import ai.labs.persistence.DescriptorStore;
import ai.labs.serialization.IDocumentBuilder;
import ai.labs.testing.descriptor.ITestCaseDescriptorStore;
import ai.labs.testing.descriptor.model.TestCaseDescriptor;
import ai.labs.user.IUserStore;
import com.mongodb.client.MongoDatabase;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * @author ginccc
 */
@Singleton
public class TestCaseDescriptorStore extends DescriptorStore<TestCaseDescriptor> implements ITestCaseDescriptorStore {
    @Inject
    public TestCaseDescriptorStore(MongoDatabase database, IDocumentBuilder documentBuilder,
                                   IPermissionStore permissionStore, IUserStore userStore, IGroupStore groupStore) {
        super(database, permissionStore, userStore, groupStore, documentBuilder, TestCaseDescriptor.class);
    }
}
