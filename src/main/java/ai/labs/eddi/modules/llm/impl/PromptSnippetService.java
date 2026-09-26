/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.descriptors.model.AccessLevel;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.configs.snippets.IPromptSnippetStore;
import ai.labs.eddi.configs.snippets.model.PromptSnippet;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.serialization.IDescriptorStore;
import ai.labs.eddi.engine.security.spaces.CallerSpaces;
import ai.labs.eddi.engine.security.spaces.DescriptorAccess;
import ai.labs.eddi.engine.security.spaces.SharingChangedEvent;
import ai.labs.eddi.engine.security.spaces.Subjects;
import ai.labs.eddi.engine.security.spaces.WorkspaceSettings;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * Cached service that loads prompt snippets and provides them as a template
 * data map for LLM task system prompts.
 * <p>
 * Snippets are auto-available via {@code {snippets.<name>}} in system prompt
 * templates. The cache auto-expires after 5 minutes (TTL) and can be explicitly
 * invalidated via {@link #invalidateCache()}.
 * <p>
 * <b>Content is stored raw.</b> A snippet is never concatenated into a
 * template's SOURCE — it is put into the template DATA map and pulled in by an
 * expression, and Qute does not re-parse what an expression resolved to. Any
 * {@code {...}} inside a snippet therefore reaches the model literally already,
 * which is exactly the {@code templateEnabled=false} guarantee, for free and
 * for every snippet.
 * <p>
 * This used to wrap {@code templateEnabled=false} content in a Qute unparsed
 * block. That protected nothing it was not already protected from, and since
 * the wrapper is itself a resolved value it was likewise never re-parsed: the
 * {@code {|…|}} delimiters travelled into the system prompt verbatim. Escaping
 * belongs only where generated text is concatenated into template source — see
 * {@link ai.labs.eddi.modules.templating.TemplateEscaping}.
 * <p>
 * The corollary is that {@code templateEnabled=true} does not make a snippet's
 * markers resolve either; the flag currently has no effect on this path.
 * Honouring it would mean rendering snippet content in a second pass, which is
 * a design decision with an injection surface attached — snippet text is
 * admin-authored, but a second evaluation pass over data is precisely the shape
 * EDDI avoids elsewhere. Left as-is deliberately rather than by oversight.
 *
 * <h3>Which snippets a conversation sees</h3> Snippets are injected <em>by
 * name</em>, automatically, into every render — nobody opts in to a particular
 * one. That makes a share different from a share of any other resource: a grant
 * or a publish on a snippet is not an offer the recipient may take up, it is a
 * <em>push</em> into the recipient's prompts, and
 * {@code ResourceSharingService} only asks the snippet's owner. So under
 * enforced workspaces ({@code eddi.workspaces.enabled=true}),
 * {@link #getForAgent} injects only snippets from sources the agent's own side
 * controls:
 * <ol>
 * <li>snippets filed in the agent's own space, which that space's members may
 * use;</li>
 * <li>for an agent in a <em>personal</em> space, its owner's own snippets — but
 * not for an agent in a team space, whose prompt every team editor can change
 * and read back, so the creator's private snippets must not ride along;</li>
 * <li>legacy (unowned) snippets — the pre-workspace shared namespace. No tenant
 * can create one once enforcement is on, because ownership is stamped whenever
 * authentication is, and they load regardless of
 * {@code eddi.workspaces.legacy-visibility} for the same reason every other
 * configuration an agent references does: that policy governs the authoring
 * surface, not the engine.</li>
 * </ol>
 * A snippet from another space reaches an agent through none of these, however
 * it is granted or published; to use it, copy it into the agent's space. A name
 * several eligible snippets share goes to the earliest tier above, the oldest
 * first within a tier. With enforcement off there is one shared workspace and
 * every snippet is visible, as before; a duplicated name then resolves to the
 * oldest snippet instead of to whichever happened to be listed last.
 *
 * @author ginccc
 * @since 6.0.0
 */
@ApplicationScoped
public class PromptSnippetService {

    private static final Logger LOGGER = Logger.getLogger(PromptSnippetService.class);
    private static final String CACHE_KEY = "all_snippets";
    private static final Duration CACHE_TTL = Duration.ofMinutes(5);
    private static final int MAX_COLLISION_WARNINGS = 1000;

    /** Closeness tiers, lower wins. See the class javadoc. */
    private static final int TIER_AGENT_SPACE = 0;
    private static final int TIER_OWNER = 1;
    private static final int TIER_LEGACY = 2;

    /**
     * Tie-break for snippets sharing a name within the same closeness tier: the
     * oldest wins, so creating a same-named snippet later can never take over a
     * name an agent already renders. The resource id breaks a creation-time tie, so
     * every pod makes the same choice.
     */
    private static final Comparator<SnippetEntry> OLDEST_FIRST = Comparator
            .comparing((SnippetEntry entry) -> entry.createdOn() == null ? new Date(Long.MAX_VALUE) : entry.createdOn())
            .thenComparing(SnippetEntry::id);

    private final IPromptSnippetStore snippetStore;
    private final IDocumentDescriptorStore descriptorStore;
    private final WorkspaceSettings workspaceSettings;
    private final Counter cacheHitCounter;
    private final Counter cacheMissCounter;

    /**
     * Single-entry cache holding every snippet together with the descriptor that
     * decides who may use it, and the unscoped {@link #getAll()} view resolved from
     * them. Invalidated on any snippet write and on any sharing change; TTL
     * fallback ensures eventual consistency across nodes.
     */
    private final Cache<String, Loaded> snippetCache;

    /**
     * Per-agent view under enforced workspaces, keyed by agent id. Bounded and
     * expiring like the sibling caches, and cleared together with
     * {@link #snippetCache}.
     */
    private final Cache<String, Map<String, Object>> agentSnippetCache;

    /**
     * Names already reported as ambiguous, so the warning is emitted once per name
     * per process rather than once per render. Bounded: past
     * {@link #MAX_COLLISION_WARNINGS} further collisions go to DEBUG only.
     */
    private final Set<String> collisionsWarned = ConcurrentHashMap.newKeySet();

    @Inject
    public PromptSnippetService(IPromptSnippetStore snippetStore,
            IDocumentDescriptorStore descriptorStore,
            WorkspaceSettings workspaceSettings,
            MeterRegistry meterRegistry) {
        this.snippetStore = snippetStore;
        this.descriptorStore = descriptorStore;
        this.workspaceSettings = workspaceSettings;
        this.cacheHitCounter = meterRegistry.counter("eddi.snippets.cache.hits");
        this.cacheMissCounter = meterRegistry.counter("eddi.snippets.cache.misses");

        this.snippetCache = Caffeine.newBuilder()
                .maximumSize(1)
                .expireAfterWrite(CACHE_TTL)
                .build();
        this.agentSnippetCache = Caffeine.newBuilder()
                .maximumSize(1000)
                .expireAfterWrite(CACHE_TTL)
                .build();
    }

    @PostConstruct
    void warmCache() {
        try {
            getAll(); // Pre-populate cache at startup
        } catch (Exception e) {
            LOGGER.warnv("Failed to warm snippet cache at startup: {0}", e.getMessage());
        }
    }

    /**
     * Get <em>every</em> snippet as a map suitable for injection into the template
     * data. The map keys are snippet names, values are snippet content strings,
     * verbatim.
     * <p>
     * Unscoped: under enforced workspaces this includes other workspaces' snippets.
     * A conversation render must use {@link #getForAgent} instead; this is for
     * callers that make their own access decision (the template preview, which
     * redacts for anyone who does not see everything).
     * <p>
     * Nothing is escaped on the way in, and nothing needs to be — see the class
     * javadoc for why a value reached through this map is never re-parsed.
     *
     * @return unmodifiable map of snippet name → content
     */
    public Map<String, Object> getAll() {
        return load().all();
    }

    /**
     * The snippets a conversation with {@code agentId} may render, as
     * {@code name → content}. See the class javadoc for which snippets qualify and
     * how a shared name is resolved.
     * <p>
     * With workspaces not enforced this is {@link #getAll()}. With them enforced,
     * an agent that has no descriptor, or an unowned one, is a legacy agent and
     * gets the legacy snippets. An agent whose descriptor cannot be read also gets
     * only the legacy snippets for that turn — never another space's, and never
     * nothing, since dropping every snippet would silently strip safety and
     * compliance text from the prompt — and that view is not cached, so the next
     * turn retries.
     *
     * @param agentId
     *            the agent the render is for; {@code null} is treated like an agent
     *            with no descriptor
     * @return unmodifiable map of snippet name → content
     */
    public Map<String, Object> getForAgent(String agentId) {
        if (workspaceSettings == null || !workspaceSettings.isEnforcing()) {
            return getAll();
        }
        String cacheKey = agentId == null ? "" : agentId;
        Map<String, Object> cached = agentSnippetCache.getIfPresent(cacheKey);
        if (cached != null) {
            return cached;
        }

        DocumentDescriptor agentDescriptor = null;
        if (agentId != null && !agentId.isBlank()) {
            try {
                agentDescriptor = descriptorStore.readCurrentDescriptor(agentId);
            } catch (IResourceStore.ResourceNotFoundException e) {
                LOGGER.debugf("No descriptor for agent %s; giving it the legacy prompt snippets", sanitize(agentId));
            } catch (IResourceStore.ResourceStoreException e) {
                LOGGER.warnf("Could not load the descriptor of agent %s to scope its prompt snippets; rendering only the legacy "
                        + "snippets this turn: %s", sanitize(agentId), e.getMessage());
                return resolveScoped(load().entries(), null);
            }
        }

        Map<String, Object> scoped = resolveScoped(load().entries(), agentDescriptor);
        agentSnippetCache.put(cacheKey, scoped);
        return scoped;
    }

    /**
     * Explicitly invalidate the snippet cache. Call when snippets are updated
     * (e.g., from the REST layer) or from tests.
     */
    public void invalidateCache() {
        snippetCache.invalidateAll();
        agentSnippetCache.invalidateAll();
        LOGGER.debug("Snippet cache invalidated");
    }

    /**
     * A grant, revoke, publish or ownership transfer changes which snippets an
     * agent may use — on the snippet, or on the agent itself — so the derived views
     * are dropped immediately on this node rather than when the TTL runs out.
     */
    void onSharingChanged(@Observes SharingChangedEvent event) {
        invalidateCache();
    }

    /**
     * The agent as an access subject. A team-space agent is the team: no personal
     * identity, so its creator's private snippets and user grants are out of reach.
     * A personal-space agent is its owner. A legacy agent is nobody.
     */
    static CallerSpaces agentCaller(DocumentDescriptor agentDescriptor) {
        if (agentDescriptor == null || DescriptorAccess.isUnowned(agentDescriptor)) {
            return CallerSpaces.ANONYMOUS;
        }
        String space = agentSpace(agentDescriptor);
        if (space != null && space.startsWith(Subjects.TEAM_PREFIX)) {
            return new CallerSpaces(Set.of(), Set.of(space), Set.of(space));
        }
        String owner = agentDescriptor.getOwnerId();
        if (!isSet(owner)) {
            // A space but no owner, and not a team: nothing personal to act as.
            return space == null ? CallerSpaces.ANONYMOUS : new CallerSpaces(Set.of(), Set.of(space), Set.of(space));
        }
        String personal = Subjects.personalSpace(owner.trim());
        return new CallerSpaces(Set.of(owner.trim()), Set.of(personal), Set.of(personal));
    }

    private Loaded load() {
        Loaded cached = snippetCache.getIfPresent(CACHE_KEY);
        if (cached != null) {
            cacheHitCounter.increment();
            return cached;
        }

        cacheMissCounter.increment();
        List<SnippetEntry> entries = loadAllSnippets();
        Loaded loaded = new Loaded(entries, resolveUnscoped(entries));
        snippetCache.put(CACHE_KEY, loaded);
        return loaded;
    }

    private List<SnippetEntry> loadAllSnippets() {
        try {
            // Use descriptor store to enumerate all snippet resources
            List<DocumentDescriptor> descriptors = descriptorStore.readDescriptors(
                    "ai.labs.snippet", "", 0, IDescriptorStore.NO_LIMIT, false);

            if (descriptors == null || descriptors.isEmpty()) {
                return Collections.emptyList();
            }

            List<SnippetEntry> result = new ArrayList<>();
            for (DocumentDescriptor descriptor : descriptors) {
                try {
                    URI resourceUri = descriptor.getResource();
                    String id = extractIdFromUri(resourceUri);
                    // Read the CURRENT version. The descriptor's resource URI keeps the
                    // version the snippet was created with, so after an update agents kept
                    // rendering the old content even though the cache had been invalidated.
                    IResourceStore.IResourceId current = snippetStore.getCurrentResourceId(id);
                    Integer version = current != null ? current.getVersion() : extractVersionFromUri(resourceUri);
                    PromptSnippet snippet = snippetStore.read(id, version);
                    if (snippet != null && snippet.getName() != null && snippet.getContent() != null) {
                        // Stored RAW — see the class javadoc. A snippet reaches a prompt as a
                        // template DATA VALUE, and Qute does not re-parse what an expression
                        // resolved to, so its markers are already literal. Wrapping it in an
                        // unparsed block only added the block's own delimiters to the prompt.
                        result.add(new SnippetEntry(id, snippet.getName(), snippet.getContent(), descriptor.getCreatedOn(), descriptor));
                    }
                } catch (IResourceStore.ResourceNotFoundException e) {
                    LOGGER.debugv("Snippet descriptor references missing resource: {0}", descriptor.getResource());
                }
            }

            LOGGER.debugv("Loaded {0} prompt snippets into cache", result.size());
            return Collections.unmodifiableList(result);

        } catch (IResourceStore.ResourceStoreException | IResourceStore.ResourceNotFoundException e) {
            LOGGER.errorv("Failed to load prompt snippets: {0}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /** Every entry is eligible; a shared name goes to the oldest. */
    private Map<String, Object> resolveUnscoped(List<SnippetEntry> entries) {
        return pick(entries, entry -> TIER_AGENT_SPACE);
    }

    /**
     * Only the entries the agent's own side controls are eligible — see the class
     * javadoc.
     *
     * @param agentDescriptor
     *            the agent's descriptor, or {@code null} for a legacy agent (or one
     *            whose descriptor could not be read)
     */
    private Map<String, Object> resolveScoped(List<SnippetEntry> entries, DocumentDescriptor agentDescriptor) {
        CallerSpaces caller = agentCaller(agentDescriptor);
        String agentSpace = agentDescriptor == null ? null : agentSpace(agentDescriptor);
        return pick(entries, entry -> tier(entry.descriptor(), agentSpace, caller));
    }

    /**
     * The closeness tier of a snippet for the agent, or {@code -1} when the agent
     * must not see it.
     */
    private static int tier(DocumentDescriptor snippet, String agentSpace, CallerSpaces caller) {
        if (DescriptorAccess.isUnowned(snippet)) {
            return TIER_LEGACY;
        }
        // Access is still required: a snippet filed in the agent's space but kept
        // private by a teammate is not the team's to use. admitLegacy=false because
        // legacy snippets were handled above.
        AccessLevel level = DescriptorAccess.effectiveLevel(snippet, caller, false);
        if (level == null || !level.includes(AccessLevel.USE)) {
            return -1;
        }
        if (agentSpace != null && agentSpace.equals(snippet.getSpaceId())) {
            return TIER_AGENT_SPACE;
        }
        if (caller.isSelf(snippet.getOwnerId())) {
            return TIER_OWNER;
        }
        // Reachable only through a grant or a publish from elsewhere: a push, not
        // something this agent's side chose. Never injected.
        return -1;
    }

    private Map<String, Object> pick(List<SnippetEntry> entries, TierFunction tierOf) {
        if (entries.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, SnippetEntry> chosen = new LinkedHashMap<>();
        Map<String, Integer> chosenTier = new LinkedHashMap<>();
        for (SnippetEntry entry : entries) {
            int tier = tierOf.tier(entry);
            if (tier < 0) {
                continue;
            }
            SnippetEntry incumbent = chosen.get(entry.name());
            if (incumbent == null) {
                chosen.put(entry.name(), entry);
                chosenTier.put(entry.name(), tier);
                continue;
            }
            int incumbentTier = chosenTier.get(entry.name());
            boolean replaces = tier < incumbentTier || (tier == incumbentTier && OLDEST_FIRST.compare(entry, incumbent) < 0);
            reportCollision(entry.name(), replaces ? entry : incumbent, replaces ? incumbent : entry);
            if (replaces) {
                chosen.put(entry.name(), entry);
                chosenTier.put(entry.name(), tier);
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        chosen.forEach((name, entry) -> result.put(name, entry.content()));
        return Collections.unmodifiableMap(result);
    }

    private static String agentSpace(DocumentDescriptor agentDescriptor) {
        String space = agentDescriptor.getSpaceId();
        return isSet(space) && !Subjects.LEGACY.equals(space) ? space : null;
    }

    private void reportCollision(String name, SnippetEntry winner, SnippetEntry loser) {
        if (collisionsWarned.size() < MAX_COLLISION_WARNINGS && collisionsWarned.add(name)) {
            LOGGER.warnf("Several prompt snippets are named '%s'; it resolves to snippet %s rather than %s. "
                    + "Rename one of them to make the choice explicit. Reported once per name.", sanitize(name), sanitize(winner.id()),
                    sanitize(loser.id()));
        } else {
            LOGGER.debugf("Prompt snippet name '%s' resolved to %s over %s", sanitize(name), sanitize(winner.id()), sanitize(loser.id()));
        }
    }

    private static boolean isSet(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * Extract the resource ID from a resource URI like
     * {@code eddi://ai.labs.snippet/snippetstore/snippets/<id>?version=1}.
     */
    private static String extractIdFromUri(URI resourceUri) {
        if (resourceUri == null)
            return "";
        String path = resourceUri.getPath();
        if (path == null)
            return "";
        int lastSlash = path.lastIndexOf('/');
        return lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
    }

    /**
     * Extract the version number from a resource URI query string. Falls back to
     * version 1 if not present.
     */
    private static Integer extractVersionFromUri(URI resourceUri) {
        if (resourceUri == null)
            return 1;
        String query = resourceUri.getQuery();
        if (query != null) {
            for (String param : query.split("&")) {
                if (param.startsWith("version=")) {
                    try {
                        return Integer.parseInt(param.substring("version=".length()));
                    } catch (NumberFormatException e) {
                        return 1;
                    }
                }
            }
        }
        return 1;
    }

    @FunctionalInterface
    private interface TierFunction {
        int tier(SnippetEntry entry);
    }

    /** One loaded snippet with the descriptor that decides who may use it. */
    private record SnippetEntry(String id, String name, String content, Date createdOn, DocumentDescriptor descriptor) {
    }

    /** The loaded entries and the unscoped view resolved from them. */
    private record Loaded(List<SnippetEntry> entries, Map<String, Object> all) {
    }
}
