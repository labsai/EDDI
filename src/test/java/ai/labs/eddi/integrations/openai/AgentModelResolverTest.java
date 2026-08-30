/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.openai;

import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.engine.model.Deployment.Status;
import ai.labs.eddi.engine.runtime.IAgent;
import ai.labs.eddi.engine.runtime.IAgentFactory;
import ai.labs.eddi.integrations.openai.model.ModelObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static ai.labs.eddi.integrations.openai.OpenAiTestFixtures.AGENT_ID_OTHER;
import static ai.labs.eddi.integrations.openai.OpenAiTestFixtures.AGENT_ID_SALES;
import static ai.labs.eddi.integrations.openai.OpenAiTestFixtures.AGENT_ID_SUPPORT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AgentModelResolver}.
 * <p>
 * The load-bearing behaviour here is that a non-canonical identifier matching
 * two agents <em>fails</em> instead of picking one. Routing a conversation to
 * the wrong agent is silent and looks like an agent bug, not a routing bug.
 */
class AgentModelResolverTest {

    private IAgentFactory agentFactory;
    private IDocumentDescriptorStore descriptorStore;
    private final List<IAgent> agents = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        agents.clear();
        agentFactory = mock(IAgentFactory.class);
        descriptorStore = mock(IDocumentDescriptorStore.class);
        when(agentFactory.getAllLatestAgents(any())).thenReturn(agents);
    }

    private AgentModelResolver resolver() {
        return resolver(OpenAiTestFixtures.enabledConfig());
    }

    private AgentModelResolver resolver(OpenAiCompatConfig config) {
        var resolver = new AgentModelResolver(agentFactory, descriptorStore, config, permissiveUseGuard());
        resolver.initCache();
        return resolver;
    }

    /** Register a READY agent with the given descriptor name. */
    private void givenAgent(String agentId, String name) throws Exception {
        givenAgent(agentId, name, Status.READY);
    }

    private void givenAgent(String agentId, String name, Status status) throws Exception {
        IAgent agent = mock(IAgent.class);
        when(agent.getAgentId()).thenReturn(agentId);
        when(agent.getAgentVersion()).thenReturn(1);
        when(agent.getDeploymentStatus()).thenReturn(status);
        agents.add(agent);

        var descriptor = new DocumentDescriptor();
        descriptor.setName(name);
        descriptor.setLastModifiedOn(new Date(1_700_000_000_000L));
        when(descriptorStore.readDescriptor(eq(agentId), eq(1))).thenReturn(descriptor);
    }

    // ─── model id construction ───

    @Test
    void modelId_isSlugPlusAgentIdSuffix() throws Exception {
        givenAgent(AGENT_ID_SUPPORT, "Customer Support");

        List<ModelObject> models = resolver().listModels();

        assertTrue(models.stream().anyMatch(m -> "customer-support-a3f9c1".equals(m.id())),
                "expected slug + last 6 of agentId, got: " + models.stream().map(ModelObject::id).toList());
    }

    @Test
    void slugify_collapsesPunctuationAndTrimsDashes() {
        assertEquals("customer-support", AgentModelResolver.slugify("Customer  Support!"));
        assertEquals("uber-agent", AgentModelResolver.slugify("  Über / Agent  "));
        assertEquals("agent", AgentModelResolver.slugify("***"), "a name with no alphanumerics must still yield a slug");
        assertEquals("agent", AgentModelResolver.slugify(null));
    }

    @Test
    void idSuffix_handlesShortAndNullIds() {
        assertEquals("a3f9c1", AgentModelResolver.idSuffix(AGENT_ID_SUPPORT));
        assertEquals("abc", AgentModelResolver.idSuffix("abc"));
        assertEquals("000000", AgentModelResolver.idSuffix(null));
    }

    // ─── listing ───

    @Test
    void listModels_exposesStatefulAndStatelessVariants() throws Exception {
        givenAgent(AGENT_ID_SUPPORT, "Support");

        List<String> ids = resolver().listModels().stream().map(ModelObject::id).toList();

        assertEquals(2, ids.size());
        assertTrue(ids.contains("support-a3f9c1"));
        assertTrue(ids.contains("support-a3f9c1:stateless"));
    }

    @Test
    void listModels_omitsStatelessVariants_whenDisabled() throws Exception {
        givenAgent(AGENT_ID_SUPPORT, "Support");
        var config = OpenAiTestFixtures.config(b -> b.exposeStatelessVariants = false);

        List<String> ids = resolver(config).listModels().stream().map(ModelObject::id).toList();

        assertEquals(List.of("support-a3f9c1"), ids);
    }

    @Test
    void listModels_skipsAgentsThatAreNotReady() throws Exception {
        givenAgent(AGENT_ID_SUPPORT, "Support", Status.IN_PROGRESS);
        givenAgent(AGENT_ID_SALES, "Sales", Status.READY);

        List<String> ids = resolver().listModels().stream().map(ModelObject::id).toList();

        assertFalse(ids.stream().anyMatch(id -> id.startsWith("support-")));
        assertTrue(ids.contains("sales-b4e2d7"));
    }

    @Test
    void listModels_skipsAgentWithUnreadableDescriptor_ratherThanFailingWholeList() throws Exception {
        givenAgent(AGENT_ID_SALES, "Sales");

        IAgent broken = mock(IAgent.class);
        when(broken.getAgentId()).thenReturn(AGENT_ID_SUPPORT);
        when(broken.getAgentVersion()).thenReturn(1);
        when(broken.getDeploymentStatus()).thenReturn(Status.READY);
        agents.add(broken);
        when(descriptorStore.readDescriptor(eq(AGENT_ID_SUPPORT), eq(1)))
                .thenThrow(new RuntimeException("descriptor gone"));

        List<String> ids = resolver().listModels().stream().map(ModelObject::id).toList();

        assertTrue(ids.contains("sales-b4e2d7"), "one broken descriptor must not hide every other model");
        assertFalse(ids.stream().anyMatch(id -> id.contains("a3f9c1")));
    }

    @Test
    void listModels_returnsEmpty_whenAgentListingFails() throws Exception {
        when(agentFactory.getAllLatestAgents(any())).thenThrow(new RuntimeException("store down"));

        assertTrue(resolver().listModels().isEmpty());
    }

    // ─── resolution ───

    @Test
    void resolve_byCanonicalModelId() throws Exception {
        givenAgent(AGENT_ID_SUPPORT, "Customer Support");

        var resolved = resolver().resolve("customer-support-a3f9c1");

        assertEquals(AGENT_ID_SUPPORT, resolved.agentId());
        assertEquals("Customer Support", resolved.displayName());
        assertFalse(resolved.stateless());
    }

    @Test
    void resolve_byBareAgentId() throws Exception {
        givenAgent(AGENT_ID_SUPPORT, "Customer Support");

        assertEquals(AGENT_ID_SUPPORT, resolver().resolve(AGENT_ID_SUPPORT).agentId());
    }

    @Test
    void resolve_byDescriptorName_caseInsensitive() throws Exception {
        givenAgent(AGENT_ID_SUPPORT, "Customer Support");

        assertEquals(AGENT_ID_SUPPORT, resolver().resolve("cUsToMeR sUpPoRt").agentId());
    }

    @Test
    void resolve_byBareSlug() throws Exception {
        givenAgent(AGENT_ID_SUPPORT, "Customer Support");

        assertEquals(AGENT_ID_SUPPORT, resolver().resolve("customer-support").agentId());
    }

    @Test
    void resolve_statelessSuffix_isStrippedAndFlagged() throws Exception {
        givenAgent(AGENT_ID_SUPPORT, "Support");

        var resolved = resolver().resolve("support-a3f9c1:stateless");

        assertEquals(AGENT_ID_SUPPORT, resolved.agentId());
        assertTrue(resolved.stateless());
        assertEquals("support-a3f9c1:stateless", resolved.requestedModelId(),
                "the echoed model id must match what the client sent");
        assertEquals("support-a3f9c1:stateless", resolved.canonicalModelId(),
                "the canonical id keeps the suffix — it is a listed model in its own right");
    }

    @Test
    void resolve_byName_reportsTheCanonicalIdNotTheTypedOne() throws Exception {
        // GET /v1/models/{id} echoes the canonical id. A client that round-trips
        // the answer must get something the catalogue actually lists.
        givenAgent(AGENT_ID_SUPPORT, "Customer Support");

        var resolved = resolver().resolve("Customer Support");

        assertEquals("Customer Support", resolved.requestedModelId());
        assertEquals("customer-support-a3f9c1", resolved.canonicalModelId());
    }

    @Test
    void asStateless_flipsTheFlagAndTheCanonicalId() throws Exception {
        givenAgent(AGENT_ID_SUPPORT, "Support");
        var stateful = resolver().resolve("support-a3f9c1");

        var forced = stateful.asStateless();

        assertTrue(forced.stateless());
        assertEquals("support-a3f9c1:stateless", forced.canonicalModelId(),
                "both routes to statelessness must describe themselves identically");
        assertEquals("support-a3f9c1", forced.requestedModelId(),
                "the echoed id still reflects what the client sent");
        assertEquals(AGENT_ID_SUPPORT, forced.agentId());
    }

    @Test
    void asStateless_isIdempotent() throws Exception {
        givenAgent(AGENT_ID_SUPPORT, "Support");
        var alreadyStateless = resolver().resolve("support-a3f9c1:stateless");

        var forced = alreadyStateless.asStateless();

        assertSame(alreadyStateless, forced);
        assertEquals("support-a3f9c1:stateless", forced.canonicalModelId(),
                "the suffix must not be doubled");
    }

    @Test
    void resolve_carriesTheDescriptorTimestamp() throws Exception {
        givenAgent(AGENT_ID_SUPPORT, "Support");

        assertEquals(1_700_000_000L, resolver().resolve("support-a3f9c1").createdEpochSeconds());
    }

    @Test
    void resolve_statelessSuffix_rejectedWhenVariantsDisabled() throws Exception {
        givenAgent(AGENT_ID_SUPPORT, "Support");
        var config = OpenAiTestFixtures.config(b -> b.exposeStatelessVariants = false);

        assertThrows(AgentModelResolver.UnknownModelException.class,
                () -> resolver(config).resolve("support-a3f9c1:stateless"));
    }

    @Test
    void resolve_duplicateName_isAmbiguous_notAGuess() throws Exception {
        givenAgent(AGENT_ID_SUPPORT, "Support");
        givenAgent(AGENT_ID_SALES, "Support");

        var exception = assertThrows(AgentModelResolver.AmbiguousModelException.class,
                () -> resolver().resolve("Support"));

        assertTrue(exception.getMessage().contains("support-a3f9c1"));
        assertTrue(exception.getMessage().contains("support-b4e2d7"));
    }

    @Test
    void resolve_duplicateSlug_isAmbiguous() throws Exception {
        // Two distinct names that slug identically. Neither equals the queried
        // string, so the name lookup misses and the slug lookup sees both.
        givenAgent(AGENT_ID_SUPPORT, "Customer Support");
        givenAgent(AGENT_ID_SALES, "Customer/Support");

        assertThrows(AgentModelResolver.AmbiguousModelException.class,
                () -> resolver().resolve("customer-support"));
    }

    @Test
    void resolve_exactNameMatch_winsOverSlugCollision() throws Exception {
        // "Customer-Support" lowercases to exactly the queried string, so it is an
        // unambiguous name hit — the slug collision with "Customer Support" never
        // gets consulted. Name equality is more specific than slug equivalence.
        givenAgent(AGENT_ID_SUPPORT, "Customer Support");
        givenAgent(AGENT_ID_SALES, "Customer-Support");

        assertEquals(AGENT_ID_SALES, resolver().resolve("customer-support").agentId());
    }

    @Test
    void slugify_foldsAccents_ratherThanDroppingThem() {
        assertEquals("ubersicht", AgentModelResolver.slugify("Übersicht"));
        assertEquals("cafe-agent", AgentModelResolver.slugify("Café Agent"));
    }

    @Test
    void slugify_trimsRunsOfDashesAtBothEnds() {
        // The dash trim used to be the regex (^-+|-+$). It is now a character
        // walk, so these pin the behaviour that replaced it rather than the
        // implementation that produced it.
        assertEquals("support", AgentModelResolver.slugify("---Support---"));
        assertEquals("support", AgentModelResolver.slugify("!!!Support!!!"));
        assertEquals("a-b", AgentModelResolver.slugify("-a - b-"));
        assertEquals("agent", AgentModelResolver.slugify("-----"),
                "an all-dash name has nothing left after trimming and must fall back");
    }

    @Test
    void slugify_survivesLongRunsOfSeparators() {
        // slugify runs on the caller-supplied model name, so it must stay correct
        // on degenerate input. Deliberately NOT a timing assertion: the regex this
        // replaced was measured at 3ms for this input, so a "is it fast enough"
        // check would pass either way and prove nothing.
        assertEquals("x", AgentModelResolver.slugify("-".repeat(200_000) + "x" + "-".repeat(200_000)));
    }

    @Test
    void resolve_duplicateName_stillResolvesByCanonicalId() throws Exception {
        givenAgent(AGENT_ID_SUPPORT, "Support");
        givenAgent(AGENT_ID_SALES, "Support");

        assertEquals(AGENT_ID_SALES, resolver().resolve("support-b4e2d7").agentId(),
                "the canonical id must remain unambiguous even when names collide");
    }

    @Test
    void resolve_unknownModel_throws() throws Exception {
        givenAgent(AGENT_ID_SUPPORT, "Support");

        assertThrows(AgentModelResolver.UnknownModelException.class, () -> resolver().resolve("gpt-4o"));
    }

    @Test
    void resolve_nullOrBlank_throws() {
        assertThrows(AgentModelResolver.UnknownModelException.class, () -> resolver().resolve(null));
        assertThrows(AgentModelResolver.UnknownModelException.class, () -> resolver().resolve("   "));
    }

    // ─── caching ───

    @Test
    void catalogue_isCached_andInvalidatable() throws Exception {
        givenAgent(AGENT_ID_SUPPORT, "Support");
        var resolver = resolver();

        resolver.listModels();
        resolver.listModels();
        // A second agent appearing is not visible until the cache is dropped.
        givenAgent(AGENT_ID_OTHER, "Other");
        assertEquals(2, resolver.listModels().size(), "cached catalogue must not pick up the new agent");

        resolver.invalidate();
        assertEquals(4, resolver.listModels().size());
    }

    /**
     * A guard that admits every agent: a bare mock's void requireAgentUseAccess
     * does nothing. The /v1 USE gate has its own tests.
     */
    private static ResourceAccessGuard permissiveUseGuard() {
        return mock(ResourceAccessGuard.class);
    }
}
