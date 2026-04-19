package ai.labs.eddi.engine.a2a;

import ai.labs.eddi.configs.agents.IRestAgentStore;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.datastore.IResourceStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentCardServiceTest {

    private IRestAgentStore restAgentStore;
    private AgentCardService service;

    @BeforeEach
    void setUp() {
        restAgentStore = mock(IRestAgentStore.class);
        service = new AgentCardService(
                restAgentStore,
                "http://localhost:7070",
                false,
                Optional.empty());
    }

    // --- getAgentCard ---

    @Nested
    class GetAgentCard {

        @Test
        void returnsNull_whenNoResourceId() throws Exception {
            when(restAgentStore.getCurrentResourceId("agent-1")).thenReturn(null);
            assertNull(service.getAgentCard("agent-1"));
        }

        @Test
        void returnsNull_whenConfigNull() throws Exception {
            var resourceId = new IResourceStore.IResourceId() {
                public String getId() {
                    return "agent-1";
                }
                public Integer getVersion() {
                    return 1;
                }
            };
            when(restAgentStore.getCurrentResourceId("agent-1")).thenReturn(resourceId);
            when(restAgentStore.readAgent("agent-1", 1)).thenReturn(null);
            assertNull(service.getAgentCard("agent-1"));
        }

        @Test
        void returnsNull_whenNotA2aEnabled() throws Exception {
            var config = new AgentConfiguration();
            config.setA2aEnabled(false);
            var resourceId = new IResourceStore.IResourceId() {
                public String getId() {
                    return "agent-1";
                }
                public Integer getVersion() {
                    return 1;
                }
            };
            when(restAgentStore.getCurrentResourceId("agent-1")).thenReturn(resourceId);
            when(restAgentStore.readAgent("agent-1", 1)).thenReturn(config);
            assertNull(service.getAgentCard("agent-1"));
        }

        @Test
        void returnsCard_whenA2aEnabled() throws Exception {
            var config = new AgentConfiguration();
            config.setA2aEnabled(true);
            config.setDescription("My agent");
            var resourceId = new IResourceStore.IResourceId() {
                public String getId() {
                    return "agent-1";
                }
                public Integer getVersion() {
                    return 1;
                }
            };
            when(restAgentStore.getCurrentResourceId("agent-1")).thenReturn(resourceId);
            when(restAgentStore.readAgent("agent-1", 1)).thenReturn(config);

            var card = service.getAgentCard("agent-1");
            assertNotNull(card);
            assertEquals("EDDI Agent agent-1", card.name());
            assertEquals("My agent", card.description());
            assertTrue(card.url().contains("agent-1"));
            assertEquals("EDDI", card.provider());
        }

        @Test
        void returnsNull_onException() throws Exception {
            when(restAgentStore.getCurrentResourceId("bad"))
                    .thenThrow(new RuntimeException("DB error"));
            assertNull(service.getAgentCard("bad"));
        }
    }

    // --- buildAgentCard ---

    @Nested
    class BuildAgentCard {

        @Test
        void defaultSkill_whenNoSkillsConfigured() {
            var config = new AgentConfiguration();
            config.setA2aEnabled(true);

            var card = service.buildAgentCard("a1", config, 1);
            assertEquals(1, card.skills().size());
            assertEquals("chat", card.skills().get(0).id());
        }

        @Test
        void customSkills() {
            var config = new AgentConfiguration();
            config.setA2aEnabled(true);
            config.setA2aSkills(List.of("Translation", "Code Review"));

            var card = service.buildAgentCard("a2", config, 1);
            assertEquals(2, card.skills().size());
            assertEquals("translation", card.skills().get(0).id());
            assertEquals("code-review", card.skills().get(1).id());
        }

        @Test
        void noAuth_whenDisabled() {
            var config = new AgentConfiguration();
            config.setA2aEnabled(true);

            var card = service.buildAgentCard("a3", config, 1);
            assertNull(card.authentication());
        }

        @Test
        void withAuth_whenEnabled() {
            var authService = new AgentCardService(
                    restAgentStore,
                    "http://localhost:7070",
                    true,
                    Optional.of("http://keycloak:8080/realms/eddi"));

            var config = new AgentConfiguration();
            config.setA2aEnabled(true);

            var card = authService.buildAgentCard("a4", config, 1);
            assertNotNull(card.authentication());
            assertEquals(List.of("Bearer"), card.authentication().schemes());
            assertTrue(card.authentication().credentials().contains("openid-connect/token"));
        }

        @Test
        void defaultDescription_whenNone() {
            var config = new AgentConfiguration();
            config.setA2aEnabled(true);

            var card = service.buildAgentCard("a5", config, 1);
            assertEquals("EDDI conversational AI agent", card.description());
        }

        @Test
        void capabilities() {
            var config = new AgentConfiguration();
            config.setA2aEnabled(true);

            var card = service.buildAgentCard("a6", config, 1);
            assertTrue(card.capabilities().stateTransitionHistory());
            assertFalse(card.capabilities().streaming());
            assertFalse(card.capabilities().pushNotifications());
        }
    }

    // --- listA2AAgents ---

    @Nested
    class ListA2AAgents {

        @Test
        void emptyList_whenNoDescriptors() throws Exception {
            when(restAgentStore.readAgentDescriptors("", 0, 100)).thenReturn(null);
            assertTrue(service.listA2AAgents().isEmpty());
        }

        @Test
        void emptyList_onException() throws Exception {
            when(restAgentStore.readAgentDescriptors("", 0, 100))
                    .thenThrow(new RuntimeException("DB error"));
            assertTrue(service.listA2AAgents().isEmpty());
        }

        @Test
        void skipsDescriptors_withNullResource() throws Exception {
            var desc = new DocumentDescriptor();
            desc.setResource(null);
            when(restAgentStore.readAgentDescriptors("", 0, 100)).thenReturn(List.of(desc));
            assertTrue(service.listA2AAgents().isEmpty());
        }

        @Test
        void skipsDescriptors_withEmptyPath() throws Exception {
            var desc = new DocumentDescriptor();
            desc.setResource(URI.create("eddi://ai.labs.agent"));
            when(restAgentStore.readAgentDescriptors("", 0, 100)).thenReturn(List.of(desc));
            assertTrue(service.listA2AAgents().isEmpty());
        }
    }
}
