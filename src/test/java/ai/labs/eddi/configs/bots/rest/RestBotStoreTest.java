package ai.labs.eddi.configs.bots.rest;

import ai.labs.eddi.configs.bots.IBotStore;
import ai.labs.eddi.configs.bots.model.BotConfiguration;
import ai.labs.eddi.configs.documentdescriptor.IDocumentDescriptorStore;
import ai.labs.eddi.configs.documentdescriptor.model.DocumentDescriptor;
import ai.labs.eddi.configs.packages.IRestPackageStore;
import ai.labs.eddi.configs.schedule.IScheduleStore;
import ai.labs.eddi.configs.schema.IJsonSchemaCreator;
import ai.labs.eddi.datastore.IResourceStore;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.openMocks;

class RestBotStoreTest {

    // Realistic IDs (extractResourceId requires 18+ hex chars)
    private static final String BOT_ID = "aabbccddee1122334455";
    private static final String PKG1_ID = "ff00112233445566aa77";
    private static final String PKG2_ID = "bb99887766554433cc22";

    @Mock
    private IBotStore botStore;
    @Mock
    private IRestPackageStore restPackageStore;
    @Mock
    private IDocumentDescriptorStore documentDescriptorStore;
    @Mock
    private IJsonSchemaCreator jsonSchemaCreator;
    @Mock
    private IScheduleStore scheduleStore;

    private RestBotStore restBotStore;

    @BeforeEach
    void setUp() {
        openMocks(this);
        restBotStore = new RestBotStore(
                botStore, restPackageStore, documentDescriptorStore, jsonSchemaCreator, scheduleStore);
    }

    /** Helper to create a dummy DocumentDescriptor for reference-count mocking */
    private DocumentDescriptor dummyDescriptor() {
        return new DocumentDescriptor();
    }

    @Nested
    @DisplayName("deleteBot")
    class DeleteBotTests {

        @Test
        @DisplayName("should delete bot without cascade when cascade=false")
        void deleteBot_noCascade() throws Exception {
            restBotStore.deleteBot(BOT_ID, 1, false, false);

            verify(restPackageStore, never()).deletePackage(anyString(), anyInt(), anyBoolean(), anyBoolean());
            verify(botStore).delete(eq(BOT_ID), eq(1));
        }

        @Test
        @DisplayName("should cascade-delete packages when cascade=true and packages are not shared")
        void deleteBot_cascade() throws Exception {
            BotConfiguration config = new BotConfiguration();
            config.setPackages(new ArrayList<>(List.of(
                    URI.create("eddi://ai.labs.package/packagestore/packages/" + PKG1_ID + "?version=2"),
                    URI.create("eddi://ai.labs.package/packagestore/packages/" + PKG2_ID + "?version=1")
            )));
            when(botStore.read(BOT_ID, 1)).thenReturn(config);
            // Each package is only referenced by this one bot
            when(botStore.getBotDescriptorsContainingPackage(PKG1_ID, 2, false))
                    .thenReturn(List.of(dummyDescriptor()));
            when(botStore.getBotDescriptorsContainingPackage(PKG2_ID, 1, false))
                    .thenReturn(List.of(dummyDescriptor()));
            when(restPackageStore.deletePackage(anyString(), anyInt(), anyBoolean(), anyBoolean()))
                    .thenReturn(Response.ok().build());

            restBotStore.deleteBot(BOT_ID, 1, true, true);

            verify(restPackageStore).deletePackage(PKG1_ID, 2, true, true);
            verify(restPackageStore).deletePackage(PKG2_ID, 1, true, true);
            verify(botStore).deleteAllPermanently(BOT_ID);
        }

        @Test
        @DisplayName("should skip cascade-delete of packages shared with other bots")
        void deleteBot_cascade_skipsSharedPackages() throws Exception {
            BotConfiguration config = new BotConfiguration();
            config.setPackages(new ArrayList<>(List.of(
                    URI.create("eddi://ai.labs.package/packagestore/packages/" + PKG1_ID + "?version=2"),
                    URI.create("eddi://ai.labs.package/packagestore/packages/" + PKG2_ID + "?version=1")
            )));
            when(botStore.read(BOT_ID, 1)).thenReturn(config);
            // PKG1 is shared with 2 bots — should be SKIPPED
            when(botStore.getBotDescriptorsContainingPackage(PKG1_ID, 2, false))
                    .thenReturn(List.of(dummyDescriptor(), dummyDescriptor()));
            // PKG2 is only in this bot — should be deleted
            when(botStore.getBotDescriptorsContainingPackage(PKG2_ID, 1, false))
                    .thenReturn(List.of(dummyDescriptor()));
            when(restPackageStore.deletePackage(anyString(), anyInt(), anyBoolean(), anyBoolean()))
                    .thenReturn(Response.ok().build());

            restBotStore.deleteBot(BOT_ID, 1, true, true);

            // Only PKG2 should be deleted — PKG1 is shared
            verify(restPackageStore, never()).deletePackage(eq(PKG1_ID), anyInt(), anyBoolean(), anyBoolean());
            verify(restPackageStore).deletePackage(PKG2_ID, 1, true, true);
            verify(botStore).deleteAllPermanently(BOT_ID);
        }

        @Test
        @DisplayName("should continue deleting bot even when package cascade fails")
        void deleteBot_cascade_partialFailure() throws Exception {
            BotConfiguration config = new BotConfiguration();
            config.setPackages(new ArrayList<>(List.of(
                    URI.create("eddi://ai.labs.package/packagestore/packages/" + PKG1_ID + "?version=1"),
                    URI.create("eddi://ai.labs.package/packagestore/packages/" + PKG2_ID + "?version=1")
            )));
            when(botStore.read(BOT_ID, 1)).thenReturn(config);
            // Both packages only referenced by this bot
            when(botStore.getBotDescriptorsContainingPackage(anyString(), anyInt(), eq(false)))
                    .thenReturn(List.of(dummyDescriptor()));
            when(restPackageStore.deletePackage(PKG1_ID, 1, true, true))
                    .thenThrow(new RuntimeException("Package in use"));
            when(restPackageStore.deletePackage(PKG2_ID, 1, true, true))
                    .thenReturn(Response.ok().build());

            assertDoesNotThrow(() -> restBotStore.deleteBot(BOT_ID, 1, true, true));

            verify(restPackageStore).deletePackage(PKG1_ID, 1, true, true);
            verify(restPackageStore).deletePackage(PKG2_ID, 1, true, true);
            verify(botStore).deleteAllPermanently(BOT_ID);
        }

        @Test
        @DisplayName("should still delete bot when bot config not found for cascade")
        void deleteBot_cascade_botNotFound() throws Exception {
            when(botStore.read(BOT_ID, 1))
                    .thenThrow(new IResourceStore.ResourceNotFoundException("not found"));

            assertDoesNotThrow(() -> restBotStore.deleteBot(BOT_ID, 1, true, true));

            verify(restPackageStore, never()).deletePackage(anyString(), anyInt(), anyBoolean(), anyBoolean());
            verify(botStore).deleteAllPermanently(BOT_ID);
        }

        @Test
        @DisplayName("should handle empty packages list in cascade")
        void deleteBot_cascade_emptyPackages() throws Exception {
            BotConfiguration config = new BotConfiguration();
            config.setPackages(new ArrayList<>());
            when(botStore.read(BOT_ID, 1)).thenReturn(config);

            assertDoesNotThrow(() -> restBotStore.deleteBot(BOT_ID, 1, true, true));

            verify(restPackageStore, never()).deletePackage(anyString(), anyInt(), anyBoolean(), anyBoolean());
            verify(botStore).deleteAllPermanently(BOT_ID);
        }
    }
}
