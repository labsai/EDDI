/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.migration;

import ai.labs.eddi.configs.agents.IRestAgentStore;
import ai.labs.eddi.configs.apicalls.IRestApiCallsStore;
import ai.labs.eddi.configs.channels.IRestChannelIntegrationStore;
import ai.labs.eddi.configs.connections.IRestConnectionStore;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.dictionary.IRestDictionaryStore;
import ai.labs.eddi.configs.groups.IRestAgentGroupStore;
import ai.labs.eddi.configs.llm.IRestLlmStore;
import ai.labs.eddi.configs.mcpcalls.IRestMcpCallsStore;
import ai.labs.eddi.configs.output.IRestOutputStore;
import ai.labs.eddi.configs.parser.IRestParserStore;
import ai.labs.eddi.configs.propertysetter.IRestPropertySetterStore;
import ai.labs.eddi.configs.rag.IRestRagStore;
import ai.labs.eddi.configs.rules.IRestRuleSetStore;
import ai.labs.eddi.configs.snippets.IRestPromptSnippetStore;
import ai.labs.eddi.configs.workflows.IRestWorkflowStore;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.configs.migration.model.MigrationLog;
import ai.labs.eddi.engine.security.spaces.DescriptorAccess;
import ai.labs.eddi.engine.security.spaces.Subjects;
import ai.labs.eddi.utils.RestUtilities;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.stream.Stream;

/**
 * One-shot startup migration: gives every pre-existing configuration descriptor
 * an access index, so workspace filtering can be switched on without hiding
 * everything that already exists.
 *
 * <h3>Why an explicit backfill is required, not optional</h3> Listings filter
 * on {@code accessIndex}, and neither backend can express "this field is
 * absent" — a regex predicate simply does not match a missing field on MongoDB,
 * and {@code jsonb ->> 'accessIndex'} is null on PostgreSQL. So a descriptor
 * written before this feature would match no access predicate at all and vanish
 * from every listing the moment enforcement is enabled. Stamping the
 * {@link Subjects#LEGACY} token is what keeps that from happening.
 * <p>
 * Descriptors that already carry an owner or a space are re-indexed rather than
 * skipped: the index is derived state, and rebuilding it is idempotent.
 *
 * <h3>It does not invent owners</h3> A descriptor with no {@code ownerId} keeps
 * none. Attribution cannot be reconstructed after the fact, and guessing (the
 * first admin? the deployment owner?) would produce confident, wrong answers
 * that are worse than an honest "unowned". Whether unowned resources stay
 * visible is the operator's call, via
 * {@code eddi.workspaces.legacy-visibility}.
 *
 * @since 6.3.0
 */
@ApplicationScoped
public class WorkspaceAccessIndexMigration {

    private static final Logger LOGGER = Logger.getLogger(WorkspaceAccessIndexMigration.class);
    private static final String MIGRATION_KEY = "workspace-access-index-migration-complete";

    /**
     * Descriptor types to backfill: every configuration type that has a REST store,
     * because every one of them is listed through the same access predicate.
     *
     * <h3>Derived, not hand-written</h3> The type a listing queries comes from
     * {@link RestUtilities#extractDescriptorType} applied to each store's own
     * {@code resourceURI}, and several of those differ from the file-extension
     * names used in ZIP archives — {@code ai.labs.rules} not
     * {@code ai.labs.behavior}, {@code ai.labs.apicalls} not
     * {@code ai.labs.httpcalls}, {@code ai.labs.llm} not {@code ai.labs.langchain},
     * {@code ai.labs.dictionary} not {@code ai.labs.regulardictionary} (AGENTS.md
     * §5.5). Deriving the list from the constants is what keeps a hand-written
     * near-miss from silently backfilling nothing for a type and then recording
     * itself as complete — which would make every rule set, api call, LLM config
     * and dictionary vanish from every listing the moment enforcement was switched
     * on.
     * <p>
     * {@code WorkspaceAccessIndexMigrationTest} asserts this against the stores.
     */
    static final List<String> DESCRIPTOR_TYPES = Stream.of(
            IRestAgentStore.resourceURI,
            IRestWorkflowStore.resourceURI,
            IRestRuleSetStore.resourceURI,
            IRestApiCallsStore.resourceURI,
            IRestMcpCallsStore.resourceURI,
            IRestLlmStore.resourceURI,
            IRestOutputStore.resourceURI,
            IRestPropertySetterStore.resourceURI,
            IRestParserStore.resourceURI,
            IRestDictionaryStore.resourceURI,
            IRestRagStore.resourceURI,
            IRestPromptSnippetStore.resourceURI,
            IRestChannelIntegrationStore.resourceURI,
            IRestConnectionStore.resourceURI,
            IRestAgentGroupStore.resourceURI)
            .map(RestUtilities::extractDescriptorType)
            .distinct()
            .toList();

    /**
     * Page size for the sweep. Bounded so a large deployment does not load it all.
     */
    private static final int BATCH_SIZE = 200;

    /**
     * Pages scanned per type before giving up. At {@value #BATCH_SIZE} per page
     * this is 200 000 descriptors of one type — far beyond any real deployment, and
     * a backstop against a store whose paging never advances.
     */
    private static final int MAX_PAGES = 1000;

    private final IDocumentDescriptorStore descriptorStore;
    private final IMigrationLogStore migrationLogStore;

    @Inject
    public WorkspaceAccessIndexMigration(IDocumentDescriptorStore descriptorStore, IMigrationLogStore migrationLogStore) {
        this.descriptorStore = descriptorStore;
        this.migrationLogStore = migrationLogStore;
    }

    /** Runs the backfill unless it has already completed. */
    public void runIfNeeded() {
        if (migrationLogStore.readMigrationLog(MIGRATION_KEY) != null) {
            LOGGER.debug("Workspace access-index migration already applied — skipping");
            return;
        }

        int stamped = 0;
        int scanned = 0;
        boolean complete = true;
        for (String type : DESCRIPTOR_TYPES) {
            for (int page = 0; page < MAX_PAGES; page++) {
                List<DocumentDescriptor> batch;
                try {
                    // Unrestricted deliberately: a migration runs below the access model, and
                    // filtering the backfill by an access index that does not exist yet would
                    // return nothing.
                    batch = descriptorStore.readDescriptors(type, "", page, BATCH_SIZE, true);
                } catch (Exception e) {
                    LOGGER.warnf("Could not read %s descriptors on page %d during access-index backfill: %s", type, page, e.getMessage());
                    complete = false;
                    break;
                }
                if (batch == null || batch.isEmpty()) {
                    break;
                }
                scanned += batch.size();
                for (DocumentDescriptor descriptor : batch) {
                    if (stampIfNeeded(descriptor)) {
                        stamped++;
                    }
                }
                if (batch.size() < BATCH_SIZE) {
                    break;
                }
            }
        }

        if (!complete) {
            // Deliberately NOT recording completion: a partial backfill that marks itself
            // done leaves descriptors with no access index, and those are invisible in
            // every listing once enforcement is on — with no way to re-run short of
            // deleting the log row by hand. Retrying on the next startup is cheap;
            // the stamp is idempotent.
            LOGGER.warnv("Workspace access-index backfill INCOMPLETE ({0} scanned, {1} stamped) — it will retry on the next startup.",
                    scanned, stamped);
            return;
        }

        LOGGER.infov("Workspace access-index backfill complete: {0} descriptor(s) scanned, {1} stamped.", scanned, stamped);
        migrationLogStore.createMigrationLog(new MigrationLog(MIGRATION_KEY));
    }

    /**
     * @return whether the descriptor was written
     */
    private boolean stampIfNeeded(DocumentDescriptor descriptor) {
        if (descriptor == null || descriptor.getResource() == null) {
            return false;
        }
        String rebuilt = DescriptorAccess.buildIndex(descriptor);
        if (rebuilt.equals(descriptor.getAccessIndex())) {
            return false;
        }
        descriptor.setAccessIndex(rebuilt);

        var resourceId = RestUtilities.extractResourceId(descriptor.getResource());
        if (resourceId == null || resourceId.getId() == null || resourceId.getVersion() == null) {
            return false;
        }
        try {
            // setDescriptor writes in place. A backfill must not create a version: doing so
            // would make every pre-existing resource look as though it had just been
            // edited.
            descriptorStore.setDescriptor(resourceId.getId(), resourceId.getVersion(), descriptor);
            return true;
        } catch (Exception e) {
            LOGGER.warnf("Could not stamp access index on descriptor %s v%s: %s", resourceId.getId(), resourceId.getVersion(), e.getMessage());
            return false;
        }
    }
}
