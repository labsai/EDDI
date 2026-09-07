/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.openai;

import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.engine.model.Deployment.Environment;
import ai.labs.eddi.engine.model.Deployment.Status;
import ai.labs.eddi.engine.runtime.IAgent;
import ai.labs.eddi.engine.runtime.IAgentFactory;
import ai.labs.eddi.integrations.openai.model.ModelObject;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.text.Normalizer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Maps OpenAI {@code model} identifiers to deployed EDDI agents, and renders
 * the deployed-agent set as an OpenAI model list.
 * <p>
 * <b>Model id format:</b> {@code <slugified-descriptor-name>-<last 6 hex of
 * agentId>} — for example {@code customer-support-a3f9c1}. The suffix exists
 * because descriptor names are <em>not</em> unique: a bare slug would make
 * resolution non-deterministic as soon as two agents are both called "Support".
 * The composite is readable in a model dropdown and collision-free.
 * <p>
 * Each agent is additionally exposed with a {@value #STATELESS_SUFFIX} suffix
 * (when enabled). That variant runs one throwaway conversation per request —
 * the answer to Open WebUI's title/tag generation, which would otherwise inject
 * utility prompts into the user's real conversation.
 * <p>
 * <b>Why the deployment data is read from {@link IAgentFactory} rather than
 * {@code IRestAgentAdministration}:</b> that REST interface is annotated
 * {@code @RolesAllowed({"eddi-admin","eddi-editor"})} at type level, and
 * Quarkus enforces it with a CDI interceptor — so injecting it here and calling
 * it would 403 for every ordinary caller. The REST facade is an authorization
 * boundary; a public surface must not reach around it.
 *
 * @since 6.1.0
 */
@ApplicationScoped
public class AgentModelResolver {

    private static final Logger LOGGER = Logger.getLogger(AgentModelResolver.class);

    /** Suffix marking the one-shot, no-memory variant of a model. */
    public static final String STATELESS_SUFFIX = ":stateless";

    private static final Pattern NON_SLUG_CHARS = Pattern.compile("[^a-z0-9]+");

    /** Diacritics left behind by NFD decomposition, stripped during slugging. */
    private static final Pattern COMBINING_MARKS = Pattern.compile("\\p{M}+");

    /** Fallback slug when a descriptor name slugifies to nothing (e.g. "***"). */
    private static final String FALLBACK_SLUG = "agent";

    /** Characters of the agentId appended to the slug to disambiguate. */
    private static final int ID_SUFFIX_LENGTH = 6;

    /** Single cache entry key — the model catalogue is small and read whole. */
    private static final String CATALOGUE_KEY = "catalogue";

    private final IAgentFactory agentFactory;
    private final IDocumentDescriptorStore documentDescriptorStore;
    private final OpenAiCompatConfig config;

    private Cache<String, Catalogue> cache;

    private final ResourceAccessGuard resourceAccessGuard;

    @Inject
    public AgentModelResolver(IAgentFactory agentFactory,
            IDocumentDescriptorStore documentDescriptorStore,
            OpenAiCompatConfig config,
            ResourceAccessGuard resourceAccessGuard) {
        this.agentFactory = agentFactory;
        this.documentDescriptorStore = documentDescriptorStore;
        this.config = config;
        this.resourceAccessGuard = resourceAccessGuard;
    }

    @PostConstruct
    void initCache() {
        cache = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofSeconds(Math.max(1, config.getModelCacheSeconds())))
                .maximumSize(1)
                .build();
    }

    /**
     * A model identifier resolved to a concrete agent.
     *
     * @param requestedModelId
     *            exactly what the client sent — echoed back in completion
     *            responses, because OpenAI clients match the {@code model} field
     *            against what they asked for
     * @param canonicalModelId
     *            the catalogue id, including the {@value #STATELESS_SUFFIX} suffix
     *            when applicable — what {@code GET /v1/models} lists, and what a
     *            single-model lookup must report
     * @param stateless
     *            whether the caller asked for the one-shot variant — the
     *            conversation is ended immediately after the turn and no
     *            user-conversation mapping is stored
     */
    public record ResolvedModel(String agentId, Environment environment, String displayName,
            String requestedModelId, String canonicalModelId, long createdEpochSeconds,
            boolean stateless) {

        /**
         * This model with statelessness forced on, for a request that asked via the
         * body field rather than the model suffix. Returns {@code this} when already
         * stateless, and keeps {@link #canonicalModelId()} consistent so the two routes
         * to the same behaviour describe themselves identically.
         */
        public ResolvedModel asStateless() {
            if (stateless) {
                return this;
            }
            return new ResolvedModel(agentId, environment, displayName, requestedModelId,
                    canonicalModelId + STATELESS_SUFFIX, createdEpochSeconds, true);
        }
    }

    /** Raised when no deployed agent matches the requested model id. */
    public static class UnknownModelException extends Exception {
        public UnknownModelException(String message) {
            super(message);
        }
    }

    /**
     * Raised when a non-canonical identifier (a bare name or slug) matches more
     * than one deployed agent. Resolving it arbitrarily would silently route a
     * conversation to the wrong agent, so the caller is told to use the canonical
     * model id instead.
     */
    public static class AmbiguousModelException extends Exception {
        public AmbiguousModelException(String message) {
            super(message);
        }
    }

    /**
     * The OpenAI model list for all ready agents the caller may actually use.
     * <p>
     * The {@code /v1} surface authenticates with one shared API key and takes the
     * user id from a header it is configured to trust, so there is no verified
     * principal here — {@code ResourceAccessGuard} therefore sees an anonymous
     * caller and admits only <b>published</b> agents once workspaces are enforced.
     * That is the intended answer rather than a limitation: a self-asserted user id
     * must not unlock that user's private agents, and listing a model the same
     * caller would be refused at chat time is worse than not listing it.
     * <p>
     * With workspaces off, {@code seesEverything()} is true and the list is exactly
     * what it always was.
     */
    public List<ModelObject> listModels() {
        Catalogue catalogue = catalogue();
        List<ModelObject> models = new ArrayList<>(catalogue.byModelId().size());
        for (Entry entry : catalogue.byModelId().values()) {
            if (!mayUse(entry.agentId())) {
                continue;
            }
            models.add(ModelObject.of(entry.modelId(), entry.createdEpochSeconds()));
            if (config.isExposeStatelessVariants()) {
                models.add(ModelObject.of(entry.modelId() + STATELESS_SUFFIX, entry.createdEpochSeconds()));
            }
        }
        return models;
    }

    /**
     * Whether the current caller may converse with this agent. Non-throwing: a
     * listing omits what it may not offer rather than failing wholesale.
     */
    private boolean mayUse(String agentId) {
        try {
            resourceAccessGuard.requireAgentUseAccess(agentId);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }
    /**
     * Resolve a client-supplied {@code model} value to a deployed agent.
     * <p>
     * Resolution order — first match wins:
     * <ol>
     * <li>the canonical model id</li>
     * <li>the bare agentId</li>
     * <li>the descriptor name, case-insensitively — only when unique</li>
     * <li>the bare slug — only when unique</li>
     * </ol>
     * Steps 3 and 4 raise {@link AmbiguousModelException} rather than guessing.
     */
    public ResolvedModel resolve(String requestedModel) throws UnknownModelException, AmbiguousModelException {
        if (requestedModel == null || requestedModel.isBlank()) {
            throw new UnknownModelException("No model was specified.");
        }

        String raw = requestedModel.trim();
        boolean stateless = raw.toLowerCase(Locale.ROOT).endsWith(STATELESS_SUFFIX);
        String id = stateless ? raw.substring(0, raw.length() - STATELESS_SUFFIX.length()) : raw;

        if (stateless && !config.isExposeStatelessVariants()) {
            throw new UnknownModelException(
                    "Stateless model variants are disabled (eddi.openai-compat.expose-stateless-variants=false).");
        }

        Catalogue catalogue = catalogue();

        // 1. canonical model id
        Entry entry = catalogue.byModelId().get(id.toLowerCase(Locale.ROOT));
        if (entry != null) {
            return toResolved(entry, raw, stateless);
        }

        // 2. bare agentId
        entry = catalogue.byAgentId().get(id);
        if (entry != null) {
            return toResolved(entry, raw, stateless);
        }

        // 3. descriptor name (unique only)
        List<Entry> byName = catalogue.byName().get(id.toLowerCase(Locale.ROOT));
        if (byName != null) {
            return toResolved(requireUnique(byName, id, "name"), raw, stateless);
        }

        // 4. bare slug (unique only)
        List<Entry> bySlug = catalogue.bySlug().get(slugify(id));
        if (bySlug != null) {
            return toResolved(requireUnique(bySlug, id, "slug"), raw, stateless);
        }

        throw new UnknownModelException("No deployed agent matches model '" + id
                + "'. Call GET /v1/models for the available ids.");
    }

    /** Drop the cached catalogue — used by tests and after a deployment change. */
    public void invalidate() {
        cache.invalidateAll();
    }

    private Entry requireUnique(List<Entry> candidates, String requested, String matchedBy)
            throws AmbiguousModelException {
        if (candidates.size() == 1) {
            return candidates.getFirst();
        }
        List<String> ids = candidates.stream().map(Entry::modelId).toList();
        throw new AmbiguousModelException("Model '" + requested + "' matches " + candidates.size()
                + " deployed agents by " + matchedBy + ": " + String.join(", ", ids)
                + ". Use the full model id.");
    }

    private ResolvedModel toResolved(Entry entry, String requestedModel, boolean stateless) {
        String canonical = stateless ? entry.modelId() + STATELESS_SUFFIX : entry.modelId();
        return new ResolvedModel(entry.agentId(), config.getEnvironment(), entry.displayName(),
                requestedModel, canonical, entry.createdEpochSeconds(), stateless);
    }

    private Catalogue catalogue() {
        return cache.get(CATALOGUE_KEY, key -> buildCatalogue());
    }

    /**
     * Build the model catalogue from the deployed-agent set. Agents whose
     * descriptor cannot be read are skipped rather than failing the whole listing —
     * one broken descriptor must not make every model disappear.
     */
    private Catalogue buildCatalogue() {
        Map<String, Entry> byModelId = new LinkedHashMap<>();
        Map<String, Entry> byAgentId = new LinkedHashMap<>();
        Map<String, List<Entry>> byName = new LinkedHashMap<>();
        Map<String, List<Entry>> bySlug = new LinkedHashMap<>();

        List<IAgent> agents;
        try {
            agents = agentFactory.getAllLatestAgents(config.getEnvironment());
        } catch (Exception e) {
            LOGGER.errorf("Could not list deployed agents for the OpenAI model catalogue: %s", e.getMessage());
            return new Catalogue(byModelId, byAgentId, byName, bySlug);
        }

        for (IAgent agent : agents) {
            if (agent == null || agent.getDeploymentStatus() != Status.READY) {
                continue;
            }
            String agentId = agent.getAgentId();
            String displayName;
            long created;
            try {
                var descriptor = documentDescriptorStore.readDescriptor(agentId, agent.getAgentVersion());
                displayName = descriptor != null && descriptor.getName() != null && !descriptor.getName().isBlank()
                        ? descriptor.getName()
                        : agentId;
                created = descriptor != null && descriptor.getLastModifiedOn() != null
                        ? descriptor.getLastModifiedOn().getTime() / 1000L
                        : 0L;
            } catch (Exception e) {
                LOGGER.debugf("Skipping agent %s in the OpenAI model catalogue — descriptor unreadable: %s",
                        agentId, e.getMessage());
                continue;
            }

            String slug = slugify(displayName);
            String modelId = slug + "-" + idSuffix(agentId);
            Entry entry = new Entry(agentId, displayName, modelId, created);

            byModelId.putIfAbsent(modelId.toLowerCase(Locale.ROOT), entry);
            byAgentId.putIfAbsent(agentId, entry);
            byName.computeIfAbsent(displayName.toLowerCase(Locale.ROOT), k -> new ArrayList<>()).add(entry);
            bySlug.computeIfAbsent(slug, k -> new ArrayList<>()).add(entry);
        }

        LOGGER.debugf("OpenAI model catalogue rebuilt: %d ready agent(s)", byModelId.size());
        return new Catalogue(byModelId, byAgentId, byName, bySlug);
    }

    /**
     * Lower-case, dash-separated form of a descriptor name. Never empty — a name
     * with no alphanumeric characters falls back to {@value #FALLBACK_SLUG}.
     * <p>
     * Accented letters are folded to their ASCII base rather than dropped, so
     * "Übersicht" slugs to {@code ubersicht} and not {@code bersicht}. Dropping
     * them would mangle every non-English agent name into something the operator
     * cannot recognise in a model dropdown.
     */
    static String slugify(String name) {
        if (name == null) {
            return FALLBACK_SLUG;
        }
        String folded = Normalizer.normalize(name, Normalizer.Form.NFD);
        folded = COMBINING_MARKS.matcher(folded).replaceAll("");
        String slug = NON_SLUG_CHARS.matcher(folded.toLowerCase(Locale.ROOT)).replaceAll("-");
        slug = stripDashes(slug);
        return slug.isBlank() ? FALLBACK_SLUG : slug;
    }

    /**
     * Trim leading and trailing {@code -} without a regular expression.
     * <p>
     * This was {@code (^-+|-+$)}, which CodeQL flags as polynomial ReDoS because
     * {@code slugify} runs on the caller-supplied model name. The alert is a false
     * positive twice over: the {@link #NON_SLUG_CHARS} pass immediately above
     * collapses every run into a single {@code -}, so {@code -+} can never match
     * more than one character, and the regex was measured linear anyway (3ms on
     * 400k separators — the engine anchors on {@code $} rather than backtracking).
     * <p>
     * It is replaced regardless: character walking is no harder to read, and it
     * costs nothing to stop a recurring high-severity alert from competing for
     * attention with real ones. Do not read this as a fixed vulnerability.
     */
    private static String stripDashes(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && value.charAt(start) == '-') {
            start++;
        }
        while (end > start && value.charAt(end - 1) == '-') {
            end--;
        }
        return value.substring(start, end);
    }

    /** Last {@value #ID_SUFFIX_LENGTH} characters of the agentId, lower-cased. */
    static String idSuffix(String agentId) {
        if (agentId == null || agentId.isEmpty()) {
            return "000000";
        }
        String tail = agentId.length() <= ID_SUFFIX_LENGTH
                ? agentId
                : agentId.substring(agentId.length() - ID_SUFFIX_LENGTH);
        return tail.toLowerCase(Locale.ROOT);
    }

    private record Entry(String agentId, String displayName, String modelId, long createdEpochSeconds) {
    }

    private record Catalogue(Map<String, Entry> byModelId,
            Map<String, Entry> byAgentId,
            Map<String, List<Entry>> byName,
            Map<String, List<Entry>> bySlug) {
    }
}
