/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mcp;

import ai.labs.eddi.datastore.serialization.JsonSerialization;
import ai.labs.eddi.engine.pricing.ModelPriceCatalogService;
import ai.labs.eddi.engine.pricing.PriceCatalogSnapshot;
import ai.labs.eddi.engine.pricing.model.ModelPriceCatalog;
import ai.labs.eddi.engine.pricing.model.ModelPriceEntry;
import ai.labs.eddi.engine.pricing.model.UnpricedPolicy;
import ai.labs.eddi.engine.spendcontrol.ISpendControlChangeStore;
import ai.labs.eddi.engine.spendcontrol.SpendControlAudit;
import ai.labs.eddi.engine.spendcontrol.SpendControlChangeService;
import ai.labs.eddi.engine.spendcontrol.model.SpendControlChange;
import ai.labs.eddi.engine.tenancy.InMemoryTenantQuotaStore;
import ai.labs.eddi.engine.tenancy.TenantQuotaService;
import ai.labs.eddi.engine.tenancy.model.TenantQuota;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.quarkiverse.mcp.server.Tool;
import io.quarkus.security.ForbiddenException;
import io.quarkus.security.identity.SecurityIdentity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.security.Principal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The spend-control tools run the real {@link SpendControlChangeService} over a
 * mocked catalog service and an in-memory quota store, so what a tool answers
 * is what the service decided.
 */
@DisplayName("McpSpendControlTools")
class McpSpendControlToolsTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private ModelPriceCatalogService catalogService;
    private ISpendControlChangeStore store;
    private InMemoryTenantQuotaStore quotaStore;
    private TenantQuotaService quotaService;
    private final List<SpendControlChange> created = new ArrayList<>();

    @BeforeEach
    void setUp() {
        catalogService = mock(ModelPriceCatalogService.class);
        PriceCatalogSnapshot snapshot = mock(PriceCatalogSnapshot.class);
        when(snapshot.effectivePolicy()).thenReturn(UnpricedPolicy.ALLOW);
        when(catalogService.snapshot()).thenReturn(snapshot);
        when(catalogService.knownProviders()).thenReturn(Set.of("openai"));
        when(catalogService.read()).thenReturn(new ModelPriceCatalog(2, UnpricedPolicy.ALLOW, null, null,
                List.of(new ModelPriceEntry("openai", "gpt-4o", 2.5, 10.0, null, null, null, null, "https://openai.com/api/pricing", null))));
        when(catalogService.replace(anyLong(), any(), anyString())).thenAnswer(inv -> ((ModelPriceCatalog) inv.getArgument(1)).stamped(3, "now",
                inv.getArgument(2)));
        store = mock(ISpendControlChangeStore.class);
        when(store.list(any(), anyInt())).thenAnswer(inv -> List.copyOf(created));
        doAnswer(inv -> created.add(inv.getArgument(0))).when(store).create(any());
        when(store.read(anyString())).thenReturn(Optional.empty());
        quotaStore = new InMemoryTenantQuotaStore(new TenantQuota("default", 100, -1, -1, 50.0, true));
        quotaService = new TenantQuotaService(quotaStore, new SimpleMeterRegistry(), "default");
    }

    private McpSpendControlTools tools(Set<String> roles) {
        SecurityIdentity identity = mock(SecurityIdentity.class);
        when(identity.isAnonymous()).thenReturn(false);
        when(identity.hasRole(anyString())).thenAnswer(inv -> roles.contains(inv.getArgument(0, String.class)));
        when(identity.getRoles()).thenReturn(roles);
        Principal principal = mock(Principal.class);
        when(principal.getName()).thenReturn("root");
        when(identity.getPrincipal()).thenReturn(principal);
        var service = new SpendControlChangeService(store, catalogService, quotaService, mock(SpendControlAudit.class), new SimpleMeterRegistry(),
                true);
        return new McpSpendControlTools(service, quotaService, new JsonSerialization(mapper), identity, true);
    }

    private JsonNode json(String text) throws Exception {
        return mapper.readTree(text);
    }

    @Test
    @DisplayName("every tool refuses a non-admin: editors and viewers keep read_model_prices only")
    void nonAdminRefused() {
        for (String role : List.of("eddi-editor", "eddi-viewer", "eddi-user", "eddi-approver")) {
            var t = tools(Set.of(role));
            assertThrows(ForbiddenException.class, () -> t.readTenantQuota(null), role);
            assertThrows(ForbiddenException.class, () -> t.updateModelPrices(2L, "[]", null, null, "r"), role);
            assertThrows(ForbiddenException.class, () -> t.updateTenantQuota(null, "x", 1, null, null, null, null, "r"), role);
            assertThrows(ForbiddenException.class, () -> t.listSpendControlChanges(null, null), role);
            assertThrows(ForbiddenException.class, () -> t.withdrawSpendControlChange("id", "r"), role);
        }
        verify(catalogService, never()).replace(anyLong(), any(), anyString());
    }

    @Test
    @DisplayName("a raised price applies; a lowered price is PROPOSED and never applied")
    void pricesThroughTheTool() throws Exception {
        var admin = tools(Set.of("eddi-admin"));
        String raise = "[{\"provider\":\"openai\",\"model\":\"gpt-4o\",\"inputPricePer1M\":3,\"outputPricePer1M\":12,"
                + "\"source\":\"https://openai.com/api/pricing\"}]";
        assertEquals("APPLIED", json(admin.updateModelPrices(2L, raise, null, null, "price went up")).get("result").asText());
        verify(catalogService).replace(eq(2L), any(), eq("root"));

        clearInvocations(catalogService);
        String lower = "[{\"provider\":\"openai\",\"model\":\"gpt-4o\",\"inputPricePer1M\":0,\"outputPricePer1M\":0,"
                + "\"source\":\"https://openai.com/api/pricing\"}]";
        JsonNode proposed = json(admin.updateModelPrices(2L, lower, null, null, "make it free"));
        assertEquals("PROPOSED", proposed.get("result").asText());
        assertTrue(proposed.get("note").asText().contains("Nothing was applied"));
        assertFalse(proposed.get("loosening").isEmpty());
        verify(catalogService, never()).replace(anyLong(), any(), anyString());
    }

    @Test
    @DisplayName("a stale revision and malformed JSON are caller errors")
    void callerErrors() throws Exception {
        var admin = tools(Set.of("eddi-admin"));
        assertEquals("CONFLICT", json(admin.updateModelPrices(1L, null, null, "REFUSE", "r")).get("errorCode").asText());
        assertEquals("BAD_REQUEST", json(admin.updateModelPrices(2L, "[{", null, null, "r")).get("errorCode").asText());
        assertEquals("BAD_REQUEST", json(admin.updateModelPrices(2L, null, null, "SOMETIMES", "r")).get("errorCode").asText());
        assertEquals("BAD_REQUEST", json(admin.updateModelPrices(null, null, null, "REFUSE", "r")).get("errorCode").asText());
    }

    @Test
    @DisplayName("the quota read carries the fingerprint; raising the budget is PROPOSED, lowering applies")
    void quota() throws Exception {
        var admin = tools(Set.of("eddi-admin"));
        JsonNode read = json(admin.readTenantQuota(null));
        String fingerprint = read.get("fingerprint").asText();
        assertEquals(50.0, read.get("quota").get("maxMonthlyCostUsd").asDouble());

        assertEquals("PROPOSED", json(admin.updateTenantQuota(null, fingerprint, null, null, null, 5000.0, null, "more")).get("result").asText());
        assertEquals(50.0, quotaStore.getQuota("default").maxMonthlyCostUsd());

        JsonNode applied = json(admin.updateTenantQuota(null, fingerprint, null, null, null, 10.0, null, "less"));
        assertEquals("APPLIED", applied.get("result").asText());
        assertEquals(10.0, quotaStore.getQuota("default").maxMonthlyCostUsd());
        assertFalse(fingerprint.equals(applied.get("fingerprint").asText()));
    }

    @Test
    @DisplayName("withdrawing another's or an unknown change, and an unknown status filter, are caller errors; APPLYING and FAILED filter")
    void withdrawAndList() throws Exception {
        var admin = tools(Set.of("eddi-admin"));
        assertEquals("NOT_FOUND", json(admin.withdrawSpendControlChange("nope", "r")).get("errorCode").asText());

        String lower = "[{\"provider\":\"openai\",\"model\":\"gpt-4o\",\"inputPricePer1M\":0,\"outputPricePer1M\":0,"
                + "\"source\":\"https://openai.com/api/pricing\"}]";
        String id = json(admin.updateModelPrices(2L, lower, null, null, "free")).get("change").get("changeId").asText();
        SpendControlChange mine = created.get(0);
        when(store.read(id)).thenReturn(Optional.of(mine));
        SpendControlChange othersChange = new SpendControlChange("other", mine.kind(), mine.status(), null, mine.baseRevision(), null,
                mine.proposedCatalog(), null, mine.changes(), mine.loosening(), "r", "someone-else", "mcp", mine.proposedAtMillis(),
                mine.expiresAtMillis(), null, null, null, null, "h");
        when(store.read("other")).thenReturn(Optional.of(othersChange));
        assertEquals("NOT_FOUND", json(admin.withdrawSpendControlChange("other", "r")).get("errorCode").asText());
        verify(store, never()).compareAndSetStatus(eq("other"), any(), any());

        when(store.compareAndSetStatus(eq(id), eq(SpendControlChange.Status.PENDING), any())).thenReturn(true);
        assertEquals("WITHDRAWN", json(admin.withdrawSpendControlChange(id, "not needed")).get("status").asText());

        assertTrue(json(admin.listSpendControlChanges("applying", null)).has("changes"));
        assertTrue(json(admin.listSpendControlChanges("FAILED", 5)).has("changes"));
        assertEquals("BAD_REQUEST", json(admin.listSpendControlChanges("SOMETIMES", null)).get("errorCode").asText());
        verify(catalogService, never()).replace(anyLong(), any(), anyString());
    }

    @Test
    @DisplayName("no MCP tool approves, rejects or applies a spend-control change")
    void noApprovalTool() {
        List<String> names = Arrays.stream(McpSpendControlTools.class.getDeclaredMethods()).filter(m -> m.isAnnotationPresent(Tool.class))
                .map(m -> m.getAnnotation(Tool.class).name()).toList();
        assertEquals(List.of(), names.stream().filter(n -> n.contains("approve") || n.contains("reject") || n.contains("apply")).toList());
        assertTrue(names.contains("withdraw_spend_control_change"));
    }
}
