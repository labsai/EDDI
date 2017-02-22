package ai.labs.configuration.utilities;

import ai.labs.memory.IConversationMemoryStore;
import ai.labs.permission.IPermissionStore;
import ai.labs.persistence.IResourceStore;
import ai.labs.resources.rest.behavior.IBehaviorStore;
import ai.labs.resources.rest.bots.IBotStore;
import ai.labs.resources.rest.documentdescriptor.IDocumentDescriptorStore;
import ai.labs.resources.rest.documentdescriptor.model.DocumentDescriptor;
import ai.labs.resources.rest.output.IOutputStore;
import ai.labs.resources.rest.packages.IPackageStore;
import ai.labs.resources.rest.regulardictionary.IRegularDictionaryStore;
import ai.labs.utilities.RestUtilities;

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
        restInterfaces.put("ai.labs.bot", botStore::deleteAllPermanently);
        restInterfaces.put("ai.labs.package", packageStore::deleteAllPermanently);
        restInterfaces.put("ai.labs.regulardictionary", regularDictionaryStore::deleteAllPermanently);
        restInterfaces.put("ai.labs.behavior", behaviorStore::deleteAllPermanently);
        restInterfaces.put("ai.labs.output", outputStore::deleteAllPermanently);
        restInterfaces.put("ai.labs.conversation", conversationMemoryStore::deleteConversationMemorySnapshot);
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
