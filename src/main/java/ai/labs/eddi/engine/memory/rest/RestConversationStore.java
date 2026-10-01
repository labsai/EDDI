/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.memory.rest;

import ai.labs.eddi.configs.agents.IRestAgentStore;
import ai.labs.eddi.configs.migration.V6RenameMigration;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.descriptors.model.AccessLevel;
import ai.labs.eddi.configs.properties.IUserMemoryStore;
import ai.labs.eddi.datastore.IResourceFilter.NotMatching;
import ai.labs.eddi.datastore.IResourceFilter.QueryFilter;
import ai.labs.eddi.datastore.IResourceFilter.QueryFilters;
import ai.labs.eddi.datastore.IResourceStorage;
import ai.labs.eddi.datastore.IResourceStore.ResourceModifiedException;
import ai.labs.eddi.datastore.IResourceStore.ResourceNotFoundException;
import ai.labs.eddi.datastore.IResourceStore.ResourceStoreException;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.attachments.IAttachmentStore;
import ai.labs.eddi.secrets.AutoVaultedSecrets;
import ai.labs.eddi.engine.memory.ConversationMemory;
import ai.labs.eddi.engine.memory.ConversationMemoryUtilities;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.memory.descriptor.IConversationDescriptorStore;
import ai.labs.eddi.engine.memory.descriptor.model.ConversationDescriptor;
import ai.labs.eddi.engine.memory.model.ConversationListingSummary;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot;

import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.memory.model.ConversationStatus;
import ai.labs.eddi.engine.runtime.IRuntime;
import ai.labs.eddi.engine.runtime.ThreadContext;
import ai.labs.eddi.engine.security.ConversationAccessGuard;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import static ai.labs.eddi.engine.exception.SneakyThrow.sneakyThrow;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;

import static ai.labs.eddi.engine.memory.ConversationMemoryUtilities.convertSimpleConversationMemory;
import static ai.labs.eddi.engine.memory.ConversationMemoryUtilities.redactRawPendingToolCallsForRead;
import static ai.labs.eddi.engine.security.spaces.Subjects.escapeRegex;
import static ai.labs.eddi.utils.LogSanitizer.sanitize;
import static ai.labs.eddi.utils.RestUtilities.createURI;
import static ai.labs.eddi.utils.RestUtilities.extractResourceId;
import static ai.labs.eddi.utils.RuntimeUtilities.checkNotNull;
import static ai.labs.eddi.utils.RuntimeUtilities.isNullOrEmpty;
import static java.lang.String.format;

/**
 * @author ginccc
 */

@ApplicationScoped
public class RestConversationStore implements IRestConversationStore {
    public static final String DESCRIPTOR_TYPE = "ai.labs.conversation";

    /**
     * Conversation descriptors are written once and then updated in place, so they
     * never leave version 0 — every other read in this class hard-codes it too.
     */
    private static final int CONVERSATION_DESCRIPTOR_VERSION = 0;

    /**
     * Upper bound on how many descriptors an owner-filtered listing will scan
     * before giving up, so a caller who owns few/none of a large shared store
     * cannot turn one list request into a full-collection scan. This is the single
     * owner-scan budget in the system — the MCP {@code list_conversations} tool
     * delegates to this endpoint rather than scanning itself. Admins /
     * auth-disabled callers are not filtered and never reach this bound.
     */
    private static final int MAX_OWNER_SCAN = 500;

    /** Smallest descriptor batch of a listing past its first page. */
    private static final int SCAN_BATCH = 100;

    /** Largest descriptor batch, which a deep page reads its earlier rows in. */
    private static final int MAX_SCAN_BATCH = 1_000;

    /**
     * Deepest result a listing serves: {@code index * limit} above this is refused
     * with a 400, for every caller. A page counts off every match before it, so
     * without a ceiling one request with a huge {@code index} reads the whole
     * descriptor collection — and an admin, or anyone when authorization is
     * disabled, never reaches {@link #MAX_OWNER_SCAN}. The same ceiling the storage
     * layer puts on one query's results.
     */
    static final int MAX_RESULT_OFFSET = IResourceStorage.MAX_RESULT_LIMIT;

    /** Descriptor fields the listing filters on in the query. */
    private static final String FIELD_AGENT_RESOURCE = "agentResource";
    private static final String FIELD_USER_ID = "userId";
    private static final String FIELD_VIEW_STATE = "viewState";

    /**
     * Matches a field holding anything but whitespace — so a {@link NotMatching} of
     * it selects a field that is absent, empty or blank, which is how the listing
     * treats "no owner" and "no agent" ({@code isNullOrEmpty}, and {@code isBlank}
     * in the access guard).
     */
    private static final String NOT_BLANK = "\\S";

    /**
     * Smallest age (in days) the deployment-wide retention sweep accepts. Zero used
     * to be legal and meant "every ended conversation, regardless of age" — one
     * query parameter away from wiping the deployment. Values below this bound are
     * rejected on the REST path and treated as "retention disabled" by the
     * scheduled sweep.
     */
    static final int MIN_RETENTION_DAYS = 1;

    /**
     * Fallback actor recorded when deleting a conversation ends it and there is no
     * named caller — the HITL cancellation audit carries the actor if the
     * conversation was paused (G4). A named caller is recorded as themselves.
     */
    static final String DELETE_ACTOR = "system:delete";

    /**
     * Fallback actor for the agent-scoped bulk end ({@code POST …/end}, undeploy)
     * when there is no named caller; a named caller is recorded as themselves.
     */
    static final String BULK_END_ACTOR = "system:admin-end";

    private final IDocumentDescriptorStore documentDescriptorStore;
    private final IConversationDescriptorStore conversationDescriptorStore;
    private final IConversationMemoryStore conversationMemoryStore;
    private final IConversationService conversationService;
    private final IUserMemoryStore userMemoryStore;
    private final IRuntime runtime;
    private final ConversationAccessGuard conversationAccessGuard;
    private final ResourceAccessGuard resourceAccessGuard;
    private final Integer deleteEndedConversationsOnceOlderThanDays;
    private final Integer deleteMemoriesOlderThanDays;
    private final Instance<IAttachmentStore> attachmentStorageInstance;

    // Field-injected per the AGENTS.md metrics pattern; the SimpleMeterRegistry
    // default keeps it non-null in unit tests that construct this bean directly
    // (CDI overwrites it with the real registry in production).
    @Inject
    MeterRegistry meterRegistry = new SimpleMeterRegistry();

    /**
     * Optional so the unit tests that construct this store directly need no vault.
     */
    @Inject
    Instance<AutoVaultedSecrets> autoVaultedSecretsInstance;

    /**
     * Optional for the same reason; absent means no EDDI 5 migration is in play.
     */
    @Inject
    Instance<V6RenameMigration> v6RenameMigrationInstance;

    /**
     * The operator's confirmation that 6.x's retention may delete ended
     * conversations of a database that came from EDDI 5; see
     * {@link #retentionHeldForV5Migration()}.
     */
    @ConfigProperty(name = "eddi.migration.v6-rename.retention-confirmed", defaultValue = "false")
    boolean retentionConfirmed;

    private static final Logger log = Logger.getLogger(RestConversationStore.class);

    @Inject
    // @formatter:off
    public RestConversationStore(
            IDocumentDescriptorStore documentDescriptorStore,
            IConversationDescriptorStore conversationDescriptorStore,
            IConversationMemoryStore conversationMemoryStore,
            IConversationService conversationService,
            IUserMemoryStore userMemoryStore,
            IRuntime runtime,
            ConversationAccessGuard conversationAccessGuard,
            ResourceAccessGuard resourceAccessGuard,
            @ConfigProperty(name = "eddi.conversations.deleteEndedConversationsOnceOlderThanDays")
            Integer deleteEndedConversationsOnceOlderThanDays,
            @ConfigProperty(name = "eddi.usermemories.deleteOlderThanDays")
            Integer deleteMemoriesOlderThanDays,
            Instance<IAttachmentStore> attachmentStorageInstance) {
    // @formatter:on

        this.documentDescriptorStore = documentDescriptorStore;
        this.conversationDescriptorStore = conversationDescriptorStore;
        this.conversationMemoryStore = conversationMemoryStore;
        this.conversationService = conversationService;
        this.userMemoryStore = userMemoryStore;
        this.runtime = runtime;
        this.conversationAccessGuard = conversationAccessGuard;
        this.resourceAccessGuard = resourceAccessGuard;
        this.deleteEndedConversationsOnceOlderThanDays = deleteEndedConversationsOnceOlderThanDays;
        this.deleteMemoriesOlderThanDays = deleteMemoriesOlderThanDays;
        this.attachmentStorageInstance = attachmentStorageInstance;
    }

    @Override
    public List<ConversationDescriptor> readConversationDescriptors(Integer index, Integer limit, String filter, String conversationId,
                                                                    String agentId, Integer agentVersion, ConversationState conversationState,
                                                                    ConversationDescriptor.ViewState viewState) {
        // Sanitize pagination parameters to prevent overflow (CodeQL: integer-overflow)
        if (index == null || index < 0) {
            index = 0;
        }
        if (limit == null || limit < 1) {
            limit = 20;
        }
        if (limit > 100) {
            limit = 100;
        }
        if ((long) index * limit > MAX_RESULT_OFFSET) {
            throw new BadRequestException(format("index * limit must not exceed %d. To reach older conversations, narrow the "
                    + "listing with agentId, conversationState or filter.", MAX_RESULT_OFFSET));
        }

        // `index` is a page of RESULTS: page n is the (n*limit)th to the
        // ((n+1)*limit - 1)th conversation that passes every filter. It used to be a
        // page of DESCRIPTORS, read from descriptor page `index` onward and filtered
        // afterwards, so a filtered page could run past `limit` (it added whole
        // descriptor pages) and page n+1 repeated rows page n had already read from
        // the descriptor pages after its own.
        //
        // The filters that are plain descriptor fields — agent, view state and, for
        // a non-admin, owner — are pushed into the query (listingRestrictions), so
        // the descriptors read are mostly results. The rest can only be decided from
        // the conversation memory: the conversation state, whether the memory still
        // exists (orphans), and the owner or agent of a legacy descriptor that does
        // not record them. Those are checked here, and the matches that belong to
        // earlier pages are counted off.
        //
        // The cost per descriptor page is two queries: the descriptor page, and one
        // projected read of the candidates' listing fields (loadListingSummaries) —
        // no conversation is loaded in full, and an agent's name is read once per
        // listing. Page n also reads past the rows of the pages before it, in batches
        // of up to MAX_SCAN_BATCH (see scanSize), and never past MAX_RESULT_OFFSET
        // of them — not (n+1)*limit document loads.
        //
        // Owner-scoping: a non-admin caller may only enumerate their own
        // conversations. Admins (and any caller when authorization is disabled) see
        // all — resolved once, up front, so the per-row check is skipped entirely on
        // that path. The scan is bounded by MAX_OWNER_SCAN so a caller who owns
        // few/none of a large shared store cannot force a full-collection scan. The
        // budget counts the descriptors this page examined, not the matches it
        // counted off for earlier pages: those are the caller's own conversations,
        // and charging them would put a caller's older conversations out of reach.
        final boolean seesAllConversations = conversationAccessGuard.seesAllConversations();
        // Review decisions per agent version, made once per listing rather than once
        // per row: the answer depends only on the version and on the caller.
        final Map<URI, Boolean> reviewable = new HashMap<>();
        final List<QueryFilters> restrictions = listingRestrictions(seesAllConversations, agentId, viewState);
        final long matchesToSkip = (long) index * limit;
        // Descriptors are read in pages of `limit` for the first result page, the one
        // almost every request asks for. A later page has earlier pages' rows to count
        // off first, so it reads in batches sized to that work (SCAN_BATCH to
        // MAX_SCAN_BATCH). Every batch makes the database walk past the batches before
        // it again (skip = batch * size), so small batches made a deep page quadratic;
        // at the MAX_RESULT_OFFSET ceiling this is about 11 batches, not 101. One
        // listing keeps one size, which is all the descriptor store's index arithmetic
        // needs.
        final int scanSize = index == 0
                ? limit
                : (int) Math.min(MAX_SCAN_BATCH, Math.max(SCAN_BATCH, matchesToSkip + limit));

        try {
            List<ConversationDescriptor> conversationDescriptors;
            List<ConversationDescriptor> retConversationDescriptors = new LinkedList<>();
            // Agent display names, read once per agent version rather than once per row.
            Map<String, String> agentNames = new HashMap<>();
            String textFilter = filter;
            int descriptorPage = 0;
            long skippedMatches = 0;
            long scannedDescriptors = 0;
            int orphanedDescriptors = 0;
            boolean pageFull = false;

            do {
                conversationDescriptors = readConversationDescriptors(descriptorPage, scanSize, textFilter, restrictions);
                if (conversationDescriptors.isEmpty() && descriptorPage == 0 && !isNullOrEmpty(textFilter)
                        && searchMatchesNothing(textFilter, restrictions)) {
                    // A search that matches no conversation at all lists everything
                    // instead — decided once, on the first descriptor page, so every
                    // result page of the listing agrees on which rows it is paging through.
                    textFilter = null;
                    conversationDescriptors = readConversationDescriptors(descriptorPage, scanSize, null, restrictions);
                }

                // 1. What the descriptor alone decides — no conversation is read for a
                // row this rejects.
                List<ConversationDescriptor> candidates = new ArrayList<>();
                List<String> candidateIds = new ArrayList<>();
                for (var conversationDescriptor : conversationDescriptors) {
                    // The scan budget is checked per batch (the loop condition), never
                    // mid-batch: which rows of a batch are counted off for earlier pages —
                    // and so exempt from the budget — is only known after the batch's
                    // summary read, and stopping mid-batch would let the next batch start
                    // after rows nobody examined. A batch may therefore run past the
                    // budget by its own size; its foreign rows cost no conversation read.
                    scannedDescriptors++;
                    String candidateId = recordedOwnerAdmits(conversationDescriptor, seesAllConversations, agentId, reviewable);
                    if (candidateId != null) {
                        candidates.add(conversationDescriptor);
                        candidateIds.add(candidateId);
                    }
                }

                // 2. One projected read for the page's candidates, instead of a full
                // conversation load per row.
                Set<String> unreadable = new HashSet<>();
                Map<String, ConversationListingSummary> summaries = readListingSummaries(candidateIds, unreadable);

                // 3. What only the conversation decides, row by row in listing order.
                for (int i = 0; i < candidates.size(); i++) {
                    var conversationDescriptor = candidates.get(i);
                    var summary = summaries.get(candidateIds.get(i));
                    if (summary == null && unreadable.contains(candidateIds.get(i))) {
                        continue;
                    }
                    if (summary == null) {
                        // A descriptor whose conversation memory is gone is an orphan:
                        // there is no conversation to open, so it is not listed. 5.x left
                        // many of these behind, and once the v6 rename gives them an
                        // agentResource they would otherwise surface in by-agent listings.
                        orphanedDescriptors++;
                        continue;
                    }
                    if (!conversationAdmits(conversationDescriptor, summary, seesAllConversations, agentId, agentVersion,
                            conversationState, viewState)) {
                        continue;
                    }

                    if (skippedMatches < matchesToSkip) {
                        // A result of an earlier page — counted off, and not charged
                        // against the scan budget.
                        skippedMatches++;
                        continue;
                    }

                    fillAgentName(conversationDescriptor, summary, agentNames);
                    retConversationDescriptors.add(conversationDescriptor);
                    if (retConversationDescriptors.size() >= limit) {
                        // Stop at `limit` exactly, mid-page: the rest of this descriptor
                        // page belongs to the next result page.
                        pageFull = true;
                        break;
                    }
                }

                if (descriptorPage < Integer.MAX_VALUE) {
                    descriptorPage++;
                } else {
                    break; // prevent integer overflow
                }
                // Bound the owner-filtered back-fill: stop once the scan budget is spent
                // (admins/auth-disabled are never filtered, so they page only as far as
                // filling `limit` requires and never hit this).
            } while (!pageFull && !conversationDescriptors.isEmpty()
                    && (seesAllConversations || scannedDescriptors - skippedMatches < MAX_OWNER_SCAN));

            // Observability: a non-admin listing that stopped on the scan budget with
            // fewer than `limit` results may have owned conversations beyond what was
            // scanned (the List return type can't signal that truncation to the
            // caller). Count it so a persistently-truncated user is not invisible.
            if (!seesAllConversations && retConversationDescriptors.size() < limit
                    && scannedDescriptors - skippedMatches >= MAX_OWNER_SCAN) {
                meterRegistry.counter("eddi.conversations.listing.owner_scan_exhausted").increment();
            }

            if (orphanedDescriptors > 0) {
                meterRegistry.counter("eddi.conversations.listing.orphaned_descriptors").increment(orphanedDescriptors);
                log.debug(format("Left %d orphaned conversation descriptor(s) out of the listing.", orphanedDescriptors));
            }

            return retConversationDescriptors;

        } catch (ResourceStoreException | ResourceNotFoundException e) {
            throw sneakyThrow(e);
        }
    }

    /**
     * The checks the descriptor alone can make: it names a conversation, and — for
     * a non-admin — the owner it records is the caller, or the caller reviews its
     * agent. Every conversation since v5.1.6 records its owner on the descriptor,
     * so a foreign row is rejected here without its conversation ever being read; a
     * legacy row with no recorded owner is decided in {@link #conversationAdmits},
     * once the conversation supplies its owner. A fully unowned (null both ways)
     * conversation stays visible, matching OwnershipValidator.requireOwnerOrAdmin.
     *
     * @return the conversation's id, or {@code null} when the row is not listed
     */
    private String recordedOwnerAdmits(ConversationDescriptor conversationDescriptor, boolean seesAllConversations, String agentId,
                                       Map<URI, Boolean> reviewable) {
        try {
            URI resourceUri = conversationDescriptor.getResource();
            var conversationResourceId = extractResourceId(resourceUri);
            if (conversationResourceId == null || conversationResourceId.getId() == null) {
                log.warn(format("conversationResourceId was null, this should never happen. (%s)", resourceUri));
                return null;
            }

            String recordedOwner = conversationDescriptor.getUserId();
            if (!seesAllConversations && !isNullOrEmpty(recordedOwner)
                    && !conversationAccessGuard.canAccessConversation(recordedOwner)
                    && !mayReview(conversationDescriptor.getAgentResource(), agentId, reviewable)) {
                return null;
            }
            return conversationResourceId.getId();
        } catch (Exception e) {
            // Skip individual corrupted descriptors gracefully
            log.debug(format("Skipping descriptor due to error: %s", sanitize(e.getMessage())));
            return null;
        }
    }

    /**
     * The listing fields of a page's candidates, in one read. If that read fails,
     * the ids are read one at a time, so one conversation the store cannot read
     * costs only its own row — not the page, and not the listing. Such an id is
     * added to {@code unreadable}, so it is not mistaken for an orphan.
     */
    private Map<String, ConversationListingSummary> readListingSummaries(List<String> conversationIds, Set<String> unreadable) {
        if (conversationIds.isEmpty()) {
            return Map.of();
        }
        try {
            return conversationMemoryStore.loadListingSummaries(conversationIds);
        } catch (Exception batchFailure) {
            Map<String, ConversationListingSummary> summaries = new HashMap<>();
            for (String conversationId : conversationIds) {
                try {
                    summaries.putAll(conversationMemoryStore.loadListingSummaries(List.of(conversationId)));
                } catch (Exception e) {
                    unreadable.add(conversationId);
                    log.warn(format("Skipping descriptor due to error: %s", sanitize(e.getMessage())));
                }
            }
            return summaries;
        }
    }

    /**
     * Fills the descriptor from its conversation and makes the checks only the
     * conversation can answer: the owner of a legacy descriptor that records none,
     * the agent of one that names none, and the conversation's state — which lives
     * in the conversation, not on the descriptor, so it cannot be pushed into the
     * query. The agent and view-state checks repeat what the query already narrowed
     * to; they stay the authority.
     */
    private boolean conversationAdmits(ConversationDescriptor conversationDescriptor, ConversationListingSummary summary,
                                       boolean seesAllConversations, String agentId, Integer agentVersion,
                                       ConversationState conversationState, ConversationDescriptor.ViewState viewState) {
        try {
            boolean ownerRecorded = !isNullOrEmpty(conversationDescriptor.getUserId());
            if (!ownerRecorded) {
                // fallback for older conversations pre v5.1.6
                conversationDescriptor.setUserId(summary.userId());
            }
            conversationDescriptor.setEnvironment(summary.environment());
            conversationDescriptor.setConversationStepSize(summary.conversationStepCount());
            conversationDescriptor.setConversationState(summary.conversationState());
            if (conversationDescriptor.getAgentResource() == null && !isNullOrEmpty(summary.agentId())) {
                // A descriptor an earlier 6.x rewrote without its v5 botResource (see
                // V6RenameMigration's backfill) names no agent; the conversation does.
                // Without this it is missing from every per-agent listing.
                conversationDescriptor.setAgentResource(summary.agentVersion() == null
                        ? createURI(IRestAgentStore.resourceURI, summary.agentId())
                        : createURI(IRestAgentStore.resourceURI, summary.agentId(), IRestAgentStore.versionQueryParam,
                                summary.agentVersion()));
            }

            // Legacy safety net: the descriptor recorded no owner; the conversation
            // has now supplied it (pre-v5.1.6 fallback), so check it.
            if (!seesAllConversations && !ownerRecorded
                    && !conversationAccessGuard.canAccessConversation(conversationDescriptor.getUserId())) {
                return false;
            }

            // Agent filtering uses the agentResource URI (which contains
            // the agent's ID), NOT the conversation's resource URI.
            if (!isNullOrEmpty(agentId)) {
                URI agentResourceUri = conversationDescriptor.getAgentResource();
                var agentResourceId = agentResourceUri != null ? extractResourceId(agentResourceUri) : null;
                if (agentResourceId == null || !agentId.equals(agentResourceId.getId())) {
                    return false;
                }

                if (!isNullOrEmpty(agentVersion) && !agentVersion.equals(agentResourceId.getVersion())) {
                    return false;
                }
            }

            if (!isNullOrEmpty(conversationState) && !conversationState.equals(conversationDescriptor.getConversationState())) {
                return false;
            }

            return isNullOrEmpty(viewState) || viewState.equals(conversationDescriptor.getViewState());
        } catch (Exception e) {
            // Skip individual corrupted descriptors gracefully
            log.debug(format("Skipping descriptor due to error: %s", sanitize(e.getMessage())));
            return false;
        }
    }

    /**
     * Names the agent of a returned row whose descriptor carries no agent name —
     * only rows that are returned, and each agent version once per listing.
     */
    private void fillAgentName(ConversationDescriptor conversationDescriptor, ConversationListingSummary summary,
                               Map<String, String> agentNames) {
        if (!isNullOrEmpty(conversationDescriptor.getAgentName()) || isNullOrEmpty(summary.agentId())) {
            return;
        }
        String key = summary.agentId() + "?version=" + summary.agentVersion();
        String agentName = agentNames.computeIfAbsent(key, ignored -> {
            try {
                String name = documentDescriptorStore.readDescriptor(summary.agentId(), summary.agentVersion()).getName();
                return name == null ? "" : name;
            } catch (Exception e) {
                log.warn(format("Resource referenced in descriptor does not exist (anymore) [%s, %s]. Ignoring this resource.",
                        sanitize(summary.agentId()), summary.agentVersion()));
                return "";
            }
        });
        if (!agentName.isEmpty()) {
            conversationDescriptor.setAgentName(agentName);
        }
    }

    /**
     * The listing filters that are plain descriptor fields, as query groups — so
     * the descriptor store pages through candidates rather than through every
     * conversation. Each group admits a superset of what
     * {@link #conversationAdmits} accepts, never less: that check still runs on
     * every row and stays the authority.
     * <ul>
     * <li><b>Agent</b> — the descriptor names this agent, or names no agent at all:
     * a descriptor an earlier 6.x rewrote without its agent gets it from the
     * conversation in {@link #conversationAdmits}. The version is left to the
     * per-row check.</li>
     * <li><b>Owner</b> (non-admins) — the caller, or no recorded owner (a
     * pre-v5.1.6 conversation, whose owner comes from its memory). Not pushed when
     * an agent is named: a reviewer of that agent may list conversations they do
     * not own (see {@link #mayReview}), and the agent group narrows the query
     * instead.</li>
     * <li><b>View state</b> — equality; a descriptor without one never
     * matched.</li>
     * </ul>
     * The conversation state is not here: it is read from the conversation memory,
     * not from the descriptor.
     */
    private List<QueryFilters> listingRestrictions(boolean seesAllConversations, String agentId,
                                                   ConversationDescriptor.ViewState viewState) {
        List<QueryFilters> restrictions = new LinkedList<>();
        if (!isNullOrEmpty(agentId)) {
            restrictions.add(new QueryFilters(QueryFilters.ConnectingType.OR, List.of(
                    new QueryFilter(FIELD_AGENT_RESOURCE, "/" + escapeRegex(agentId) + "(\\?|$)"),
                    new QueryFilter(FIELD_AGENT_RESOURCE, new NotMatching(NOT_BLANK)))));
        } else if (!seesAllConversations) {
            String caller = conversationAccessGuard.callerActor(null);
            if (caller != null) {
                restrictions.add(new QueryFilters(QueryFilters.ConnectingType.OR, List.of(
                        QueryFilter.exact(FIELD_USER_ID, caller),
                        new QueryFilter(FIELD_USER_ID, new NotMatching(NOT_BLANK)))));
            }
        }
        if (viewState != null) {
            restrictions.add(new QueryFilters(List.of(QueryFilter.exact(FIELD_VIEW_STATE, viewState.name()))));
        }
        return restrictions;
    }

    /**
     * Whether the search finds no conversation at all — the only case in which the
     * listing drops it and lists everything. The listing's own filters are pushed
     * into its query, so its first page also comes back empty when the search does
     * match, only outside them ("Billing" within another agent's conversations);
     * that listing is empty, as it was before the filters moved into the query.
     */
    private boolean searchMatchesNothing(String textFilter, List<QueryFilters> restrictions)
            throws ResourceStoreException, ResourceNotFoundException {
        return restrictions.isEmpty() || readConversationDescriptors(0, 1, textFilter, List.of()).isEmpty();
    }

    private List<ConversationDescriptor> readConversationDescriptors(Integer index, Integer limit, String filter,
                                                                     List<QueryFilters> restrictions)
            throws ResourceStoreException, ResourceNotFoundException {

        return conversationDescriptorStore.readDescriptors(DESCRIPTOR_TYPE, filter, index, limit, false, restrictions);
    }

    /**
     * Whether a conversation the caller does not own may still be listed because
     * they review its agent. Only when the listing asks for one agent: a reviewer
     * browsing "all conversations" would otherwise have every agent's review
     * setting checked for every row of the store.
     */
    private boolean mayReview(URI agentResource, String agentIdFilter, Map<URI, Boolean> cache) {
        if (isNullOrEmpty(agentIdFilter) || agentResource == null) {
            return false;
        }
        var resourceId = extractResourceId(agentResource);
        if (resourceId == null || !agentIdFilter.equals(resourceId.getId())) {
            return false;
        }
        return cache.computeIfAbsent(agentResource, conversationAccessGuard::canReview);
    }

    @Override
    public ConversationMemorySnapshot readRawConversationLog(String conversationId) {
        checkNotNull(conversationId, "conversationId");
        // Owner-or-admin, the same gate RestAgentEngine/RestAttachmentUpload apply.
        // Without it any authenticated caller could read any conversation by id — the
        // raw surface returns the full memory document, properties included. NOT
        // opened to conversation review: those properties include the person's
        // long-term memory, facts from other conversations and other agents, which a
        // maintainer reviewing this agent has no business reading. Reviewers read the
        // simple log below, which shows them the dialogue only.
        conversationAccessGuard.requireConversationOwner(conversationId);

        try {
            // Project the pending tool-call batch down to names-only before returning:
            // this generic raw-read surface is reachable by any authenticated caller
            // and must NOT leak the unredacted tool arguments or the frozen LLM
            // transcript of a paused conversation — those stay behind the approver-only
            // detail=full gate. Mirrors fix #4's confinement on the Simple surface.
            // requireSnapshot, not the raw load: a missing conversation returned null
            // here, which JAX-RS renders as 204 No Content — indistinguishable from a
            // conversation that exists and happens to be empty.
            return redactRawPendingToolCallsForRead(requireSnapshot(conversationId));
        } catch (ResourceStoreException | ResourceNotFoundException e) {
            throw sneakyThrow(e);
        }
    }

    @Override
    public SimpleConversationMemorySnapshot readSimpleConversationLog(String conversationId, Boolean returnDetailed, Boolean returnCurrentStepOnly,
                                                                      List<String> returningFields) {
        checkNotNull(conversationId, "conversationId");
        checkNotNull(returnDetailed, "returnDetailed");
        checkNotNull(returnCurrentStepOnly, "returnCurrentStepOnly");
        var access = conversationAccessGuard.requireConversationRead(conversationId);
        boolean review = access != null && access.review();

        try {
            // A reviewing maintainer sees what was said, not what EDDI holds about the
            // person: no detailed view (model traces, raw tool data), and none of the
            // conversation's properties, which carry the user's long-term memory.
            var snapshot = convertSimpleConversationMemory(requireSnapshot(conversationId), review ? false : returnDetailed,
                    returnCurrentStepOnly);
            if (review) {
                snapshot.setConversationProperties(null);
            }
            return snapshot;

        } catch (ResourceStoreException | ResourceNotFoundException e) {
            throw sneakyThrow(e);
        }
    }

    /**
     * The snapshot, or a 404 — never a {@code null} for the caller to dereference.
     *
     * <p>
     * {@code loadConversationMemorySnapshot} answers {@code null} for a
     * conversation that is not there, and the read paths went straight on to call
     * {@code getEnvironment()} on it. So a deleted or mistyped conversation id
     * produced a {@link NullPointerException}, which reached the client as
     * {@code 500 Internal Server Error} plus an error id — from endpoints whose own
     * {@code @APIResponse} promised a 404, and which the troubleshooting
     * documentation tells people to call precisely when something has already gone
     * wrong.
     * </p>
     *
     * <p>
     * Throws the checked {@code ResourceNotFoundException} rather than
     * {@code ConversationNotFoundException}, which {@code ConversationService}'s
     * twin uses: both read endpoints here already declare it on
     * {@link IRestConversationStore}, so this is the contract they publish. Both
     * map to 404.
     * </p>
     */
    private ConversationMemorySnapshot requireSnapshot(String conversationId)
            throws ResourceStoreException, ResourceNotFoundException {
        var snapshot = conversationMemoryStore.loadConversationMemorySnapshot(conversationId);
        if (snapshot == null) {
            throw new ResourceNotFoundException(
                    String.format("No conversation found! (conversationId=%s)", sanitize(conversationId)));
        }
        return snapshot;
    }

    @Override
    public void deleteConversationLog(String conversationId, Boolean deletePermanently)
            throws ResourceStoreException, ResourceNotFoundException {
        checkNotNull(conversationId, "conversationId");
        // Deletion is irreversible, so the gate runs before anything is touched —
        // the soft-delete path included, which still removes the conversation from
        // every listing. Strict variant: a legacy conversation with no recorded
        // owner is refused to a non-admin here, rather than deletable by any token.
        // A pre-v5.1.6 descriptor without a userId resolves its owner from the
        // snapshot (the same fallback the listing uses), so
        // the recorded owner can still delete their own legacy conversation.
        conversationAccessGuard.requireConversationOwnerStrict(conversationId, id -> {
            var snapshot = conversationMemoryStore.loadConversationMemorySnapshot(id);
            return snapshot != null ? snapshot.getUserId() : null;
        });

        if (deletePermanently) {
            // If the conversation is a live pending approval, resolve the HITL state
            // BEFORE removing the document: endConversation disarms the armed
            // timeout schedule (otherwise it fires later against a deleted
            // conversation, logs "Conversation not found", and leaves a dead
            // schedule row), clears the bookmark, writes the hitl.approval
            // cancellation audit, and invalidates the cached AWAITING_HUMAN state
            // (which getConversationState would otherwise keep serving for a
            // nonexistent conversation until TTL).
            try {
                if (conversationMemoryStore.getConversationState(conversationId) == ConversationState.AWAITING_HUMAN) {
                    // G4: attribute the pause-terminating end (this store has no request
                    // principal and also runs from scheduled cleanup).
                    conversationService.endConversation(conversationId, "system:admin-end");
                }
            } catch (Exception e) {
                log.warn(format("HITL cleanup before permanent delete failed for conversation %s: %s",
                        sanitize(conversationId), e.getMessage()));
            }

            // The snapshot holds the only references to the conversation's vault
            // slots: if they cannot be deleted, nothing is — a retry can then still
            // find them. Deleting the snapshot anyway would orphan the credentials.
            if (!deleteAutoVaultedSecretsForConversation(conversationId)) {
                throw new ResourceStoreException("Could not delete the vault secrets of conversation " + conversationId
                        + "; the conversation was not deleted. Retry once the secrets vault is reachable.");
            }
            deleteAttachmentsForConversation(conversationId);
            conversationMemoryStore.deleteConversationMemorySnapshot(conversationId);
            conversationDescriptorStore.deleteAllDescriptor(conversationId);
            log.info(format("Conversation has been permanently deleted (conversationId=%s)", sanitize(conversationId)));
        } else {
            softDelete(conversationId);
        }
    }

    /**
     * Ends the conversation and retires its descriptor, so the conversation
     * disappears from every listing while its memory snapshot and attachments stay
     * on the server.
     *
     * <p>
     * This branch used to do <em>nothing at all</em>. A comment claimed a
     * {@code DocumentDescriptorInterceptor} would mark the descriptor deleted
     * "regardless of whether it has been permanently deleted or not", but no such
     * interceptor exists anywhere in the code base — so {@code DELETE
     * /conversationstore/conversations/{id}} (whose {@code deletePermanently}
     * defaults to {@code false}) answered 204 and left the row untouched, still
     * {@code "deleted": false} and still listed. The Manager's delete dialog
     * describes exactly the behaviour implemented here and then reports
     * "Conversation deleted", so the honest-looking answer was the wrong one: users
     * saw a success toast next to a conversation that was still there.
     * </p>
     *
     * <p>
     * {@code deleteDescriptor} archives the descriptor into its history collection
     * with {@code deleted=true} and drops the live row, which is what the
     * {@code includeDeleted=false} listings filter on. The snapshot itself is
     * deliberately kept — that is the whole distinction from the permanent path,
     * and it is what lets the retention sweep and GDPR erasure still find the data.
     * </p>
     *
     * <p>
     * The conversation is <strong>ended first</strong>. A deleted conversation left
     * READY stayed drivable — {@code POST /agents/{id}} still ran turns on it — and
     * stayed in {@code getActiveConversations}, whose descriptor read then failed
     * and broke undeploy-with-end for the whole agent. Ending goes through
     * {@link IConversationService#endConversation(String, String)}, so a paused
     * conversation's approval is resolved (timer disarmed, bookmark cleared,
     * cancellation audited) and an in-flight turn is told not to write back.
     * </p>
     */
    private void softDelete(String conversationId) throws ResourceStoreException, ResourceNotFoundException {
        var state = conversationMemoryStore.getConversationState(conversationId);
        if (state != null && state != ConversationState.ENDED) {
            conversationService.endConversation(conversationId, conversationAccessGuard.callerActor(DELETE_ACTOR));
            markDescriptorEnded(conversationId);
        }
        try {
            conversationDescriptorStore.deleteDescriptor(conversationId, CONVERSATION_DESCRIPTOR_VERSION);
            log.info(format("Conversation has been deleted (conversationId=%s)", sanitize(conversationId)));
        } catch (ResourceModifiedException e) {
            // The descriptor moved under us — surface it rather than reporting a
            // deletion that did not happen.
            throw new ResourceStoreException(
                    format("Could not delete conversation %s: its descriptor was modified concurrently", sanitize(conversationId)), e);
        }
    }

    /**
     * Records ENDED on the live descriptor, if there is one. A conversation whose
     * descriptor was already retired (soft-deleted) or never written is ended on
     * its snapshot alone — that is not an error for a bulk end.
     */
    private void markDescriptorEnded(String conversationId) throws ResourceStoreException {
        try {
            ConversationDescriptor conversationDescriptor = conversationDescriptorStore.readDescriptor(conversationId,
                    CONVERSATION_DESCRIPTOR_VERSION);
            if (conversationDescriptor == null) {
                return;
            }
            conversationDescriptor.setConversationState(ConversationState.ENDED);
            conversationDescriptorStore.setDescriptor(conversationId, CONVERSATION_DESCRIPTOR_VERSION, conversationDescriptor);
        } catch (ResourceNotFoundException e) {
            log.debug(format("No live descriptor to mark ENDED for conversation %s", sanitize(conversationId)));
        }
    }

    @Scheduled(every = "24h")
    public void deleteEndedConversationsOlderThanXDays() {
        if (deleteEndedConversationsOnceOlderThanDays == null || deleteEndedConversationsOnceOlderThanDays < MIN_RETENTION_DAYS) {
            log.debugf("Ended-conversation retention sweep disabled (deleteEndedConversationsOnceOlderThanDays < %d)", MIN_RETENTION_DAYS);
            return;
        }

        if (retentionHeldForV5Migration()) {
            runtime.submitCallable(() -> {
                try {
                    long eligible = countEndedConversationsOlderThan(deleteEndedConversationsOnceOlderThanDays);
                    log.warnf("Ended-conversation retention sweep held: this database comes from EDDI 5, which kept ended "
                            + "conversations for ever. With deleteEndedConversationsOnceOlderThanDays=%d the sweep would "
                            + "permanently delete %d ended conversation(s). It deletes nothing until you decide: keep them with "
                            + "EDDI_CONVERSATIONS_DELETEENDEDCONVERSATIONSONCEOLDERTHANDAYS=-1, or let the retention apply with "
                            + "EDDI_MIGRATION_V6_RENAME_RETENTION_CONFIRMED=true.", deleteEndedConversationsOnceOlderThanDays, eligible);
                } catch (Exception e) {
                    log.warnf("Ended-conversation retention sweep held for a database from EDDI 5; could not count what it would "
                            + "delete: %s", e.toString());
                }
                return null;
            }, ThreadContext.getResources());
            return;
        }

        runtime.submitCallable(() -> {
            try {
                var amountOfEndedConversations = permanentlyDeleteEndedConversationLogs(deleteEndedConversationsOnceOlderThanDays);

                if (amountOfEndedConversations > 0) {
                    log.info(format("Successfully deleted %s conversations, which were older than %s days", amountOfEndedConversations,
                            deleteEndedConversationsOnceOlderThanDays));
                }
            } catch (ResourceStoreException | ResourceNotFoundException e) {
                log.error(e.getLocalizedMessage(), e);
            }
            return null;
        }, ThreadContext.getResources());
    }

    /**
     * Whether the retention sweep must delete nothing because the database comes
     * from EDDI 5 and the operator has not confirmed 6.x's retention.
     *
     * <p>
     * EDDI 5 shipped {@code deleteEndedConversationsOnceOlderThanDays=-1}; 6.x
     * ships 365, and this sweep has no initial delay. So the first boot on 6.x
     * permanently deleted every ended conversation older than a year, before anyone
     * had a chance to notice the default had changed. A hold that lasted only for
     * the process that migrated was not enough: a second replica, or the same pod
     * rescheduled minutes later, is a boot nobody decided on. The hold therefore
     * lasts until {@code eddi.migration.v6-rename.retention-confirmed=true} (or the
     * retention is switched off with -1); see
     * {@link V6RenameMigration#holdsRetention()}.
     * </p>
     */
    boolean retentionHeldForV5Migration() {
        if (retentionConfirmed || v6RenameMigrationInstance == null || !v6RenameMigrationInstance.isResolvable()) {
            return false;
        }
        return v6RenameMigrationInstance.get().holdsRetention();
    }

    /**
     * How many ended conversations with a live descriptor were last modified before
     * the retention cut-off — what the sweep would delete, give or take the
     * soft-deleted ones it ages on their archived descriptor.
     */
    long countEndedConversationsOlderThan(int days) throws ResourceStoreException {
        var cutOff = Date.from(Instant.now().minus(Duration.ofDays(days)));
        long eligible = 0;
        for (var conversationId : conversationMemoryStore.getEndedConversationIds()) {
            try {
                var descriptor = documentDescriptorStore.readDescriptor(conversationId, CONVERSATION_DESCRIPTOR_VERSION);
                if (descriptor != null && descriptor.getLastModifiedOn() != null && descriptor.getLastModifiedOn().before(cutOff)) {
                    eligible++;
                }
            } catch (ResourceNotFoundException e) {
                // no live descriptor: not counted
            }
        }
        return eligible;
    }

    @Scheduled(every = "24h", delayed = "2m")
    void cleanupOldUserMemories() {
        if (deleteMemoriesOlderThanDays == null || deleteMemoriesOlderThanDays <= 0) {
            return; // Disabled
        }

        runtime.submitCallable(() -> {
            try {
                long deleted = userMemoryStore.deleteOlderThan(deleteMemoriesOlderThanDays);
                if (deleted > 0) {
                    log.infof("User memory retention: deleted %d entries older than %d days",
                            deleted, deleteMemoriesOlderThanDays);
                }
            } catch (Exception e) {
                log.error("User memory retention cleanup failed", e);
            }
            return null;
        }, ThreadContext.getResources());
    }

    @Override
    public Integer permanentlyDeleteEndedConversationLogs(Integer deleteOlderThanDays)
            throws ResourceStoreException, ResourceNotFoundException {

        if (deleteOlderThanDays == null || deleteOlderThanDays < MIN_RETENTION_DAYS) {
            throw new BadRequestException(
                    "deleteOlderThanDays must be at least " + MIN_RETENTION_DAYS
                            + " — a smaller value would permanently delete every ended conversation in the deployment");
        }

        int amountOfEndedConversations = 0;
        var deleteOlderThanThisDate = Date.from(Instant.now().minus(Duration.ofDays(deleteOlderThanDays)));
        var endedConversationIds = conversationMemoryStore.getEndedConversationIds();

        for (var endedConversationId : endedConversationIds) {
            try {
                var descriptor = documentDescriptorStore.readDescriptor(endedConversationId, CONVERSATION_DESCRIPTOR_VERSION);
                if (descriptor.getLastModifiedOn().before(deleteOlderThanThisDate)) {
                    if (!deleteAutoVaultedSecretsForConversation(endedConversationId)) {
                        continue; // kept, with its secret references, for the next run
                    }
                    documentDescriptorStore.deleteAllDescriptor(endedConversationId);
                    conversationDescriptorStore.deleteAllDescriptor(endedConversationId);
                    deleteAttachmentsForConversation(endedConversationId);
                    conversationMemoryStore.deleteConversationMemorySnapshot(endedConversationId);
                    amountOfEndedConversations++;
                }
            } catch (ResourceNotFoundException e) {
                // No live descriptor. A soft-deleted conversation still has its
                // archived one and ages out on the same schedule as everything else —
                // soft delete now ends the conversation, so without this every
                // soft-deleted conversation would be purged on the very next sweep,
                // however recently it was active. Only a snapshot with no descriptor
                // at all (live or archived) is an orphan to remove straight away.
                var archive = archiveRetention(endedConversationId, deleteOlderThanThisDate);
                // The vault slots go first: the snapshot about to be deleted holds the
                // only references to them.
                if (archive == ArchiveRetention.WITHIN_RETENTION || !deleteAutoVaultedSecretsForConversation(endedConversationId)) {
                    continue;
                }
                conversationDescriptorStore.deleteAllDescriptor(endedConversationId);
                deleteAttachmentsForConversation(endedConversationId);
                conversationMemoryStore.deleteConversationMemorySnapshot(endedConversationId);
                if (archive == ArchiveRetention.EXPIRED) {
                    // A soft-deleted conversation that aged out: an ordinary retention
                    // deletion, counted as one.
                    amountOfEndedConversations++;
                    log.debug(format("Deleted soft-deleted conversation past retention (id=%s)", sanitize(endedConversationId)));
                } else {
                    log.debug(format("Cleaned up orphaned conversation memory without descriptor (id=%s)", sanitize(endedConversationId)));
                }
            }
        }

        return amountOfEndedConversations;
    }

    /** Where a conversation without a live descriptor stands against retention. */
    private enum ArchiveRetention {
        /** No archived descriptor either: an orphaned snapshot. */
        NO_ARCHIVE,
        /** Soft-deleted, last modified on or after the cut-off: keep for now. */
        WITHIN_RETENTION,
        /** Soft-deleted and past retention (or of unknown age): delete. */
        EXPIRED
    }

    private ArchiveRetention archiveRetention(String conversationId, Date deleteOlderThanThisDate) throws ResourceStoreException {
        ConversationDescriptor archived;
        try {
            archived = conversationDescriptorStore.readDescriptorWithHistory(conversationId, CONVERSATION_DESCRIPTOR_VERSION);
        } catch (ResourceNotFoundException e) {
            archived = null;
        }
        if (archived == null) {
            return ArchiveRetention.NO_ARCHIVE;
        }
        // An archive with no date cannot be aged, and the conversation is both ended
        // and deleted — it is not kept forever on that account.
        if (archived.getLastModifiedOn() != null && !archived.getLastModifiedOn().before(deleteOlderThanThisDate)) {
            return ArchiveRetention.WITHIN_RETENTION;
        }
        return ArchiveRetention.EXPIRED;
    }

    @Override
    public List<ConversationStatus> getActiveConversations(String agentId, Integer agentVersion)
            throws ResourceStoreException, ResourceNotFoundException {
        checkNotNull(agentId, "agentId");
        // agentVersion is optional: absent means every version of the agent. It was
        // mandatory and a bare GET answered 400 "Argument must not be null", although
        // "which conversations of this agent are still open" is the question an
        // operator asks before undeploying — across versions, not for one.

        // Agent-scoped: the ids of every user's open conversations with this agent are
        // an operator's view of that agent, so they take the same EDIT access undeploy
        // (which ends them all) takes. Before this, any authenticated caller could
        // list them, and an id is all the per-conversation endpoints are keyed on.
        resourceAccessGuard.requireAccess(agentId, AccessLevel.EDIT, "agent");

        List<ConversationMemorySnapshot> conversationMemorySnapshots;
        List<ConversationStatus> conversationStatuses = new LinkedList<>();

        conversationMemorySnapshots = conversationMemoryStore.loadActiveConversationMemorySnapshot(agentId, agentVersion);
        for (var snapshot : conversationMemorySnapshots) {
            ConversationStatus conversationStatus = new ConversationStatus();
            String conversationId = snapshot.getId();
            conversationStatus.setConversationId(conversationId);
            conversationStatus.setAgentId(agentId);
            conversationStatus.setAgentVersion(agentVersion != null ? agentVersion : snapshot.getAgentVersion());
            conversationStatus.setConversationState(snapshot.getConversationState());
            conversationStatus.setLastInteraction(lastInteractionOf(conversationId));
            conversationStatuses.add(conversationStatus);
        }

        return conversationStatuses;
    }

    /**
     * The descriptor's last-modified date, falling back to the archived descriptor
     * of a soft-deleted conversation, else {@code null}. A conversation that was
     * soft-deleted while still open (before soft delete ended it) has no live
     * descriptor, and reading one threw — which failed the whole listing, and with
     * it undeploy-with-end for the agent.
     */
    private Date lastInteractionOf(String conversationId) throws ResourceStoreException {
        try {
            return conversationDescriptorStore.readDescriptor(conversationId, CONVERSATION_DESCRIPTOR_VERSION).getLastModifiedOn();
        } catch (ResourceNotFoundException e) {
            try {
                return conversationDescriptorStore.readDescriptorWithHistory(conversationId, CONVERSATION_DESCRIPTOR_VERSION)
                        .getLastModifiedOn();
            } catch (ResourceNotFoundException notArchivedEither) {
                return null;
            }
        }
    }

    /**
     * Ends the listed conversations. Only the conversation ids are read from the
     * request: which agent a conversation belongs to and which state it is in come
     * from the server.
     *
     * <p>
     * The request's {@code conversationState} used to decide whether the HITL-aware
     * end ran. A paused conversation sent as {@code READY} skipped it — leaving the
     * timeout armed, the bookmark set and no cancellation audited — and any
     * conversation sent as {@code AWAITING_HUMAN} wrote a forged
     * {@code hitl.approval} cancellation to the audit trail. Every conversation now
     * goes through {@link IConversationService#endConversation(String, String)},
     * which reads the previous state itself, and a terminated approval is
     * attributed to the calling principal.
     * </p>
     *
     * <p>
     * <strong>Authorization is all-or-nothing:</strong> every conversation's agent
     * is checked for EDIT access before any conversation is ended, so a list that
     * mixes agents the caller may and may not edit is refused with 403 and nothing
     * changes. (The EDIT check is only enforced with workspaces on —
     * {@code eddi.workspaces.enabled=true}; otherwise the role on the interface is
     * the whole gate, exactly as for undeploy.)
     * </p>
     *
     * <p>
     * <strong>Ending is per conversation and continues on error.</strong> An
     * unknown or already ENDED id is skipped. If ending one conversation fails, the
     * rest are still ended and the response is a 500 whose body lists what was
     * {@code ended}, {@code skipped} and {@code failed}; otherwise it is a 200 with
     * the same body. Recording ENDED on the descriptor is best-effort — the listing
     * re-derives the state from the snapshot — so it is logged, not failed.
     * </p>
     *
     * <p>
     * State and agent are read without loading the memory snapshot: the state
     * through the state projection, the agent from the conversation descriptor
     * (live or archived). Only a conversation with no descriptor at all falls back
     * to the snapshot.
     * </p>
     */
    /**
     * The end reasons a caller may record. A closed list rather than free text: the
     * reason is stored on the conversation and shown to its user by clients, so it
     * must be a code they know, never caller-supplied prose.
     */
    static final Set<String> ACCEPTED_END_REASONS = Set.of(IConversationService.END_REASON_AGENT_VERSION_RETIRED);

    @Override
    public Response endActiveConversations(List<ConversationStatus> conversationStatuses, String endReason) {
        if (conversationStatuses == null) {
            throw new BadRequestException("A list of conversations to end is required");
        }
        final String reason = isNullOrEmpty(endReason) ? null : endReason;
        if (reason != null && !ACCEPTED_END_REASONS.contains(reason)) {
            throw new BadRequestException("Unknown endReason; accepted: " + String.join(", ", ACCEPTED_END_REASONS));
        }
        String actor = conversationAccessGuard.callerActor(BULK_END_ACTOR);
        try {
            Set<String> checkedAgents = new HashSet<>();
            List<String> toEnd = new LinkedList<>();
            List<String> skipped = new LinkedList<>();
            for (ConversationStatus conversationStatus : conversationStatuses) {
                String conversationId = conversationStatus == null ? null : conversationStatus.getConversationId();
                if (isNullOrEmpty(conversationId)) {
                    continue;
                }

                ConversationState state = conversationMemoryStore.getConversationState(conversationId);
                if (state == null) {
                    log.debug(format("Skipping unknown conversation %s in bulk end", sanitize(conversationId)));
                    skipped.add(conversationId);
                    continue;
                }
                String agentId = agentIdOf(conversationId);
                if (checkedAgents.add(String.valueOf(agentId))) {
                    resourceAccessGuard.requireAccess(agentId, AccessLevel.EDIT, "agent");
                }
                if (state == ConversationState.ENDED) {
                    skipped.add(conversationId);
                } else {
                    toEnd.add(conversationId);
                }
            }

            List<String> ended = new LinkedList<>();
            List<String> failed = new LinkedList<>();
            for (String conversationId : toEnd) {
                try {
                    if (reason == null) {
                        conversationService.endConversation(conversationId, actor);
                    } else {
                        conversationService.endConversation(conversationId, actor, reason);
                    }
                } catch (RuntimeException e) {
                    log.error(format("Could not end conversation %s in bulk end", sanitize(conversationId)), e);
                    failed.add(conversationId);
                    continue;
                }
                ended.add(conversationId);
                try {
                    markDescriptorEnded(conversationId);
                } catch (ResourceStoreException | RuntimeException e) {
                    log.warn(format("Conversation %s was ended but its descriptor could not be marked ENDED: %s",
                            sanitize(conversationId), sanitize(e.getMessage())));
                }
                log.info(format("conversation (%s) has been set to ENDED", sanitize(conversationId)));
            }

            var result = new LinkedHashMap<String, List<String>>();
            result.put("ended", ended);
            result.put("skipped", skipped);
            result.put("failed", failed);
            var status = failed.isEmpty() ? Response.Status.OK : Response.Status.INTERNAL_SERVER_ERROR;
            return Response.status(status).entity(result).type(MediaType.APPLICATION_JSON).build();
        } catch (ResourceStoreException e) {
            throw sneakyThrow(e);
        }
    }

    /**
     * Deletes the vault slots the conversation's {@code scope: "secret"} properties
     * point to — every version, including those only its undo history and redo
     * cache still reference. Each auto-vaulted write owns its own slot (see
     * {@link AutoVaultedSecrets}), so once the conversation is gone nothing can
     * resolve them any more — left in place they would be orphaned plaintext
     * credentials. Must run BEFORE the snapshot is deleted: the snapshot is where
     * the references live.
     * <p>
     * Unlike the attachment cleanup this is not best effort. A snapshot deleted
     * after a failed vault delete takes the only record of the slot names with it,
     * and neither a retry nor a GDPR erasure could find them again (a custom tenant
     * is named only there).
     *
     * @return {@code false} when the slots could not be deleted — the caller must
     *         then keep the conversation so the cleanup can be retried
     */
    private boolean deleteAutoVaultedSecretsForConversation(String conversationId) {
        if (autoVaultedSecretsInstance == null || !autoVaultedSecretsInstance.isResolvable()) {
            return true;
        }
        ConversationMemorySnapshot snapshot;
        try {
            snapshot = conversationMemoryStore.loadConversationMemorySnapshot(conversationId);
        } catch (ResourceNotFoundException e) {
            return true; // no snapshot, no references
        } catch (Exception e) {
            log.warn(format("Could not read conversation %s to delete its auto-vaulted secrets: %s", sanitize(conversationId),
                    e.getMessage()));
            return false;
        }
        if (snapshot == null) {
            return true;
        }
        try {
            var memory = ConversationMemoryUtilities.convertConversationMemorySnapshot(snapshot);
            int deleted = autoVaultedSecretsInstance.get().deleteForConversation(ConversationMemory.everyPropertyVersion(memory),
                    snapshot.getUserId());
            if (deleted > 0) {
                log.debug(format("Deleted %d auto-vaulted secret(s) for conversation %s", deleted, sanitize(conversationId)));
            }
            return true;
        } catch (Exception e) {
            log.warn(format("Failed to delete auto-vaulted secrets for conversation %s; keeping the conversation for a retry: %s",
                    sanitize(conversationId), e.getMessage()));
            return false;
        }
    }

    /**
     * The agent a conversation belongs to, from its descriptor (live, else
     * archived), falling back to the memory snapshot only when it has neither.
     */
    private String agentIdOf(String conversationId) throws ResourceStoreException {
        ConversationDescriptor descriptor;
        try {
            descriptor = conversationDescriptorStore.readDescriptor(conversationId, CONVERSATION_DESCRIPTOR_VERSION);
        } catch (ResourceNotFoundException e) {
            descriptor = null;
        }
        if (descriptor == null) {
            try {
                descriptor = conversationDescriptorStore.readDescriptorWithHistory(conversationId, CONVERSATION_DESCRIPTOR_VERSION);
            } catch (ResourceNotFoundException e) {
                descriptor = null;
            }
        }
        if (descriptor != null && descriptor.getAgentResource() != null) {
            var agentResourceId = extractResourceId(descriptor.getAgentResource());
            if (agentResourceId != null && !isNullOrEmpty(agentResourceId.getId())) {
                return agentResourceId.getId();
            }
        }
        try {
            var snapshot = conversationMemoryStore.loadConversationMemorySnapshot(conversationId);
            return snapshot == null ? null : snapshot.getAgentId();
        } catch (ResourceNotFoundException e) {
            return null;
        }
    }

    /**
     * Delete any binary attachments stored for a conversation. Silently skips if no
     * attachment storage is configured.
     */
    private void deleteAttachmentsForConversation(String conversationId) {
        if (attachmentStorageInstance.isResolvable()) {
            try {
                long deleted = attachmentStorageInstance.get().deleteByConversation(conversationId);
                if (deleted > 0) {
                    log.debug(format("Deleted %d attachments for conversation %s", deleted, sanitize(conversationId)));
                }
            } catch (Exception e) {
                log.warn(format("Failed to delete attachments for conversation %s: %s",
                        sanitize(conversationId), sanitize(e.getMessage())));
            }
        }
    }
}
