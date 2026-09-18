/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.a2a;

import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.datastore.IResourceStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentCardServiceTest {

    private IAgentStore restAgentStore;
    private IDocumentDescriptorStore documentDescriptorStore;
    private AgentCardService service;

    @BeforeEach
    void setUp() {
        restAgentStore = mock(IAgentStore.class);
        documentDescriptorStore = mock(IDocumentDescriptorStore.class);
        service = new AgentCardService(
                restAgentStore,
                documentDescriptorStore,
                "http://localhost:7070",
                false,
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    /** An auth-enabled service with the two public-issuer knobs under test. */
    private AgentCardService authServiceWith(String oidcAuthServerUrl, String publicAuthServerUrl,
                                             String keycloakPublicUrl) {
        return new AgentCardService(
                restAgentStore,
                documentDescriptorStore,
                "http://localhost:7070",
                true,
                Optional.ofNullable(oidcAuthServerUrl),
                Optional.ofNullable(publicAuthServerUrl),
                Optional.ofNullable(keycloakPublicUrl));
    }

    /**
     * Which issuer an Agent Card advertises.
     * <p>
     * The card is fetched anonymously by peers outside this deployment, so
     * {@code quarkus.oidc.auth-server-url} — the address <em>EDDI</em> uses, an
     * in-cluster Service under Helm — is the wrong thing to publish. These pin the
     * resolution order, including the fallback that keeps every deployment which
     * configures neither knob exactly where it was.
     */
    @Nested
    class PublicIssuerUrl {

        private static final String INTERNAL = "http://keycloak:8080/realms/eddi";

        @Test
        void explicitPublicUrlWins() {
            var service = authServiceWith(INTERNAL, "https://idp.example.com/realms/eddi",
                    "http://localhost:8180");

            assertEquals("https://idp.example.com/realms/eddi", service.publicIssuerUrl());
        }

        @Test
        void keycloakPublicUrlIsGraftedOntoTheRealmPath() {
            // The shipped authenticated deployments: Helm requires eddi.oidc.publicUrl
            // and the auth compose profile sets EDDI_KEYCLOAK_PUBLIC_URL, so both are
            // correct without any new configuration.
            var service = authServiceWith(INTERNAL, null, "http://localhost:8180");

            assertEquals("http://localhost:8180/realms/eddi", service.publicIssuerUrl());
        }

        @Test
        void trailingSlashOnThePublicUrlDoesNotDoubleUp() {
            var service = authServiceWith(INTERNAL, null, "http://localhost:8180/");

            assertEquals("http://localhost:8180/realms/eddi", service.publicIssuerUrl());
        }

        @Test
        void fallsBackToTheConfiguredIssuerWhenNeitherIsSet() {
            // The externally hosted IdP case, where EDDI and its peers reach the
            // issuer by the same name. Nothing changes for a deployment that has not
            // opted in.
            var service = authServiceWith(INTERNAL, null, null);

            assertEquals(INTERNAL, service.publicIssuerUrl());
        }

        @Test
        void nullWhenOidcIsNotConfiguredAtAll() {
            assertNull(authServiceWith(null, null, null).publicIssuerUrl());
        }

        @Test
        void theCardAdvertisesTheGraftedTokenEndpoint() {
            var service = authServiceWith(INTERNAL, null, "http://localhost:8180");
            var config = new AgentConfiguration();
            config.setA2aEnabled(true);

            var card = service.buildAgentCard("a1", config, 1);

            assertEquals("http://localhost:8180/realms/eddi/protocol/openid-connect/token",
                    card.authentication().credentials());
        }
    }

    // --- getDefaultAgentCard ---

    @Nested
    class GetDefaultAgentCard {

        /**
         * {@code /.well-known/agent.json} is anonymous, so the work one unauthenticated
         * GET can provoke is part of its contract. This used to call
         * {@code listA2AAgents()} and take element zero, building — and discarding — a
         * card for every other A2A-enabled agent: three store reads apiece, up to a
         * hundred candidates.
         */
        @Test
        void stopsAtTheFirstA2AEnabledAgent() throws Exception {
            var descriptors = new ArrayList<DocumentDescriptor>();
            for (int i = 0; i < 25; i++) {
                var descriptor = new DocumentDescriptor();
                descriptor.setResource(URI.create("eddi://ai.labs.agent/agentstore/agents/agent" + i + "?version=1"));
                descriptors.add(descriptor);
            }
            when(documentDescriptorStore.readDescriptors(eq("ai.labs.agent"), anyString(), anyInt(), anyInt(), anyBoolean()))
                    .thenReturn(descriptors);

            var config = new AgentConfiguration();
            config.setA2aEnabled(true);
            when(restAgentStore.getCurrentResourceId(anyString()))
                    .thenReturn(new IResourceStore.IResourceId() {
                        @Override
                        public String getId() {
                            return "agent0";
                        }

                        @Override
                        public Integer getVersion() {
                            return 1;
                        }
                    });
            when(restAgentStore.read(anyString(), anyInt())).thenReturn(config);

            assertNotNull(service.getDefaultAgentCard());

            // One candidate resolved, not twenty-five. Asserting the store calls rather
            // than the returned card is the point: the card was always correct, the
            // cost was not.
            verify(restAgentStore, times(1)).read(anyString(), anyInt());
        }

        @Test
        void returnsNullWhenNoAgentIsA2AEnabled() throws Exception {
            var descriptor = new DocumentDescriptor();
            descriptor.setResource(URI.create("eddi://ai.labs.agent/agentstore/agents/agent0?version=1"));
            when(documentDescriptorStore.readDescriptors(eq("ai.labs.agent"), anyString(), anyInt(), anyInt(), anyBoolean()))
                    .thenReturn(List.of(descriptor));

            var config = new AgentConfiguration();
            config.setA2aEnabled(false);
            when(restAgentStore.getCurrentResourceId(anyString()))
                    .thenReturn(new IResourceStore.IResourceId() {
                        @Override
                        public String getId() {
                            return "agent0";
                        }

                        @Override
                        public Integer getVersion() {
                            return 1;
                        }
                    });
            when(restAgentStore.read(anyString(), anyInt())).thenReturn(config);

            assertNull(service.getDefaultAgentCard());
        }
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
                @Override
                public String getId() {
                    return "agent-1";
                }
                @Override
                public Integer getVersion() {
                    return 1;
                }
            };
            when(restAgentStore.getCurrentResourceId("agent-1")).thenReturn(resourceId);
            when(restAgentStore.read("agent-1", 1)).thenReturn(null);
            assertNull(service.getAgentCard("agent-1"));
        }

        @Test
        void returnsNull_whenNotA2aEnabled() throws Exception {
            var config = new AgentConfiguration();
            config.setA2aEnabled(false);
            var resourceId = new IResourceStore.IResourceId() {
                @Override
                public String getId() {
                    return "agent-1";
                }
                @Override
                public Integer getVersion() {
                    return 1;
                }
            };
            when(restAgentStore.getCurrentResourceId("agent-1")).thenReturn(resourceId);
            when(restAgentStore.read("agent-1", 1)).thenReturn(config);
            assertNull(service.getAgentCard("agent-1"));
        }

        @Test
        void returnsCard_whenA2aEnabled() throws Exception {
            var config = new AgentConfiguration();
            config.setA2aEnabled(true);
            config.setDescription("My agent");
            var resourceId = new IResourceStore.IResourceId() {
                @Override
                public String getId() {
                    return "agent-1";
                }
                @Override
                public Integer getVersion() {
                    return 1;
                }
            };
            when(restAgentStore.getCurrentResourceId("agent-1")).thenReturn(resourceId);
            when(restAgentStore.read("agent-1", 1)).thenReturn(config);

            var card = service.getAgentCard("agent-1");
            assertNotNull(card);
            // No descriptor for this id, so the card falls back to the id form.
            assertEquals("EDDI Agent agent-1", card.name());
            assertEquals("My agent", card.description());
            assertTrue(card.url().contains("agent-1"));
            assertEquals("EDDI", card.provider());
        }

        /**
         * A2A Agent Cards are how other systems discover what an agent <em>is</em>, and
         * every card announced "EDDI Agent &lt;uuid&gt;" — the raw id, for every agent.
         * The operator-given name lives on the DocumentDescriptor, not on
         * AgentConfiguration, which is why it was never reached for.
         */
        @Test
        void usesTheDescriptorName_whenTheAgentHasOne() throws Exception {
            var config = new AgentConfiguration();
            config.setA2aEnabled(true);
            config.setDescription("My agent");

            var resourceId = new IResourceStore.IResourceId() {
                @Override
                public String getId() {
                    return "agent-1";
                }

                @Override
                public Integer getVersion() {
                    return 1;
                }
            };
            when(restAgentStore.getCurrentResourceId("agent-1")).thenReturn(resourceId);
            when(restAgentStore.read("agent-1", 1)).thenReturn(config);

            var descriptor = new DocumentDescriptor();
            descriptor.setName("Refund Specialist");
            when(documentDescriptorStore.readDescriptor("agent-1", 1)).thenReturn(descriptor);

            assertEquals("Refund Specialist", service.getAgentCard("agent-1").name());
        }

        @Test
        void fallsBackToTheIdForm_whenTheDescriptorIsUnnamed() throws Exception {
            var config = new AgentConfiguration();
            config.setA2aEnabled(true);

            var resourceId = new IResourceStore.IResourceId() {
                @Override
                public String getId() {
                    return "agent-2";
                }

                @Override
                public Integer getVersion() {
                    return 1;
                }
            };
            when(restAgentStore.getCurrentResourceId("agent-2")).thenReturn(resourceId);
            when(restAgentStore.read("agent-2", 1)).thenReturn(config);
            when(documentDescriptorStore.readDescriptor("agent-2", 1)).thenReturn(new DocumentDescriptor());

            assertEquals("EDDI Agent agent-2", service.getAgentCard("agent-2").name());
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
            var authService = authServiceWith("http://keycloak:8080/realms/eddi", null, null);

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
            when(documentDescriptorStore.readDescriptors("ai.labs.agent", "", 0, 100, false)).thenReturn(null);
            assertTrue(service.listA2AAgents().isEmpty());
        }

        @Test
        void emptyList_onException() throws Exception {
            when(documentDescriptorStore.readDescriptors("ai.labs.agent", "", 0, 100, false))
                    .thenThrow(new RuntimeException("DB error"));
            assertTrue(service.listA2AAgents().isEmpty());
        }

        @Test
        void skipsDescriptors_withNullResource() throws Exception {
            var desc = new DocumentDescriptor();
            desc.setResource(null);
            when(documentDescriptorStore.readDescriptors("ai.labs.agent", "", 0, 100, false)).thenReturn(List.of(desc));
            assertTrue(service.listA2AAgents().isEmpty());
        }

        @Test
        void skipsDescriptors_withEmptyPath() throws Exception {
            var desc = new DocumentDescriptor();
            desc.setResource(URI.create("eddi://ai.labs.agent"));
            when(documentDescriptorStore.readDescriptors("ai.labs.agent", "", 0, 100, false)).thenReturn(List.of(desc));
            assertTrue(service.listA2AAgents().isEmpty());
        }
    }
}
