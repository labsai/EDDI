/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.groups.budget;

import ai.labs.eddi.configs.groups.IAgentGroupStore;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.ProtocolConfig.CostPolicy;
import ai.labs.eddi.configs.groups.model.TeamBudgetConfig;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.tenancy.TenantQuotaService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.OptionalDouble;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Wiring shared by the team-budget unit tests. */
final class TeamBudgetTestSupport {

    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-02T12:00:00Z"), ZoneOffset.UTC);

    final InMemoryTeamBudgetStore store = new InMemoryTeamBudgetStore();
    final IAgentGroupStore groupStore = mock(IAgentGroupStore.class);
    final TenantQuotaService tenant = mock(TenantQuotaService.class);
    final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    final TeamBudgetAudit audit = mock(TeamBudgetAudit.class);
    final TeamBudgetService service;
    final TeamSpendMeter meter;
    private final Map<String, AgentGroupConfiguration> configs = new HashMap<>();

    TeamBudgetTestSupport() {
        try {
            when(groupStore.getCurrentResourceId(anyString())).thenAnswer(inv -> {
                String id = inv.getArgument(0);
                if (!configs.containsKey(id)) {
                    throw new IResourceStore.ResourceNotFoundException("no group");
                }
                IResourceStore.IResourceId rid = mock(IResourceStore.IResourceId.class);
                when(rid.getVersion()).thenReturn(1);
                return rid;
            });
            when(groupStore.read(anyString(), anyInt())).thenAnswer(inv -> configs.get(inv.<String>getArgument(0)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        when(tenant.getDefaultTenantId()).thenReturn("default");
        when(tenant.remainingCostUsd(anyString())).thenReturn(OptionalDouble.empty());
        service = new TeamBudgetService(store, groupStore, tenant, registry, audit, CLOCK);
        meter = new TeamSpendMeter(service, registry);
    }

    AgentGroupConfiguration group(String id, String name, Double cap, CostPolicy onExhausted) {
        AgentGroupConfiguration config = new AgentGroupConfiguration();
        config.setName(name);
        if (cap != null) {
            config.setTeamBudget(new TeamBudgetConfig(cap, onExhausted, null));
        }
        configs.put(id, config);
        return config;
    }

    double counter(String name, String... tags) {
        var c = registry.find(name).tags(tags).counter();
        return c == null ? -1 : c.count();
    }
}
