package ai.labs.eddi.engine.mcp;

import ai.labs.eddi.configs.behavior.IRestBehaviorStore;
import ai.labs.eddi.configs.behavior.model.BehaviorConfiguration;
import ai.labs.eddi.configs.botmanagement.IRestBotTriggerStore;
import ai.labs.eddi.configs.bots.IRestBotStore;
import ai.labs.eddi.configs.bots.model.BotConfiguration;
import ai.labs.eddi.configs.documentdescriptor.IRestDocumentDescriptorStore;
import ai.labs.eddi.configs.documentdescriptor.model.DocumentDescriptor;
import ai.labs.eddi.configs.http.IRestHttpCallsStore;
import ai.labs.eddi.configs.http.model.HttpCallsConfiguration;
import ai.labs.eddi.configs.langchain.IRestLangChainStore;
import ai.labs.eddi.configs.output.IRestOutputStore;
import ai.labs.eddi.configs.output.model.OutputConfigurationSet;
import ai.labs.eddi.configs.packages.IRestPackageStore;
import ai.labs.eddi.configs.packages.model.PackageConfiguration;
import ai.labs.eddi.configs.propertysetter.IRestPropertySetterStore;
import ai.labs.eddi.configs.regulardictionary.IRestRegularDictionaryStore;
import ai.labs.eddi.configs.schedule.IScheduleStore;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.IRestBotAdministration;
import ai.labs.eddi.engine.model.BotTriggerConfiguration;
import ai.labs.eddi.engine.model.Deployment.Environment;
import ai.labs.eddi.engine.runtime.client.factory.IRestInterfaceFactory;
import ai.labs.eddi.engine.runtime.internal.ScheduleFireExecutor;
import ai.labs.eddi.engine.runtime.internal.SchedulePollerService;
import ai.labs.eddi.modules.langchain.model.LangChainConfiguration;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for McpAdminTools Phase 8a.2 —
 * update_resource, create_resource, delete_resource, apply_bot_changes, list_bot_resources.
 * Phase 8a.3 — list_bot_triggers, create_bot_trigger, update_bot_trigger, delete_bot_trigger.
 */
class McpAdminToolsCrudTest {

    private static final String BOT_ID = "test-bot-id";
    private static final String RESOURCE_ID = "test-resource-id";
    private static final String PKG_ID = "test-pkg-id";

    private IRestBotAdministration botAdmin;
    private IRestBotStore botStore;
    private IRestPackageStore packageStore;
    private IRestDocumentDescriptorStore descriptorStore;
    private IRestBehaviorStore behaviorStore;
    private IRestLangChainStore langChainStore;
    private IRestHttpCallsStore httpCallsStore;
    private IRestOutputStore outputStore;
    private IRestPropertySetterStore propertySetterStore;
    private IRestRegularDictionaryStore dictionaryStore;
    private IRestBotTriggerStore botTriggerStore;
    private IJsonSerialization jsonSerialization;
    private IScheduleStore scheduleStore;
    private ScheduleFireExecutor scheduleFireExecutor;
    private SchedulePollerService schedulePollerService;
    private McpAdminTools tools;

    @BeforeEach
    void setUp() throws Exception {
        botAdmin = mock(IRestBotAdministration.class);
        botStore = mock(IRestBotStore.class);
        packageStore = mock(IRestPackageStore.class);
        descriptorStore = mock(IRestDocumentDescriptorStore.class);
        behaviorStore = mock(IRestBehaviorStore.class);
        langChainStore = mock(IRestLangChainStore.class);
        httpCallsStore = mock(IRestHttpCallsStore.class);
        outputStore = mock(IRestOutputStore.class);
        propertySetterStore = mock(IRestPropertySetterStore.class);
        dictionaryStore = mock(IRestRegularDictionaryStore.class);
        botTriggerStore = mock(IRestBotTriggerStore.class);
        jsonSerialization = mock(IJsonSerialization.class);

        var restInterfaceFactory = mock(IRestInterfaceFactory.class);
        when(restInterfaceFactory.get(IRestBotStore.class)).thenReturn(botStore);
        when(restInterfaceFactory.get(IRestPackageStore.class)).thenReturn(packageStore);
        when(restInterfaceFactory.get(IRestDocumentDescriptorStore.class)).thenReturn(descriptorStore);
        when(restInterfaceFactory.get(IRestBehaviorStore.class)).thenReturn(behaviorStore);
        when(restInterfaceFactory.get(IRestLangChainStore.class)).thenReturn(langChainStore);
        when(restInterfaceFactory.get(IRestHttpCallsStore.class)).thenReturn(httpCallsStore);
        when(restInterfaceFactory.get(IRestOutputStore.class)).thenReturn(outputStore);
        when(restInterfaceFactory.get(IRestPropertySetterStore.class)).thenReturn(propertySetterStore);
        when(restInterfaceFactory.get(IRestRegularDictionaryStore.class)).thenReturn(dictionaryStore);
        when(restInterfaceFactory.get(IRestBotTriggerStore.class)).thenReturn(botTriggerStore);

        lenient().when(jsonSerialization.serialize(any())).thenReturn("{}");
        scheduleStore = mock(IScheduleStore.class);
        scheduleFireExecutor = mock(ScheduleFireExecutor.class);
        schedulePollerService = mock(SchedulePollerService.class);
        tools = new McpAdminTools(restInterfaceFactory, botAdmin, jsonSerialization,
                scheduleStore, scheduleFireExecutor, schedulePollerService);
    }

    // ==================== update_resource ====================

    @Test
    void updateResource_langchain_success() throws IOException {
        var config = new LangChainConfiguration(List.of());
        when(jsonSerialization.deserialize("{\"tasks\":[]}", LangChainConfiguration.class)).thenReturn(config);
        when(langChainStore.updateLangChain(RESOURCE_ID, 1, config))
                .thenReturn(Response.ok().header("Location",
                        "/langchainstore/langchains/" + RESOURCE_ID + "?version=2").build());
        when(jsonSerialization.serialize(any())).thenReturn("{\"action\":\"updated\",\"newVersion\":2}");

        String result = tools.updateResource("langchain", RESOURCE_ID, 1, "{\"tasks\":[]}");

        assertNotNull(result);
        assertTrue(result.contains("updated"));
        verify(langChainStore).updateLangChain(RESOURCE_ID, 1, config);
    }

    @Test
    void updateResource_behavior_success() throws IOException {
        var config = new BehaviorConfiguration();
        when(jsonSerialization.deserialize("{}", BehaviorConfiguration.class)).thenReturn(config);
        when(behaviorStore.updateBehaviorRuleSet(RESOURCE_ID, 1, config))
                .thenReturn(Response.ok().header("Location",
                        "/behaviorstore/behaviorsets/" + RESOURCE_ID + "?version=2").build());
        when(jsonSerialization.serialize(any())).thenReturn("{\"action\":\"updated\"}");

        String result = tools.updateResource("behavior", RESOURCE_ID, 1, "{}");

        assertNotNull(result);
        verify(behaviorStore).updateBehaviorRuleSet(RESOURCE_ID, 1, config);
    }

    @Test
    void updateResource_missingType_returnsError() {
        String result = tools.updateResource(null, RESOURCE_ID, 1, "{}");
        assertTrue(result.contains("error"));
        assertTrue(result.contains("resourceType is required"));
    }

    @Test
    void updateResource_missingId_returnsError() {
        String result = tools.updateResource("langchain", null, 1, "{}");
        assertTrue(result.contains("error"));
        assertTrue(result.contains("resourceId is required"));
    }

    @Test
    void updateResource_missingConfig_returnsError() {
        String result = tools.updateResource("langchain", RESOURCE_ID, 1, null);
        assertTrue(result.contains("error"));
        assertTrue(result.contains("config is required"));
    }

    @Test
    void updateResource_unknownType_returnsError() throws IOException {
        when(jsonSerialization.deserialize(anyString(), any())).thenReturn(null);

        String result = tools.updateResource("unknown", RESOURCE_ID, 1, "{}");
        assertTrue(result.contains("error"));
        assertTrue(result.contains("Unknown resource type"));
    }

    // ==================== create_resource ====================

    @Test
    void createResource_httpcalls_success() throws IOException {
        var config = new HttpCallsConfiguration();
        when(jsonSerialization.deserialize("{}", HttpCallsConfiguration.class)).thenReturn(config);
        when(httpCallsStore.createHttpCalls(config))
                .thenReturn(Response.created(URI.create(
                        "/httpcallsstore/httpcalls/new-id?version=1")).build());
        when(jsonSerialization.serialize(any())).thenReturn("{\"action\":\"created\",\"resourceId\":\"new-id\"}");

        String result = tools.createResource("httpcalls", "{}");

        assertNotNull(result);
        assertTrue(result.contains("created"));
        verify(httpCallsStore).createHttpCalls(config);
    }

    @Test
    void createResource_output_success() throws IOException {
        var config = new OutputConfigurationSet();
        when(jsonSerialization.deserialize("{}", OutputConfigurationSet.class)).thenReturn(config);
        when(outputStore.createOutputSet(config))
                .thenReturn(Response.created(URI.create(
                        "/outputstore/outputsets/new-id?version=1")).build());
        when(jsonSerialization.serialize(any())).thenReturn("{\"action\":\"created\"}");

        String result = tools.createResource("output", "{}");

        assertNotNull(result);
        verify(outputStore).createOutputSet(config);
    }

    @Test
    void createResource_missingType_returnsError() {
        String result = tools.createResource(null, "{}");
        assertTrue(result.contains("error"));
        assertTrue(result.contains("resourceType is required"));
    }

    @Test
    void createResource_missingConfig_returnsError() {
        String result = tools.createResource("behavior", null);
        assertTrue(result.contains("error"));
        assertTrue(result.contains("config is required"));
    }

    // ==================== delete_resource ====================

    @Test
    void deleteResource_httpcalls_success() throws IOException {
        when(httpCallsStore.deleteHttpCalls(RESOURCE_ID, 1, false))
                .thenReturn(Response.ok().build());
        when(jsonSerialization.serialize(any())).thenReturn("{\"action\":\"deleted\"}");

        String result = tools.deleteResource("httpcalls", RESOURCE_ID, 1, false);

        assertNotNull(result);
        assertTrue(result.contains("deleted"));
        verify(httpCallsStore).deleteHttpCalls(RESOURCE_ID, 1, false);
    }

    @Test
    void deleteResource_permanent_success() throws IOException {
        when(behaviorStore.deleteBehaviorRuleSet(RESOURCE_ID, 2, true))
                .thenReturn(Response.ok().build());
        when(jsonSerialization.serialize(any())).thenReturn("{\"action\":\"deleted\",\"permanent\":true}");

        String result = tools.deleteResource("behavior", RESOURCE_ID, 2, true);

        assertNotNull(result);
        verify(behaviorStore).deleteBehaviorRuleSet(RESOURCE_ID, 2, true);
    }

    @Test
    void deleteResource_missingType_returnsError() {
        String result = tools.deleteResource(null, RESOURCE_ID, 1, false);
        assertTrue(result.contains("error"));
        assertTrue(result.contains("resourceType is required"));
    }

    @Test
    void deleteResource_missingId_returnsError() {
        String result = tools.deleteResource("langchain", null, 1, false);
        assertTrue(result.contains("error"));
        assertTrue(result.contains("resourceId is required"));
    }

    // ==================== apply_bot_changes ====================

    @Test
    void applyBotChanges_singlePackage_success() throws IOException {
        // Set up bot with 1 package
        var botConfig = new BotConfiguration();
        botConfig.setPackages(List.of(
                URI.create("eddi://ai.labs.package/packagestore/packages/" + PKG_ID + "?version=1")));
        when(botStore.readBot(BOT_ID, 1)).thenReturn(botConfig);

        // Set up package with one extension containing the old URI
        var ext = new PackageConfiguration.PackageExtension();
        ext.setType(URI.create("eddi://ai.labs.langchain"));
        var configMap = new HashMap<String, Object>();
        configMap.put("uri", "eddi://ai.labs.langchain/langchainstore/langchains/lc1?version=1");
        ext.setConfig(configMap);
        var pkgConfig = new PackageConfiguration();
        pkgConfig.setPackageExtensions(new ArrayList<>(List.of(ext)));
        when(packageStore.readPackage(PKG_ID, 1)).thenReturn(pkgConfig);

        // Mock updates
        when(packageStore.updatePackage(eq(PKG_ID), eq(1), any()))
                .thenReturn(Response.ok().header("Location",
                        "/packagestore/packages/" + PKG_ID + "?version=2").build());
        when(botStore.updateBot(eq(BOT_ID), eq(1), any()))
                .thenReturn(Response.ok().header("Location",
                        "/botstore/bots/" + BOT_ID + "?version=2").build());

        // Parse mappings JSON
        String mappingsJson = "[{\"oldUri\":\"eddi://ai.labs.langchain/langchainstore/langchains/lc1?version=1\"," +
                "\"newUri\":\"eddi://ai.labs.langchain/langchainstore/langchains/lc1?version=2\"}]";
        List<Map<String, String>> mappings = List.of(Map.of(
                "oldUri", "eddi://ai.labs.langchain/langchainstore/langchains/lc1?version=1",
                "newUri", "eddi://ai.labs.langchain/langchainstore/langchains/lc1?version=2"));
        when(jsonSerialization.deserialize(mappingsJson, List.class)).thenReturn(mappings);
        when(jsonSerialization.serialize(any())).thenReturn("{\"action\":\"cascaded\",\"updatedPackages\":1}");

        String result = tools.applyBotChanges(BOT_ID, 1, mappingsJson, false, null);

        assertNotNull(result);
        assertTrue(result.contains("cascaded"));
        verify(packageStore).updatePackage(eq(PKG_ID), eq(1), any());
        verify(botStore).updateBot(eq(BOT_ID), eq(1), any());
    }

    @Test
    void applyBotChanges_noMatchingUris_noUpdates() throws IOException {
        var botConfig = new BotConfiguration();
        botConfig.setPackages(List.of(
                URI.create("eddi://ai.labs.package/packagestore/packages/" + PKG_ID + "?version=1")));
        when(botStore.readBot(BOT_ID, 1)).thenReturn(botConfig);

        var ext = new PackageConfiguration.PackageExtension();
        ext.setType(URI.create("eddi://ai.labs.langchain"));
        var configMap = new HashMap<String, Object>();
        configMap.put("uri", "eddi://ai.labs.langchain/langchainstore/langchains/lc1?version=1");
        ext.setConfig(configMap);
        var pkgConfig = new PackageConfiguration();
        pkgConfig.setPackageExtensions(new ArrayList<>(List.of(ext)));
        when(packageStore.readPackage(PKG_ID, 1)).thenReturn(pkgConfig);

        // Mappings don't match any existing URIs
        String mappingsJson = "[{\"oldUri\":\"eddi://different/resource?version=1\",\"newUri\":\"eddi://different/resource?version=2\"}]";
        List<Map<String, String>> mappings = List.of(Map.of(
                "oldUri", "eddi://different/resource?version=1",
                "newUri", "eddi://different/resource?version=2"));
        when(jsonSerialization.deserialize(mappingsJson, List.class)).thenReturn(mappings);
        when(jsonSerialization.serialize(any())).thenReturn("{\"action\":\"cascaded\",\"updatedPackages\":0}");

        tools.applyBotChanges(BOT_ID, 1, mappingsJson, false, null);

        // No package or bot updates should occur
        verify(packageStore, never()).updatePackage(any(), anyInt(), any());
        verify(botStore, never()).updateBot(any(), anyInt(), any());
    }

    @Test
    void applyBotChanges_withRedeploy_success() throws IOException {
        var botConfig = new BotConfiguration();
        botConfig.setPackages(List.of(
                URI.create("eddi://ai.labs.package/packagestore/packages/" + PKG_ID + "?version=1")));
        when(botStore.readBot(BOT_ID, 1)).thenReturn(botConfig);

        var ext = new PackageConfiguration.PackageExtension();
        ext.setType(URI.create("eddi://ai.labs.behavior"));
        var configMap = new HashMap<String, Object>();
        configMap.put("uri", "eddi://ai.labs.behavior/behaviorstore/behaviorsets/b1?version=1");
        ext.setConfig(configMap);
        var pkgConfig = new PackageConfiguration();
        pkgConfig.setPackageExtensions(new ArrayList<>(List.of(ext)));
        when(packageStore.readPackage(PKG_ID, 1)).thenReturn(pkgConfig);

        when(packageStore.updatePackage(eq(PKG_ID), eq(1), any()))
                .thenReturn(Response.ok().header("Location",
                        "/packagestore/packages/" + PKG_ID + "?version=2").build());
        when(botStore.updateBot(eq(BOT_ID), eq(1), any()))
                .thenReturn(Response.ok().header("Location",
                        "/botstore/bots/" + BOT_ID + "?version=2").build());
        when(botAdmin.deployBot(Environment.unrestricted, BOT_ID, 2, true, true))
                .thenReturn(Response.ok().build());

        String mappingsJson = "[{\"oldUri\":\"eddi://ai.labs.behavior/behaviorstore/behaviorsets/b1?version=1\"," +
                "\"newUri\":\"eddi://ai.labs.behavior/behaviorstore/behaviorsets/b1?version=2\"}]";
        List<Map<String, String>> mappings = List.of(Map.of(
                "oldUri", "eddi://ai.labs.behavior/behaviorstore/behaviorsets/b1?version=1",
                "newUri", "eddi://ai.labs.behavior/behaviorstore/behaviorsets/b1?version=2"));
        when(jsonSerialization.deserialize(mappingsJson, List.class)).thenReturn(mappings);
        when(jsonSerialization.serialize(any())).thenReturn("{\"action\":\"cascaded\",\"redeployed\":true}");

        String result = tools.applyBotChanges(BOT_ID, 1, mappingsJson, true, "unrestricted");

        assertTrue(result.contains("cascaded"));
        verify(botAdmin).deployBot(Environment.unrestricted, BOT_ID, 2, true, true);
    }

    @Test
    void applyBotChanges_missingBotId_returnsError() {
        String result = tools.applyBotChanges(null, 1, "[{}]", false, null);
        assertTrue(result.contains("error"));
        assertTrue(result.contains("botId is required"));
    }

    @Test
    void applyBotChanges_emptyMappings_noChanges() throws IOException {
        when(jsonSerialization.deserialize("[]", List.class)).thenReturn(List.of());
        when(jsonSerialization.serialize(any())).thenReturn("{\"action\":\"no_changes\"}");

        String result = tools.applyBotChanges(BOT_ID, 1, "[]", false, null);

        assertNotNull(result);
        verify(botStore, never()).readBot(any(), anyInt());
    }

    @Test
    void applyBotChanges_botNotFound_returnsError() throws IOException {
        when(botStore.readBot(BOT_ID, 1)).thenReturn(null);
        List<Map<String, String>> mappings = List.of(Map.of("oldUri", "a", "newUri", "b"));
        when(jsonSerialization.deserialize(anyString(), eq(List.class))).thenReturn(mappings);

        String result = tools.applyBotChanges(BOT_ID, 1, "[{\"oldUri\":\"a\",\"newUri\":\"b\"}]", false, null);

        assertTrue(result.contains("error"));
        assertTrue(result.contains("Bot not found"));
    }

    // ==================== list_bot_resources ====================

    @Test
    void listBotResources_success() throws IOException {
        // Set up bot with 1 package
        var botConfig = new BotConfiguration();
        botConfig.setPackages(List.of(
                URI.create("eddi://ai.labs.package/packagestore/packages/" + PKG_ID + "?version=1")));
        when(botStore.readBot(BOT_ID, 1)).thenReturn(botConfig);

        // Bot descriptor
        var descriptor = new DocumentDescriptor();
        descriptor.setName("Test Bot");
        when(descriptorStore.readDescriptor(BOT_ID, 1)).thenReturn(descriptor);

        // Package with 2 extensions
        var ext1 = new PackageConfiguration.PackageExtension();
        ext1.setType(URI.create("eddi://ai.labs.langchain"));
        ext1.setConfig(Map.of("uri", "eddi://ai.labs.langchain/langchainstore/langchains/lc1?version=1"));
        var ext2 = new PackageConfiguration.PackageExtension();
        ext2.setType(URI.create("eddi://ai.labs.behavior"));
        ext2.setConfig(Map.of("uri", "eddi://ai.labs.behavior/behaviorstore/behaviorsets/b1?version=1"));
        var pkgConfig = new PackageConfiguration();
        pkgConfig.setPackageExtensions(List.of(ext1, ext2));
        when(packageStore.readPackage(PKG_ID, 1)).thenReturn(pkgConfig);

        when(jsonSerialization.serialize(any())).thenReturn(
                "{\"botId\":\"test-bot-id\",\"botName\":\"Test Bot\",\"packageCount\":1}");

        String result = tools.listBotResources(BOT_ID, 1);

        assertNotNull(result);
        assertTrue(result.contains("Test Bot"));
        verify(botStore).readBot(BOT_ID, 1);
        verify(packageStore).readPackage(PKG_ID, 1);
    }

    @Test
    void listBotResources_botNotFound_returnsError() {
        when(botStore.readBot(BOT_ID, 1)).thenReturn(null);

        String result = tools.listBotResources(BOT_ID, 1);

        assertTrue(result.contains("error"));
        assertTrue(result.contains("Bot not found"));
    }

    @Test
    void listBotResources_missingBotId_returnsError() {
        String result = tools.listBotResources(null, 1);
        assertTrue(result.contains("error"));
        assertTrue(result.contains("botId is required"));
    }

    @Test
    void listBotResources_packageReadFailure_includesError() throws IOException {
        var botConfig = new BotConfiguration();
        botConfig.setPackages(List.of(
                URI.create("eddi://ai.labs.package/packagestore/packages/" + PKG_ID + "?version=1")));
        when(botStore.readBot(BOT_ID, 1)).thenReturn(botConfig);

        when(packageStore.readPackage(PKG_ID, 1))
                .thenThrow(new RuntimeException("Package corrupted"));
        when(jsonSerialization.serialize(any())).thenReturn("{\"packages\":[{\"error\":\"Failed to read package\"}]}");

        String result = tools.listBotResources(BOT_ID, 1);

        // Should still succeed (graceful degradation), but include error info
        assertNotNull(result);
        assertTrue(result.contains("error") || result.contains("packages"));
    }

    // ==================== list_bot_triggers ====================

    @Test
    void listBotTriggers_success() throws IOException {
        var trigger = new BotTriggerConfiguration();
        trigger.setIntent("support");
        when(botTriggerStore.readAllBotTriggers()).thenReturn(List.of(trigger));
        when(jsonSerialization.serialize(any())).thenReturn("{\"count\":1}");

        String result = tools.listBotTriggers();

        assertNotNull(result);
        verify(botTriggerStore).readAllBotTriggers();
    }

    @Test
    void listBotTriggers_error_returnsError() {
        when(botTriggerStore.readAllBotTriggers()).thenThrow(new RuntimeException("db error"));

        String result = tools.listBotTriggers();

        assertTrue(result.contains("error"));
    }

    // ==================== create_bot_trigger ====================

    @Test
    void createBotTrigger_success() throws IOException {
        var config = new BotTriggerConfiguration();
        config.setIntent("support");
        when(jsonSerialization.deserialize(anyString(), eq(BotTriggerConfiguration.class))).thenReturn(config);
        when(botTriggerStore.createBotTrigger(any())).thenReturn(Response.ok().build());
        when(jsonSerialization.serialize(any())).thenReturn("{\"action\":\"created\"}");

        String result = tools.createBotTrigger("{\"intent\":\"support\",\"botDeployments\":[]}");

        assertNotNull(result);
        assertTrue(result.contains("created"));
        verify(botTriggerStore).createBotTrigger(any());
    }

    @Test
    void createBotTrigger_missingConfig_returnsError() {
        String result = tools.createBotTrigger(null);
        assertTrue(result.contains("error"));
        assertTrue(result.contains("config is required"));
    }

    // ==================== update_bot_trigger ====================

    @Test
    void updateBotTrigger_success() throws IOException {
        var config = new BotTriggerConfiguration();
        when(jsonSerialization.deserialize(anyString(), eq(BotTriggerConfiguration.class))).thenReturn(config);
        when(botTriggerStore.updateBotTrigger(eq("support"), any())).thenReturn(Response.ok().build());
        when(jsonSerialization.serialize(any())).thenReturn("{\"action\":\"updated\"}");

        String result = tools.updateBotTrigger("support", "{\"intent\":\"support\"}");

        assertNotNull(result);
        assertTrue(result.contains("updated"));
    }

    @Test
    void updateBotTrigger_missingIntent_returnsError() {
        String result = tools.updateBotTrigger(null, "{}");
        assertTrue(result.contains("error"));
        assertTrue(result.contains("intent is required"));
    }

    // ==================== delete_bot_trigger ====================

    @Test
    void deleteBotTrigger_success() throws IOException {
        when(botTriggerStore.deleteBotTrigger("support")).thenReturn(Response.ok().build());
        when(jsonSerialization.serialize(any())).thenReturn("{\"action\":\"deleted\"}");

        String result = tools.deleteBotTrigger("support");

        assertNotNull(result);
        assertTrue(result.contains("deleted"));
        verify(botTriggerStore).deleteBotTrigger("support");
    }

    @Test
    void deleteBotTrigger_missingIntent_returnsError() {
        String result = tools.deleteBotTrigger(null);
        assertTrue(result.contains("error"));
        assertTrue(result.contains("intent is required"));
    }
}
