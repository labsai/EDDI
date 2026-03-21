package ai.labs.eddi.configs.packages.rest;

import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.configs.packages.IPackageStore;
import ai.labs.eddi.configs.packages.model.PackageConfiguration;
import ai.labs.eddi.configs.packages.model.PackageConfiguration.PackageExtension;
import ai.labs.eddi.configs.schema.IJsonSchemaCreator;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.runtime.client.configuration.ResourceClientLibrary;
import ai.labs.eddi.engine.runtime.service.ServiceException;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

import java.net.URI;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.openMocks;

class RestPackageStoreTest {

    @Mock
    private IPackageStore packageStore;
    @Mock
    private ResourceClientLibrary resourceClientLibrary;
    @Mock
    private IDocumentDescriptorStore documentDescriptorStore;
    @Mock
    private IJsonSchemaCreator jsonSchemaCreator;

    private RestPackageStore restPackageStore;

    @BeforeEach
    void setUp() {
        openMocks(this);
        restPackageStore = new RestPackageStore(
                packageStore, resourceClientLibrary, documentDescriptorStore, jsonSchemaCreator);
    }

    /** Helper — by default, resources are only referenced by 1 package (safe to delete) */
    private void mockSingleReference() throws Exception {
        when(packageStore.getPackageDescriptorsContainingResource(anyString(), eq(false)))
                .thenReturn(List.of(new DocumentDescriptor()));
    }

    @Nested
    @DisplayName("deletePackage")
    class DeletePackageTests {

        @Test
        @DisplayName("should delete package without cascade when cascade=false")
        void deletePackage_noCascade() throws Exception {
            restPackageStore.deletePackage("pkg1", 1, false, false);

            verify(resourceClientLibrary, never()).deleteResource(any(), anyBoolean());
        }

        @Test
        @DisplayName("should cascade-delete extension resources when cascade=true")
        void deletePackage_cascade_deletesExtensions() throws Exception {
            mockSingleReference();

            PackageConfiguration config = new PackageConfiguration();

            PackageExtension behaviorExt = new PackageExtension();
            behaviorExt.setType(URI.create("eddi://ai.labs.behavior"));
            behaviorExt.setConfig(new HashMap<>(Map.of(
                    "uri", "eddi://ai.labs.behavior/behaviorstore/behaviorsets/beh1?version=1"
            )));
            config.getPackageExtensions().add(behaviorExt);

            PackageExtension httpExt = new PackageExtension();
            httpExt.setType(URI.create("eddi://ai.labs.httpcalls"));
            httpExt.setConfig(new HashMap<>(Map.of(
                    "uri", "eddi://ai.labs.httpcalls/httpcallsstore/httpcalls/http1?version=3"
            )));
            config.getPackageExtensions().add(httpExt);

            PackageExtension outputExt = new PackageExtension();
            outputExt.setType(URI.create("eddi://ai.labs.output"));
            outputExt.setConfig(new HashMap<>(Map.of(
                    "uri", "eddi://ai.labs.output/outputstore/outputsets/out1?version=1"
            )));
            config.getPackageExtensions().add(outputExt);

            when(packageStore.read("pkg1", 1)).thenReturn(config);
            when(resourceClientLibrary.deleteResource(any(), anyBoolean()))
                    .thenReturn(Response.ok().build());

            restPackageStore.deletePackage("pkg1", 1, true, true);

            verify(resourceClientLibrary).deleteResource(
                    URI.create("eddi://ai.labs.behavior/behaviorstore/behaviorsets/beh1?version=1"), true);
            verify(resourceClientLibrary).deleteResource(
                    URI.create("eddi://ai.labs.httpcalls/httpcallsstore/httpcalls/http1?version=3"), true);
            verify(resourceClientLibrary).deleteResource(
                    URI.create("eddi://ai.labs.output/outputstore/outputsets/out1?version=1"), true);
        }

        @Test
        @DisplayName("should cascade-delete parser dictionaries")
        void deletePackage_cascade_deletesParserDictionaries() throws Exception {
            mockSingleReference();

            PackageConfiguration config = new PackageConfiguration();

            PackageExtension parserExt = new PackageExtension();
            parserExt.setType(URI.create("eddi://ai.labs.parser"));

            Map<String, Object> dictEntry = new HashMap<>();
            dictEntry.put("type", "eddi://ai.labs.parser.dictionaries.regular");
            dictEntry.put("config", new HashMap<>(Map.of(
                    "uri", "eddi://ai.labs.regulardictionary/regulardictionarystore/regulardictionaries/dict1?version=1"
            )));

            List<Map<String, Object>> dictionaries = new ArrayList<>();
            dictionaries.add(dictEntry);
            parserExt.getExtensions().put("dictionaries", dictionaries);

            config.getPackageExtensions().add(parserExt);

            when(packageStore.read("pkg1", 1)).thenReturn(config);
            when(resourceClientLibrary.deleteResource(any(), anyBoolean()))
                    .thenReturn(Response.ok().build());

            restPackageStore.deletePackage("pkg1", 1, true, true);

            verify(resourceClientLibrary).deleteResource(
                    URI.create("eddi://ai.labs.regulardictionary/regulardictionarystore/regulardictionaries/dict1?version=1"),
                    true);
        }

        @Test
        @DisplayName("should skip resources shared with other packages")
        void deletePackage_cascade_skipsSharedResources() throws Exception {
            PackageConfiguration config = new PackageConfiguration();

            // Behavior extension — shared with 2 packages → should be SKIPPED
            PackageExtension sharedExt = new PackageExtension();
            sharedExt.setType(URI.create("eddi://ai.labs.behavior"));
            sharedExt.setConfig(new HashMap<>(Map.of(
                    "uri", "eddi://ai.labs.behavior/behaviorstore/behaviorsets/shared1?version=1"
            )));
            config.getPackageExtensions().add(sharedExt);

            // Output extension — only in this package → should be deleted
            PackageExtension uniqueExt = new PackageExtension();
            uniqueExt.setType(URI.create("eddi://ai.labs.output"));
            uniqueExt.setConfig(new HashMap<>(Map.of(
                    "uri", "eddi://ai.labs.output/outputstore/outputsets/unique1?version=1"
            )));
            config.getPackageExtensions().add(uniqueExt);

            when(packageStore.read("pkg1", 1)).thenReturn(config);

            // Shared resource referenced by 2 packages
            String sharedUri = "eddi://ai.labs.behavior/behaviorstore/behaviorsets/shared1?version=1";
            when(packageStore.getPackageDescriptorsContainingResource(eq(sharedUri), eq(false)))
                    .thenReturn(List.of(new DocumentDescriptor(), new DocumentDescriptor()));

            // Unique resource referenced by only 1 package
            String uniqueUri = "eddi://ai.labs.output/outputstore/outputsets/unique1?version=1";
            when(packageStore.getPackageDescriptorsContainingResource(eq(uniqueUri), eq(false)))
                    .thenReturn(List.of(new DocumentDescriptor()));

            when(resourceClientLibrary.deleteResource(any(), anyBoolean()))
                    .thenReturn(Response.ok().build());

            restPackageStore.deletePackage("pkg1", 1, true, true);

            // Only the unique resource should be deleted
            verify(resourceClientLibrary, never()).deleteResource(
                    eq(URI.create(sharedUri)), anyBoolean());
            verify(resourceClientLibrary).deleteResource(
                    URI.create(uniqueUri), true);
        }

        @Test
        @DisplayName("should continue when individual resource delete fails")
        void deletePackage_cascade_partialFailure() throws Exception {
            mockSingleReference();

            PackageConfiguration config = new PackageConfiguration();

            PackageExtension ext1 = new PackageExtension();
            ext1.setType(URI.create("eddi://ai.labs.behavior"));
            ext1.setConfig(new HashMap<>(Map.of(
                    "uri", "eddi://ai.labs.behavior/behaviorstore/behaviorsets/beh1?version=1"
            )));
            config.getPackageExtensions().add(ext1);

            PackageExtension ext2 = new PackageExtension();
            ext2.setType(URI.create("eddi://ai.labs.output"));
            ext2.setConfig(new HashMap<>(Map.of(
                    "uri", "eddi://ai.labs.output/outputstore/outputsets/out1?version=1"
            )));
            config.getPackageExtensions().add(ext2);

            when(packageStore.read("pkg1", 1)).thenReturn(config);
            when(resourceClientLibrary.deleteResource(
                    URI.create("eddi://ai.labs.behavior/behaviorstore/behaviorsets/beh1?version=1"), true))
                    .thenThrow(new ServiceException("DB error"));
            when(resourceClientLibrary.deleteResource(
                    URI.create("eddi://ai.labs.output/outputstore/outputsets/out1?version=1"), true))
                    .thenReturn(Response.ok().build());

            assertDoesNotThrow(() -> restPackageStore.deletePackage("pkg1", 1, true, true));

            verify(resourceClientLibrary, times(2)).deleteResource(any(), anyBoolean());
        }

        @Test
        @DisplayName("should handle package not found for cascade gracefully")
        void deletePackage_cascade_packageNotFound() throws Exception {
            when(packageStore.read("pkg1", 1))
                    .thenThrow(new IResourceStore.ResourceNotFoundException("not found"));

            assertDoesNotThrow(() -> restPackageStore.deletePackage("pkg1", 1, true, true));

            verify(resourceClientLibrary, never()).deleteResource(any(), anyBoolean());
        }

        @Test
        @DisplayName("should skip extensions without config.uri")
        void deletePackage_cascade_noUri() throws Exception {
            PackageConfiguration config = new PackageConfiguration();

            PackageExtension ext = new PackageExtension();
            ext.setType(URI.create("eddi://ai.labs.parser"));
            config.getPackageExtensions().add(ext);

            when(packageStore.read("pkg1", 1)).thenReturn(config);

            assertDoesNotThrow(() -> restPackageStore.deletePackage("pkg1", 1, true, true));

            verify(resourceClientLibrary, never()).deleteResource(any(), anyBoolean());
        }
    }
}
