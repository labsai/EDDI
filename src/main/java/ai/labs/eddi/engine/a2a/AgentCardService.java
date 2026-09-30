/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.a2a;

import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.engine.a2a.A2AModels.AgentAuthentication;
import ai.labs.eddi.engine.a2a.A2AModels.AgentCapabilities;
import ai.labs.eddi.engine.a2a.A2AModels.AgentCard;
import ai.labs.eddi.engine.a2a.A2AModels.AgentSkill;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static ai.labs.eddi.utils.RuntimeUtilities.isNullOrEmpty;
import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * Generates A2A Agent Cards from deployed EDDI agent configurations.
 *
 * @author ginccc
 */
@ApplicationScoped
public class AgentCardService {

    private static final Logger LOGGER = Logger.getLogger(AgentCardService.class);

    /** How many agent descriptors a single card lookup will scan. */
    private static final int MAX_AGENT_DESCRIPTORS = 100;

    /**
     * How long the roster scans behind {@link #getDefaultAgentCard()} and
     * {@link #listA2AAgents()} are reused.
     * <p>
     * {@code /.well-known/agent.json} is anonymous by design, and on a deployment
     * with no A2A-enabled agent — the default — the stop-at-first-match shortcut
     * never stops: every request scanned all {@value #MAX_AGENT_DESCRIPTORS}
     * candidates, two store reads apiece, so one unauthenticated GET cost about two
     * hundred database queries and a loop of them was a free amplifier against the
     * database. Caching the result, empty included, makes that one scan per window
     * no matter how many requests arrive, and concurrent requests during a scan
     * wait for it rather than starting their own. An agent switching
     * {@code a2aEnabled} shows up in discovery within the window; the per-agent
     * card and the task endpoint read the agent directly and are never stale.
     */
    static final Duration ROSTER_CACHE_TTL = Duration.ofSeconds(30);

    private final Cache<Boolean, List<AgentCard>> rosterCache = Caffeine.newBuilder()
            .maximumSize(2)
            .expireAfterWrite(ROSTER_CACHE_TTL)
            .build();

    private final IAgentStore agentStore;
    private final IDocumentDescriptorStore documentDescriptorStore;
    private final String baseUrl;
    private final boolean authEnabled;
    private final String oidcAuthServerUrl;
    private final String publicTokenEndpoint;
    private final String keycloakPublicUrl;

    @Inject
    public AgentCardService(IAgentStore agentStore, IDocumentDescriptorStore documentDescriptorStore,
            @ConfigProperty(name = "eddi.a2a.base-url", defaultValue = "http://localhost:7070") String baseUrl,
            @ConfigProperty(name = "authorization.enabled", defaultValue = "false") boolean authEnabled,
            @ConfigProperty(name = "quarkus.oidc.auth-server-url") Optional<String> oidcAuthServerUrl,
            @ConfigProperty(name = "eddi.a2a.public-token-endpoint") Optional<String> publicTokenEndpoint,
            @ConfigProperty(name = "eddi.keycloak.public.url") Optional<String> keycloakPublicUrl) {
        this.agentStore = agentStore;
        this.documentDescriptorStore = documentDescriptorStore;
        this.baseUrl = baseUrl;
        this.authEnabled = authEnabled;
        this.oidcAuthServerUrl = oidcAuthServerUrl.orElse(null);
        this.publicTokenEndpoint = publicTokenEndpoint.filter(url -> !url.isBlank()).orElse(null);
        this.keycloakPublicUrl = keycloakPublicUrl.filter(url -> !url.isBlank()).orElse(null);
    }

    /**
     * The issuer URL to advertise in an Agent Card, which is not necessarily the
     * one EDDI itself uses.
     * <p>
     * {@code quarkus.oidc.auth-server-url} is how <em>this process</em> reaches the
     * IdP: the Helm chart points it at the in-cluster Keycloak Service and the auth
     * compose profile at {@code http://keycloak:8080}. Publishing that to an
     * external peer — which the card now does anonymously — advertises a host the
     * peer cannot resolve, so discovery dead-ends and the token endpoint has to be
     * communicated out of band.
     * <p>
     * Resolution order:
     * <ol>
     * <li>{@code eddi.keycloak.public.url} grafted onto the issuer's path. Both
     * shipped authenticated deployments already set it (Helm <em>requires</em> it;
     * the SPA cannot start a login without it), so they are correct with no new
     * configuration.</li>
     * <li>{@code quarkus.oidc.auth-server-url} unchanged — which is right whenever
     * EDDI and its peers reach the IdP by the same name, the externally hosted IdP
     * case. Nothing moves for a deployment that does not opt in.</li>
     * </ol>
     * Only used to <em>derive</em> a Keycloak-shaped token endpoint; an operator
     * who needs a different one sets {@code eddi.a2a.public-token-endpoint} and
     * this is not consulted. See {@link #advertisedTokenEndpoint()}.
     *
     * @return the issuer URL, or null when OIDC is not configured at all
     */
    String publicIssuerUrl() {
        if (keycloakPublicUrl != null && oidcAuthServerUrl != null) {
            try {
                var issuer = URI.create(oidcAuthServerUrl);
                var publicOrigin = URI.create(stripTrailingSlash(keycloakPublicUrl));
                // Only the origin is taken from the public URL; the realm path stays
                // whatever EDDI is actually configured against, so the two cannot drift.
                var grafted = new URI(publicOrigin.getScheme(), publicOrigin.getAuthority(),
                        issuer.getPath(), null, null);
                return stripTrailingSlash(grafted.toString());
            } catch (Exception e) {
                // A malformed URL must not cost the peer its card — the card is still
                // correct and useful without an authentication block it cannot trust.
                LOGGER.warnf("Could not derive a public issuer URL from '%s' and '%s': %s",
                        sanitize(keycloakPublicUrl), sanitize(oidcAuthServerUrl), e.getMessage());
            }
        }
        return oidcAuthServerUrl == null ? null : stripTrailingSlash(oidcAuthServerUrl);
    }

    /**
     * The token endpoint an Agent Card advertises.
     * <p>
     * {@code eddi.a2a.public-token-endpoint} is taken verbatim when set — the
     * endpoint, not the issuer, because the path is the part that is
     * provider-specific. Otherwise the endpoint is derived from
     * {@link #publicIssuerUrl()} plus Keycloak's
     * {@code /protocol/openid-connect/token}, <b>which assumes Keycloak</b>: it is
     * what every shipped authenticated deployment runs, and it keeps those correct
     * with no configuration at all. An operator on any other IdP sets the property.
     * <p>
     * OIDC discovery ({@code <issuer>/.well-known/openid-configuration}) would
     * remove the assumption rather than document it, and is the right follow-up. It
     * is not done here because it turns card rendering into an outbound HTTP call —
     * needing {@code SafeHttpClient}, a cache and a failure policy — on a path that
     * is now anonymous.
     *
     * @return the token endpoint, or null when OIDC is not configured at all
     */
    String advertisedTokenEndpoint() {
        if (publicTokenEndpoint != null) {
            return publicTokenEndpoint;
        }
        String issuer = publicIssuerUrl();
        return issuer == null ? null : issuer + "/protocol/openid-connect/token";
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /**
     * Generate an AgentCard for a specific agent.
     *
     * @param agentId
     *            the agent's ID
     *
     * @return the AgentCard, or null if the agent doesn't exist or isn't
     *         A2A-enabled
     */
    public AgentCard getAgentCard(String agentId) {
        try {
            var resourceId = agentStore.getCurrentResourceId(agentId);
            if (resourceId == null) {
                return null;
            }
            AgentConfiguration config = agentStore.read(agentId, resourceId.getVersion());
            if (config == null || !config.isA2aEnabled()) {
                return null;
            }

            return buildAgentCard(agentId, config, resourceId.getVersion());
        } catch (Exception e) {
            // Sanitized: the id is the path parameter of an anonymous endpoint, and a raw
            // CR/LF in it forged log lines.
            LOGGER.warnf("Failed to build Agent Card for agentId=%s: %s", sanitize(agentId), e.getMessage());
            return null;
        }
    }

    /**
     * The Agent Card served at {@code /.well-known/agent.json} — the first
     * A2A-enabled agent's.
     * <p>
     * Stops at the first match rather than reusing {@link #listA2AAgents()} and
     * taking element zero. That shortcut built a card for every A2A-enabled agent —
     * {@code getCurrentResourceId} + {@code read} + {@code readDescriptor} apiece —
     * and threw all but one away. It was merely wasteful while the endpoint
     * required a token; it is an amplification vector now that the endpoint is
     * anonymous, since one unauthenticated GET would fan out across up to
     * {@value #MAX_AGENT_DESCRIPTORS} candidates.
     *
     * @return the default AgentCard, or null when no agent is A2A-enabled
     */
    public AgentCard getDefaultAgentCard() {
        List<AgentCard> cards = rosterCache.get(Boolean.TRUE, stopAtFirst -> List.copyOf(collectA2AAgents(true)));
        return cards.isEmpty() ? null : cards.get(0);
    }

    /**
     * List Agent Cards for all A2A-enabled agents.
     *
     * @return list of AgentCards (may be empty)
     */
    public List<AgentCard> listA2AAgents() {
        return rosterCache.get(Boolean.FALSE, stopAtFirst -> List.copyOf(collectA2AAgents(false)));
    }

    /** Drops the cached roster scans — for tests, and after a bulk change. */
    void invalidateRoster() {
        rosterCache.invalidateAll();
    }

    /**
     * @param stopAtFirst
     *            return as soon as one A2A-enabled agent has been found, instead of
     *            building a card for every candidate
     */
    private List<AgentCard> collectA2AAgents(boolean stopAtFirst) {
        List<AgentCard> cards = new ArrayList<>();
        try {
            // Unrestricted deliberately: an Agent Card is published to A2A *peers*, which
            // are
            // remote systems rather than EDDI users, so the caller has no workspace to
            // scope
            // to. The gate for this surface is `isA2aEnabled()` on the agent plus whatever
            // authentication fronts the A2A endpoints — not the workspace model.
            List<DocumentDescriptor> descriptors = documentDescriptorStore.readDescriptors("ai.labs.agent", "", 0,
                    MAX_AGENT_DESCRIPTORS, false);
            if (descriptors == null) {
                return cards;
            }

            for (DocumentDescriptor descriptor : descriptors) {
                URI resourceUri = descriptor.getResource();
                if (resourceUri == null) {
                    continue;
                }
                String path = resourceUri.getPath();
                if (isNullOrEmpty(path)) {
                    continue;
                }
                // URI format: eddi://ai.labs.agent/agentstore/agents/{agentId}?version=N
                String[] segments = path.split("/");
                if (segments.length < 2) {
                    continue;
                }
                String agentId = segments[segments.length - 1];

                AgentCard card = getAgentCard(agentId);
                if (card != null) {
                    cards.add(card);
                    if (stopAtFirst) {
                        return cards;
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.warnf("Failed to list A2A agents: %s", e.getMessage());
        }
        return cards;
    }

    /**
     * Build an AgentCard from an agent configuration.
     */
    /**
     * The agent's human name, as its descriptor records it.
     * <p>
     * A2A Agent Cards are how other systems discover what an agent <em>is</em>, and
     * every card announced "EDDI Agent 6a1f…" — the raw id, for every agent. The
     * name an operator gave the agent lives on its {@link DocumentDescriptor}, not
     * on {@link AgentConfiguration}, which is why it was never reached for. Falls
     * back to the old form when the descriptor is missing or unnamed, so a card is
     * still served rather than dropped.
     */
    private String agentDisplayName(String agentId, Integer version) {
        try {
            DocumentDescriptor descriptor = documentDescriptorStore.readDescriptor(agentId, version);
            if (descriptor != null && !isNullOrEmpty(descriptor.getName())) {
                return descriptor.getName();
            }
        } catch (Exception e) {
            LOGGER.debugf("No descriptor name for A2A agent %s: %s", sanitize(agentId), e.getMessage());
        }
        return "EDDI Agent " + agentId;
    }

    AgentCard buildAgentCard(String agentId, AgentConfiguration config, Integer version) {
        String name = agentDisplayName(agentId, version);

        String description = !isNullOrEmpty(config.getDescription()) ? config.getDescription() : "EDDI conversational AI agent";

        String agentUrl = baseUrl + "/a2a/agents/" + agentId;

        // Build skills
        List<AgentSkill> skills = new ArrayList<>();
        if (config.getA2aSkills() != null && !config.getA2aSkills().isEmpty()) {
            for (String skillName : config.getA2aSkills()) {
                skills.add(new AgentSkill(skillName.toLowerCase().replace(' ', '-'), skillName, "Skill: " + skillName, null, null));
            }
        } else {
            // Default skill
            skills.add(new AgentSkill("chat", "Conversational AI", "General conversational AI agent powered by EDDI", List.of("chat", "ai"), null));
        }

        var capabilities = new AgentCapabilities(false, false, true);

        // Build authentication info if auth is enabled
        AgentAuthentication authentication = null;
        if (authEnabled) {
            authentication = new AgentAuthentication(List.of("Bearer"), advertisedTokenEndpoint());
        }

        return new AgentCard(name, description, agentUrl, "EDDI", "6.0.0", capabilities, skills, authentication);
    }
}
