package io.sls.configuration.utilities;

import io.sls.memory.IConversationMemoryStore;
import io.sls.permission.IPermissionStore;
import io.sls.persistence.IResourceStore;
import io.sls.resources.rest.behavior.IBehaviorStore;
import io.sls.resources.rest.bots.IBotStore;
import io.sls.resources.rest.documentdescriptor.IDocumentDescriptorStore;
import io.sls.resources.rest.documentdescriptor.model.DocumentDescriptor;
import io.sls.resources.rest.output.IOutputStore;
import io.sls.resources.rest.packages.IPackageStore;
import io.sls.resources.rest.regulardictionary.IRegularDictionaryStore;
import io.sls.utilities.RestUtilities;

import javax.inject.Inject;
import java.net.URI;
import java.util.HashMap;
import java.util.List;

/**
 * @author ginccc
 */
public class RemoveDeletedResources {
    private HashMap<String, IResourceDeletion> restInterfaces;
    private final IDocumentDescriptorStore descriptorStore;
    private final IPermissionStore permissionStore;

    @Inject
    public RemoveDeletedResources(IDocumentDescriptorStore descriptorStore,
                                  IPermissionStore permissionStore,
                                  IBotStore botStore,
                                  IPackageStore packageStore,
                                  IRegularDictionaryStore regularDictionaryStore,
                                  IBehaviorStore behaviorStore,
                                  IOutputStore outputStore,
                                  IConversationMemoryStore conversationMemoryStore) {
        this.descriptorStore = descriptorStore;
        this.permissionStore = permissionStore;

        this.restInterfaces = new HashMap<>();
        restInterfaces.put("io.sls.bot", botStore::deleteAllPermanently);
        restInterfaces.put("io.sls.package", packageStore::deleteAllPermanently);
        restInterfaces.put("ai.labs.regulardictionary", regularDictionaryStore::deleteAllPermanently);
        restInterfaces.put("ai.labs.behavior", behaviorStore::deleteAllPermanently);
        restInterfaces.put("ai.labs.output", outputStore::deleteAllPermanently);
        restInterfaces.put("io.sls.conversation", conversationMemoryStore::deleteConversationMemorySnapshot);
    }


    private void deleteAllDocumentsMarkedForDeletion() throws Exception {
        List<DocumentDescriptor> documentDescriptors = descriptorStore.readDescriptors("", "", 0, 0, true);
        for (DocumentDescriptor documentDescriptor : documentDescriptors) {
            if (documentDescriptor.isDeleted()) {
                URI url = URI.create(documentDescriptor.getResource().toString());
                IResourceDeletion resourceDeletion = restInterfaces.get(url.getHost());
                IResourceStore.IResourceId resourceId = RestUtilities.extractResourceId(documentDescriptor.getResource());
                String id = resourceId.getId();

                resourceDeletion.delete(id);
                descriptorStore.deleteAllDescriptor(id);
                permissionStore.deletePermissions(id);
            }
        }
    }

    private interface IResourceDeletion {
        void delete(String id) throws Exception;
    }
}
