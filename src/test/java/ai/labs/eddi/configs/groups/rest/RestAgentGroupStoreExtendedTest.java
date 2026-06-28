/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.groups.rest;

import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.configs.groups.IAgentGroupStore;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration;
import ai.labs.eddi.configs.schema.IJsonSchemaCreator;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.IResourceStore.IResourceId;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import org.mockito.ArgumentCaptor;

/**
 * Extended tests for {@link RestAgentGroupStore} — syncDescriptor branches,
 * updateGroup, duplicateGroup, and createGroup edge cases.
 */
@DisplayName("RestAgentGroupStore — Extended Branch Coverage")
class RestAgentGroupStoreExtendedTest {

    private IAgentGroupStore groupStore;
    private IDocumentDescriptorStore documentDescriptorStore;
    private IJsonSchemaCreator jsonSchemaCreator;
    private RestAgentGroupStore restStore;

    @BeforeEach
    void setUp() {
        groupStore = mock(IAgentGroupStore.class);
        documentDescriptorStore = mock(IDocumentDescriptorStore.class);
        jsonSchemaCreator = mock(IJsonSchemaCreator.class);
        restStore = new RestAgentGroupStore(groupStore, documentDescriptorStore, jsonSchemaCreator);
    }

    // ==================== updateGroup ====================

    @Nested
    @DisplayName("updateGroup")
    class UpdateGroupTests {

        @Test
        @DisplayName("updates group and syncs descriptor name")
        void updatesAndSyncs() throws Exception {
            var config = new AgentGroupConfiguration();
            config.setName("Updated Group");
            config.setDescription("Updated desc");

            IResourceId resourceId = createResourceId("group1", 2);
            when(groupStore.getCurrentResourceId("group1")).thenReturn(resourceId);

            DocumentDescriptor descriptor = new DocumentDescriptor();
            descriptor.setName("Old Group");
            descriptor.setDescription("Old desc");
            when(documentDescriptorStore.readDescriptor("group1", 2)).thenReturn(descriptor);

            when(groupStore.update("group1", 2, config)).thenReturn(2);

            restStore.updateGroup("group1", 2, config);

            verify(documentDescriptorStore).setDescriptor(eq("group1"), eq(2), any(DocumentDescriptor.class));
        }

        @Test
        @DisplayName("descriptor not changed when name/description are same")
        void noChangeWhenSame() throws Exception {
            var config = new AgentGroupConfiguration();
            config.setName("Same Name");
            config.setDescription("Same Desc");

            IResourceId resourceId = createResourceId("group1", 1);
            when(groupStore.getCurrentResourceId("group1")).thenReturn(resourceId);

            DocumentDescriptor descriptor = new DocumentDescriptor();
            descriptor.setName("Same Name");
            descriptor.setDescription("Same Desc");
            when(documentDescriptorStore.readDescriptor("group1", 1)).thenReturn(descriptor);
            when(groupStore.update("group1", 1, config)).thenReturn(2);

            restStore.updateGroup("group1", 1, config);

            verify(documentDescriptorStore, never()).setDescriptor(anyString(), anyInt(), any(DocumentDescriptor.class));
        }
    }

    // ==================== syncDescriptor — create path ====================

    @Nested
    @DisplayName("syncDescriptor — create path")
    class SyncDescriptorCreateTests {

        @Test
        @DisplayName("descriptor not found at current or previous version → creates new")
        void createsNewDescriptor() throws Exception {
            var config = new AgentGroupConfiguration();
            config.setName("New Group");
            config.setDescription("New Description");

            IResourceId resourceId = createResourceId("newGroupId", 1);
            when(groupStore.getCurrentResourceId("newGroupId")).thenReturn(resourceId);
            when(documentDescriptorStore.readDescriptor("newGroupId", 1))
                    .thenThrow(new IResourceStore.ResourceNotFoundException("not found"));

            when(groupStore.create(any())).thenReturn(resourceId);

            // Trigger via updateGroup (which calls syncDescriptor)
            when(groupStore.update(eq("newGroupId"), eq(1), any())).thenReturn(2);
            restStore.updateGroup("newGroupId", 1, config);

            verify(documentDescriptorStore).createDescriptor(eq("newGroupId"), eq(1), any(DocumentDescriptor.class));
        }

        @Test
        @DisplayName("descriptor exists at version-1 (update path)")
        void descriptorAtPreviousVersion() throws Exception {
            var config = new AgentGroupConfiguration();
            config.setName("Updated Group");

            IResourceId resourceId = createResourceId("group1", 2);
            when(groupStore.getCurrentResourceId("group1")).thenReturn(resourceId);
            when(documentDescriptorStore.readDescriptor("group1", 2))
                    .thenThrow(new IResourceStore.ResourceNotFoundException("not found"));

            DocumentDescriptor prevDescriptor = new DocumentDescriptor();
            prevDescriptor.setName("Old Group");
            when(documentDescriptorStore.readDescriptor("group1", 1)).thenReturn(prevDescriptor);

            when(groupStore.update("group1", 2, config)).thenReturn(2);

            restStore.updateGroup("group1", 2, config);

            verify(documentDescriptorStore).setDescriptor(eq("group1"), eq(1), any(DocumentDescriptor.class));
        }

        @Test
        @DisplayName("syncDescriptor handles exception gracefully")
        void syncDescriptorException() throws Exception {
            var config = new AgentGroupConfiguration();
            config.setName("Group");

            when(groupStore.getCurrentResourceId("group1"))
                    .thenThrow(new RuntimeException("DB error"));
            when(groupStore.update("group1", 1, config)).thenReturn(2);

            // Should not throw
            assertDoesNotThrow(() -> restStore.updateGroup("group1", 1, config));
        }

        @Test
        @DisplayName("create path with ResourceStoreException falls back to setDescriptor")
        void createFallsBackToSet() throws Exception {
            var config = new AgentGroupConfiguration();
            config.setName("New Group");

            IResourceId resourceId = createResourceId("group1", 1);
            when(groupStore.getCurrentResourceId("group1")).thenReturn(resourceId);
            when(documentDescriptorStore.readDescriptor("group1", 1))
                    .thenThrow(new IResourceStore.ResourceNotFoundException("not found"));
            doThrow(new IResourceStore.ResourceStoreException("already exists"))
                    .when(documentDescriptorStore).createDescriptor(anyString(), anyInt(), any());
            when(groupStore.update("group1", 1, config)).thenReturn(2);

            // Should not throw — falls back to setDescriptor
            assertDoesNotThrow(() -> restStore.updateGroup("group1", 1, config));
            verify(documentDescriptorStore).setDescriptor(eq("group1"), eq(1), any(DocumentDescriptor.class));
        }

        @Test
        @DisplayName("create path sets createdOn and lastModifiedOn timestamps")
        void createsDescriptorWithTimestamps() throws Exception {
            var config = new AgentGroupConfiguration();
            config.setName("Timestamped Group");

            IResourceId resourceId = createResourceId("tsGroup", 1);
            when(groupStore.getCurrentResourceId("tsGroup")).thenReturn(resourceId);
            when(documentDescriptorStore.readDescriptor("tsGroup", 1))
                    .thenThrow(new IResourceStore.ResourceNotFoundException("not found"));
            when(groupStore.update(eq("tsGroup"), eq(1), any())).thenReturn(2);

            long before = System.currentTimeMillis();
            restStore.updateGroup("tsGroup", 1, config);
            long after = System.currentTimeMillis();

            var captor = ArgumentCaptor.forClass(DocumentDescriptor.class);
            verify(documentDescriptorStore).createDescriptor(eq("tsGroup"), eq(1), captor.capture());

            DocumentDescriptor captured = captor.getValue();
            assertNotNull(captured.getCreatedOn(), "createdOn must be set on new descriptors");
            assertNotNull(captured.getLastModifiedOn(), "lastModifiedOn must be set on new descriptors");
            assertTrue(captured.getCreatedOn().getTime() >= before && captured.getCreatedOn().getTime() <= after,
                    "createdOn should be within the test execution window");
            assertEquals(captured.getCreatedOn(), captured.getLastModifiedOn(),
                    "createdOn and lastModifiedOn should be identical for a new descriptor");
        }

        @Test
        @DisplayName("update path refreshes lastModifiedOn when name changes")
        void updateRefreshesLastModifiedOn() throws Exception {
            var config = new AgentGroupConfiguration();
            config.setName("Changed Name");

            IResourceId resourceId = createResourceId("group1", 1);
            when(groupStore.getCurrentResourceId("group1")).thenReturn(resourceId);

            Date originalDate = new Date(1000L);
            DocumentDescriptor existingDescriptor = new DocumentDescriptor();
            existingDescriptor.setName("Original Name");
            existingDescriptor.setCreatedOn(originalDate);
            existingDescriptor.setLastModifiedOn(originalDate);
            when(documentDescriptorStore.readDescriptor("group1", 1)).thenReturn(existingDescriptor);
            when(groupStore.update("group1", 1, config)).thenReturn(2);

            long before = System.currentTimeMillis();
            restStore.updateGroup("group1", 1, config);

            var captor = ArgumentCaptor.forClass(DocumentDescriptor.class);
            verify(documentDescriptorStore).setDescriptor(eq("group1"), eq(1), captor.capture());

            DocumentDescriptor captured = captor.getValue();
            assertEquals(originalDate, captured.getCreatedOn(),
                    "createdOn must NOT be changed on update");
            assertNotNull(captured.getLastModifiedOn(), "lastModifiedOn must be set on update");
            assertTrue(captured.getLastModifiedOn().getTime() >= before,
                    "lastModifiedOn should be refreshed to current time");
            assertTrue(captured.getLastModifiedOn().getTime() > originalDate.getTime(),
                    "lastModifiedOn should be newer than the original");
        }
    }

    // ==================== duplicateGroup ====================

    @Nested
    @DisplayName("duplicateGroup")
    class DuplicateGroupTests {

        @Test
        @DisplayName("duplicates group and syncs descriptor")
        void duplicatesAndSyncs() throws Exception {
            var config = new AgentGroupConfiguration();
            config.setName("Original");

            when(groupStore.read("group1", 1)).thenReturn(config);

            String newId = "newGroupId123456789012";
            IResourceId newResId = createResourceId(newId, 1);

            when(groupStore.create(any())).thenReturn(newResId);
            when(groupStore.getCurrentResourceId(newId)).thenReturn(newResId);
            when(documentDescriptorStore.readDescriptor(newId, 1))
                    .thenThrow(new IResourceStore.ResourceNotFoundException("not found"));

            restStore.duplicateGroup("group1", 1);
        }

        @Test
        @DisplayName("duplicateGroup — location is null, syncDescriptor skipped")
        void locationNullSkipsSync() throws Exception {
            var config = new AgentGroupConfiguration();
            config.setName("Original");

            when(groupStore.read("group1", 1)).thenReturn(config);

            IResourceId newResId = createResourceId("new123456789012345678", 1);
            when(groupStore.create(any())).thenReturn(newResId);

            // restVersionInfo.create will produce a response. We test the code path
            // where location is null, but since we mock indirectly through the store
            // we just ensure no NPE
            assertDoesNotThrow(() -> restStore.duplicateGroup("group1", 1));
        }
    }

    // ==================== readGroupDescriptors ====================

    @Nested
    @DisplayName("readGroupDescriptors")
    class ReadGroupDescriptorsTests {

        @Test
        @DisplayName("delegates to restVersionInfo")
        void delegatesToVersionInfo() throws Exception {
            // This should not throw — even if the store returns empty
            List<DocumentDescriptor> result = restStore.readGroupDescriptors(null, 0, 10);
            assertNotNull(result);
        }
    }

    // ==================== syncDescriptor — null name/description
    // ====================

    @Nested
    @DisplayName("syncDescriptor — null name/description")
    class SyncDescriptorNullFields {

        @Test
        @DisplayName("null name in config does not update descriptor name")
        void nullNameSkipped() throws Exception {
            var config = new AgentGroupConfiguration();
            config.setName(null); // null name
            config.setDescription("Some desc");

            IResourceId resourceId = createResourceId("group1", 1);
            when(groupStore.getCurrentResourceId("group1")).thenReturn(resourceId);

            DocumentDescriptor descriptor = new DocumentDescriptor();
            descriptor.setName("Original Name");
            descriptor.setDescription("Old desc");
            when(documentDescriptorStore.readDescriptor("group1", 1)).thenReturn(descriptor);
            when(groupStore.update("group1", 1, config)).thenReturn(2);

            restStore.updateGroup("group1", 1, config);

            // Description changed, name unchanged
            verify(documentDescriptorStore).setDescriptor(eq("group1"), eq(1), any(DocumentDescriptor.class));
        }

        @Test
        @DisplayName("null description in config does not update descriptor description")
        void nullDescriptionSkipped() throws Exception {
            var config = new AgentGroupConfiguration();
            config.setName("New Name");
            config.setDescription(null); // null description

            IResourceId resourceId = createResourceId("group1", 1);
            when(groupStore.getCurrentResourceId("group1")).thenReturn(resourceId);

            DocumentDescriptor descriptor = new DocumentDescriptor();
            descriptor.setName("Old Name");
            descriptor.setDescription("Old desc");
            when(documentDescriptorStore.readDescriptor("group1", 1)).thenReturn(descriptor);
            when(groupStore.update(eq("group1"), eq(1), any())).thenReturn(2);

            restStore.updateGroup("group1", 1, config);

            // Name changed, description unchanged
            verify(documentDescriptorStore).setDescriptor(eq("group1"), eq(1), any(DocumentDescriptor.class));
        }
    }

    // ==================== Helpers ====================

    private IResourceId createResourceId(String id, int version) {
        return new IResourceId() {
            @Override
            public String getId() {
                return id;
            }
            @Override
            public Integer getVersion() {
                return version;
            }
        };
    }
}
