/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.channels.rest;

import ai.labs.eddi.configs.channels.IChannelIntegrationStore;
import ai.labs.eddi.configs.channels.IRestChannelIntegrationStore;
import ai.labs.eddi.configs.channels.model.ChannelIntegrationConfiguration;
import ai.labs.eddi.configs.channels.model.ChannelTarget;
import ai.labs.eddi.configs.channels.model.EmailChannelSettings;
import ai.labs.eddi.configs.channels.model.EmailSenderLists;
import ai.labs.eddi.configs.channels.model.EmailSuppressionView;
import ai.labs.eddi.configs.channels.model.ObserveConfig;
import ai.labs.eddi.configs.channels.model.WebhookChannelSettings;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import ai.labs.eddi.integrations.channels.ChannelTargetRouter;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.configs.rest.RestVersionInfo;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.serialization.IDescriptorStore;
import ai.labs.eddi.integrations.channels.ChannelConnectionCredentials;
import ai.labs.eddi.integrations.channels.ChannelTypeDescriptor;
import ai.labs.eddi.integrations.channels.ChannelTypeRegistry;
import ai.labs.eddi.integrations.email.EmailChannelSupport;
import ai.labs.eddi.utils.RestUtilities;
import ai.labs.eddi.secrets.SecretResolver;
import ai.labs.eddi.secrets.model.SecretReference;
import ai.labs.eddi.secrets.sanitize.ConfigSecretRedactor;
import ai.labs.eddi.configs.rest.RedactionMarkerGuard;
import com.fasterxml.jackson.core.JsonProcessingException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.ServiceUnavailableException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.net.URI;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * REST implementation for channel integration configuration CRUD. Includes
 * validation for trigger uniqueness, default target, and channel type.
 *
 * @since 6.1.0
 */
@ApplicationScoped
public class RestChannelIntegrationStore implements IRestChannelIntegrationStore {
    private static final Logger LOG = Logger.getLogger(RestChannelIntegrationStore.class);

    /**
     * Response header carrying save-time warnings: comma-separated stable codes
     * (see {@link EmailChannelSettings#WARNING_OPEN_TRIGGER}), absent when there
     * are none. A warning never refuses a save.
     */
    public static final String WARNINGS_HEADER = "X-Eddi-Warnings";

    /**
     * Trigger keywords that are reserved by the router and must not be configured.
     */
    private static final Set<String> RESERVED_TRIGGERS = Set.of("help");

    private final IChannelIntegrationStore channelStore;
    private final IDocumentDescriptorStore documentDescriptorStore;
    private final ResourceAccessGuard resourceAccessGuard;
    private final RestVersionInfo<ChannelIntegrationConfiguration> restVersionInfo;
    private final Instance<EmailChannelSupport> emailSupport;
    private final SecretResolver secretResolver;
    private final ChannelTargetRouter channelTargetRouter;

    /**
     * The registered channel types ({@link ChannelTypeDescriptor} beans), which
     * replace the hard-coded set of type names this class used to validate against.
     * {@code webhook} is validated by {@link WebhookChannelSettings#validate};
     * {@code email} by {@link EmailChannelSettings#validate} and, against the
     * deployment, by {@link EmailChannelSupport}.
     */
    private final ChannelTypeRegistry channelTypes;

    /**
     * Checks the connection a connection-backed type names; {@code null} (the
     * shorter constructors, used by tests) skips only that deployment check.
     */
    private final ChannelConnectionCredentials channelCredentials;

    public RestChannelIntegrationStore(IChannelIntegrationStore channelStore,
            IDocumentDescriptorStore documentDescriptorStore,
            ResourceAccessGuard resourceAccessGuard) {
        this(channelStore, documentDescriptorStore, resourceAccessGuard, (Instance<EmailChannelSupport>) null, (SecretResolver) null, null);
    }

    public RestChannelIntegrationStore(IChannelIntegrationStore channelStore,
            IDocumentDescriptorStore documentDescriptorStore,
            ResourceAccessGuard resourceAccessGuard,
            Instance<EmailChannelSupport> emailSupport) {
        this(channelStore, documentDescriptorStore, resourceAccessGuard, emailSupport, null, null);
    }

    public RestChannelIntegrationStore(IChannelIntegrationStore channelStore,
            IDocumentDescriptorStore documentDescriptorStore,
            ResourceAccessGuard resourceAccessGuard,
            SecretResolver secretResolver) {
        this(channelStore, documentDescriptorStore, resourceAccessGuard, null, secretResolver, null);
    }

    public RestChannelIntegrationStore(IChannelIntegrationStore channelStore,
            IDocumentDescriptorStore documentDescriptorStore,
            ResourceAccessGuard resourceAccessGuard,
            Instance<EmailChannelSupport> emailSupport,
            SecretResolver secretResolver) {
        this(channelStore, documentDescriptorStore, resourceAccessGuard, emailSupport, secretResolver, null);
    }

    public RestChannelIntegrationStore(IChannelIntegrationStore channelStore,
            IDocumentDescriptorStore documentDescriptorStore,
            ResourceAccessGuard resourceAccessGuard,
            SecretResolver secretResolver,
            ChannelTargetRouter channelTargetRouter) {
        this(channelStore, documentDescriptorStore, resourceAccessGuard, null, secretResolver, channelTargetRouter);
    }

    /**
     * @param channelTargetRouter
     *            consulted for legacy connectors serving a channel; {@code null}
     *            (the shorter constructors, used by tests) skips only that check
     */
    public RestChannelIntegrationStore(IChannelIntegrationStore channelStore,
            IDocumentDescriptorStore documentDescriptorStore,
            ResourceAccessGuard resourceAccessGuard,
            Instance<EmailChannelSupport> emailSupport,
            SecretResolver secretResolver,
            ChannelTargetRouter channelTargetRouter) {
        this(channelStore, documentDescriptorStore, resourceAccessGuard, emailSupport, secretResolver, channelTargetRouter,
                ChannelTypeRegistry.builtIns(), null);
    }

    @Inject
    public RestChannelIntegrationStore(IChannelIntegrationStore channelStore,
            IDocumentDescriptorStore documentDescriptorStore,
            ResourceAccessGuard resourceAccessGuard,
            Instance<EmailChannelSupport> emailSupport,
            SecretResolver secretResolver,
            ChannelTargetRouter channelTargetRouter,
            ChannelTypeRegistry channelTypes,
            ChannelConnectionCredentials channelCredentials) {
        this.channelTypes = channelTypes != null ? channelTypes : ChannelTypeRegistry.builtIns();
        this.channelCredentials = channelCredentials;
        this.secretResolver = secretResolver;
        this.channelTargetRouter = channelTargetRouter;
        restVersionInfo = new RestVersionInfo<>(resourceURI, channelStore, documentDescriptorStore, resourceAccessGuard);
        this.channelStore = channelStore;
        this.documentDescriptorStore = documentDescriptorStore;
        this.resourceAccessGuard = resourceAccessGuard;
        this.emailSupport = emailSupport;
    }

    private static boolean isEmail(ChannelIntegrationConfiguration configuration) {
        return configuration != null && EmailChannelSettings.CHANNEL_TYPE.equalsIgnoreCase(configuration.getChannelType());
    }

    private EmailChannelSupport emailSupport() {
        if (emailSupport == null || !emailSupport.isResolvable()) {
            throw new ServiceUnavailableException("Email channel support is not available on this node.");
        }
        return emailSupport.get();
    }

    /**
     * The deployment checks and, after the write, the poll schedule and the
     * warnings header of an email integration.
     */
    private Response withEmailFollowUp(String id, ChannelIntegrationConfiguration configuration, Response response) {
        if (!isEmail(configuration) || id == null || response.getStatus() >= 300) {
            return response;
        }
        EmailChannelSupport support = emailSupport();
        support.afterSave(id, configuration);
        List<String> warnings;
        try {
            warnings = support.warnings(configuration);
        } catch (RuntimeException e) {
            LOG.debugf("Email warnings skipped: %s", e.getMessage());
            warnings = List.of();
        }
        if (warnings.isEmpty()) {
            return response;
        }
        return Response.fromResponse(response).header(WARNINGS_HEADER, String.join(",", warnings)).build();
    }

    /**
     * Refuses a webhook whose vault secret already exists but is shorter than
     * {@link WebhookChannelSettings#MIN_SECRET_LENGTH}: the endpoint would answer
     * 401 to every request. A secret that is not in the vault yet (or cannot be
     * read) cannot be judged here - the vault reference alone is checked by
     * {@link WebhookChannelSettings#validate}, and the router re-checks the
     * resolved value on every refresh.
     */
    private void requireUsableWebhookSecret(Map<String, String> platformConfig) {
        String reference = platformConfig == null ? null : platformConfig.get(WebhookChannelSettings.KEY_SIGNING_SECRET);
        if (secretResolver == null || reference == null) {
            return;
        }
        try {
            // Only the default tenant's vault is read at save time: resolving another
            // tenant's reference for any editor would tell them whether that secret
            // exists and whether it is short. Such references are left to the router's
            // runtime check.
            if (!SecretReference.DEFAULT_TENANT.equals(SecretReference.parse(reference.trim()).tenantId())) {
                return;
            }
        } catch (IllegalArgumentException e) {
            return;
        }
        String resolved;
        try {
            resolved = secretResolver.resolveValue(reference.trim());
        } catch (RuntimeException e) {
            return;
        }
        if (resolved != null && !resolved.equals(reference.trim()) && resolved.length() < WebhookChannelSettings.MIN_SECRET_LENGTH) {
            throw new BadRequestException("The vault secret behind platformConfig.signingSecret is shorter than "
                    + WebhookChannelSettings.MIN_SECRET_LENGTH + " characters - use 32 random bytes.");
        }
    }

    /**
     * Asserts the author may converse with everything this channel points at.
     * <p>
     * <h3>Why the channel's own permissions are not enough</h3> A channel target is
     * a standing invitation: once configured, every inbound Slack message reaches
     * {@code targetId} as a system-initiated conversation, which is deliberately
     * below the USE gate. Without this check an editor could aim a channel they
     * control at a colleague's private agent and relay its replies into a room of
     * their choosing, having never held access to it. Mirrors the checks on
     * triggers, schedules and group membership, which are the same shape of
     * standing reference.
     */
    private void requireUseOnTargets(ChannelIntegrationConfiguration configuration) {
        if (configuration == null || configuration.getTargets() == null) {
            return;
        }
        for (var target : configuration.getTargets()) {
            if (target == null || target.getTargetId() == null || target.getTargetId().isBlank()) {
                continue;
            }
            if (target.getType() == ChannelTarget.TargetType.GROUP) {
                resourceAccessGuard.requireUseAccess(target.getTargetId(), "group");
            } else {
                resourceAccessGuard.requireAgentUseAccess(target.getTargetId());
            }
        }
    }

    @Override
    public List<DocumentDescriptor> readChannelDescriptors(String filter, Integer index, Integer limit) {
        return restVersionInfo.readDescriptors(filter, index, limit);
    }

    @Override
    public ChannelIntegrationConfiguration readChannel(String id, Integer version) {
        ChannelIntegrationConfiguration stored = restVersionInfo.read(id, version);
        // An administrator sees the document as stored, so a plaintext credential can
        // be found and moved into the vault; everyone else admitted here reads a copy
        // with literals redacted (M1 of the 2026-10-17 review) - see
        // ConfigSecretRedactor. ?version= reads go through the same path.
        // A Slack signingSecret matters most here: its holder can forge
        // /integrations/slack/* events.
        return resourceAccessGuard != null && resourceAccessGuard.isAdmin()
                ? stored
                : ConfigSecretRedactor.redact(stored, ChannelIntegrationConfiguration.class);
    }

    /**
     * Cluster mode aside, makes each uniqueness check and the write it guards one
     * step. The checks scan the stored integrations and the write follows, so two
     * concurrent saves claiming the same {@code channelType:channelId} (or name)
     * both passed the scan and both landed, leaving a duplicate routing key the
     * router resolved by iteration order. Channel writes are rare; serialising them
     * on this node costs nothing measurable.
     */
    private final Object uniquenessLock = new Object();

    @Override
    public Response updateChannel(String id, Integer version,
                                  ChannelIntegrationConfiguration channelConfiguration) {
        validateConfiguration(channelConfiguration);
        requireUseOnTargets(channelConfiguration);
        synchronized (uniquenessLock) {
            validateUniqueRoutingKeys(channelConfiguration, id);
            refuseLegacyConnectorTakeover(channelConfiguration, storedChannelId(id, version));
            validateUniqueName(channelConfiguration, id);
            if (isEmail(channelConfiguration)) {
                emailSupport().validateAgainstDeployment(channelConfiguration, id);
            }
            Response response = restVersionInfo.update(id, version, channelConfiguration);
            syncDescriptor(id, channelConfiguration);
            return withEmailFollowUp(id, channelConfiguration, response);
        }
    }

    @Override
    public Response createChannel(ChannelIntegrationConfiguration channelConfiguration) {
        validateConfiguration(channelConfiguration);
        requireUseOnTargets(channelConfiguration);
        synchronized (uniquenessLock) {
            validateUniqueRoutingKeys(channelConfiguration, null);
            refuseLegacyConnectorTakeover(channelConfiguration, null);
            validateUniqueName(channelConfiguration, null);
            if (isEmail(channelConfiguration)) {
                emailSupport().validateAgainstDeployment(channelConfiguration, null);
            }
            Response response = restVersionInfo.create(channelConfiguration);
            publishNewIntegration(response, channelConfiguration);
            return withEmailFollowUp(createdId(response), channelConfiguration, response);
        }
    }

    @Override
    public Response duplicateChannel(String id, Integer version) {
        restVersionInfo.validateParameters(id, version);
        ChannelIntegrationConfiguration config = restVersionInfo.read(id, version);
        // Clear channelId so the duplicate doesn't collide in the router's
        // integrationMap (each channelType:channelId must be unique)
        if (config.getPlatformConfig() != null) {
            var platformConfig = config.getPlatformConfig();
            platformConfig.remove("channelId");
            config.setPlatformConfig(platformConfig);
        }
        // Names are globally unique per channel type, so a copy cannot reuse the
        // source name. Suffix it and let validateUniqueName below confirm the result.
        if (config.getName() != null && !config.getName().isBlank()) {
            config.setName(deriveUniqueDuplicateName(config.getName(), config.getChannelType()));
        }
        refuseApplicationTakeoverByDuplicate(id, config);
        validateConfiguration(config);
        // A copy is a new standing route, so its targets need the same USE check a
        // create runs: duplicating an old version used to restore a route to an
        // agent the caller may not use.
        requireUseOnTargets(config);
        synchronized (uniquenessLock) {
            // channelId was cleared above, but a type routed by another key (an
            // application id) would otherwise duplicate straight into a collision.
            validateUniqueRoutingKeys(config, null);
            validateUniqueName(config, null);
            if (isEmail(config)) {
                // Refused for an email integration: the copy would poll the same mailbox.
                emailSupport().validateAgainstDeployment(config, null);
            }
            Response response = restVersionInfo.create(config);
            publishNewIntegration(response, config);
            return withEmailFollowUp(createdId(response), config, response);
        }
    }

    /**
     * A copy of a connection-backed integration acts with the source's connection,
     * so it needs edit access to the source, not the view access a duplicate of a
     * legacy integration has always needed. And a copy that has lost its
     * {@code channelId} but keeps its application id would be indexed under the
     * application's key and silently start answering that application's direct
     * messages; that is refused: a channel-less integration is created on purpose,
     * not by copying a channel-bound one.
     */
    private void refuseApplicationTakeoverByDuplicate(String sourceId, ChannelIntegrationConfiguration copy) {
        var descriptor = channelTypes.find(copy.getChannelType()).filter(ChannelTypeDescriptor::connectionBacked);
        if (descriptor.isEmpty()) {
            return;
        }
        restVersionInfo.requireEditAccess(sourceId);
        boolean claimsApplication = descriptor.get().routingKeys(null, copy.getPlatformConfig()).stream()
                .anyMatch(key -> key != null && key.startsWith(ChannelTypeDescriptor.APPLICATION_KEY_PREFIX));
        if (claimsApplication) {
            channelTypes.recordRejected(descriptor.get().id(), "duplicate_claims_application");
            // A copy never keeps its source's channel (two integrations cannot share one),
            // so a copy of an integration with an application id is always channel-less.
            throw new BadRequestException("A " + descriptor.get().id() + " integration with an application id cannot be duplicated: "
                    + "the copy would have no channel and would answer the application's direct messages. Create the new "
                    + "integration directly instead.");
        }
    }

    /**
     * The id of the integration a create answered, as
     * {@link #publishNewIntegration} validated it.
     */
    private static String createdId(Response response) {
        return RestUtilities.extractResourceId(response.getLocation()).getId();
    }

    /**
     * Makes a just-created integration visible to the next uniqueness check before
     * {@link #uniquenessLock} is released.
     *
     * <p>
     * Both uniqueness checks enumerate the channel DESCRIPTORS, and a new
     * integration has none until one is written. The descriptor used to be written
     * after the lock was released, so a second save could take the lock, scan, miss
     * the first integration and store the same {@code channelId} or name — the
     * duplicate the lock exists to prevent. Written here, inside the lock, at the
     * version the create answered, and strictly: an integration whose descriptor
     * could not be written is invisible to every later check, so it is removed
     * again and the save fails instead of leaving it behind.
     * </p>
     */
    private void publishNewIntegration(Response response, ChannelIntegrationConfiguration config) {
        URI location = response.getLocation();
        var resourceId = location == null ? null : RestUtilities.extractResourceId(location);
        if (resourceId == null || resourceId.getId() == null || resourceId.getVersion() == null) {
            throw new InternalServerErrorException("The channel integration was stored without a usable location.");
        }
        try {
            writeDescriptor(resourceId.getId(), resourceId.getVersion(), config);
        } catch (Exception e) {
            LOG.errorf(e, "Could not write the descriptor of new channel integration id=%s; removing it",
                    sanitizeForLog(resourceId.getId()));
            try {
                channelStore.deleteAllPermanently(resourceId.getId());
            } catch (RuntimeException cleanup) {
                LOG.errorf(cleanup, "Could not remove channel integration id=%s after its descriptor failed",
                        sanitizeForLog(resourceId.getId()));
            }
            throw new InternalServerErrorException("The channel integration could not be registered. Please retry.");
        }
    }

    @Override
    public Response deleteChannel(String id, Integer version, Boolean permanent) {
        boolean email = false;
        try {
            email = isEmail(channelStore.read(id, version));
        } catch (Exception e) {
            LOG.debugf("Channel %s could not be read before deletion: %s", sanitize(id), e.getMessage());
        }
        Response response = restVersionInfo.delete(id, version, permanent);
        if (email && response.getStatus() < 300 && emailSupport != null && emailSupport.isResolvable()) {
            emailSupport.get().afterDelete(id);
        }
        return response;
    }

    @Override
    public List<EmailSuppressionView> readEmailSuppressions(String id, Integer limit) {
        requireEmailIntegrationForEdit(id);
        return emailSupport().suppressions(id, limit == null ? 100 : limit).stream()
                .map(s -> new EmailSuppressionView(s.addressHash(), s.domain(), s.reason(), s.createdAt() == null ? null : s.createdAt().toString()))
                .toList();
    }

    @Override
    public Response clearEmailSuppression(String id, EmailSuppressionView.ClearRequest request) {
        requireEmailIntegrationForEdit(id);
        if (request == null || request.address() == null || request.address().isBlank()) {
            throw new BadRequestException("The body must name the address to clear: {\"address\": \"...\"}.");
        }
        return emailSupport().clearSuppression(id, request.address())
                ? Response.noContent().build()
                : Response.status(Response.Status.NOT_FOUND).build();
    }

    @Override
    public EmailSenderLists.View readEmailSenders(String id) {
        restVersionInfo.requireViewAccess(id);
        int version = currentVersion(id);
        ChannelIntegrationConfiguration current = readEmailIntegration(id, version);
        return EmailSenderLists.read(version, current.getPlatformConfig());
    }

    /**
     * A targeted edit of the sender lists (email plan v3 §5.1). The read, the
     * version check and the write are one step under {@link #uniquenessLock}, and
     * the write is {@link #updateChannel} itself: the same validation, deployment
     * checks, warnings and poll re-registration as a save of the whole document.
     * The document is read from the store, not through {@link #readChannel}, whose
     * redacted copy the redaction-marker guard would refuse to store.
     */
    @Override
    public Response updateEmailSenders(String id, EmailSenderLists.UpdateRequest request) {
        if (request == null || request.expectedVersion() == null) {
            throw new BadRequestException("The body must name the version it was read from: {\"expectedVersion\": <n>, ...}.");
        }
        restVersionInfo.requireEditAccess(id);
        synchronized (uniquenessLock) {
            int version = currentVersion(id);
            ChannelIntegrationConfiguration current = readEmailIntegration(id, version);
            EmailSenderLists.View before = EmailSenderLists.read(version, current.getPlatformConfig());
            if (request.expectedVersion() != version) {
                return Response.status(Response.Status.CONFLICT).type(MediaType.APPLICATION_JSON).entity(before).build();
            }
            EmailSenderLists.Change change;
            try {
                change = EmailSenderLists.apply(before.allow(), before.block(), request);
            } catch (EmailSenderLists.InvalidChange e) {
                throw new BadRequestException(e.getMessage());
            }
            if (!change.changed()) {
                return Response.ok(new EmailSenderLists.UpdateResult(version, version, false, before.allow(), before.block(), List.of(),
                        List.of(), change.unchanged(), List.of())).build();
            }
            current.setPlatformConfig(EmailSenderLists.withLists(current.getPlatformConfig(), change.allow(), change.block()));
            Response saved = updateChannel(id, version, current);
            var location = saved.getLocation() == null ? null : RestUtilities.extractResourceId(saved.getLocation());
            Integer newVersion = location == null ? null : location.getVersion();
            List<String> warnings = new ArrayList<>();
            if (change.allowlistEmptied()) {
                warnings.add(EmailSenderLists.WARNING_ALLOWLIST_EMPTIED);
            }
            String header = saved.getHeaderString(WARNINGS_HEADER);
            if (header != null && !header.isBlank()) {
                for (String code : header.split(",")) {
                    if (!code.isBlank() && !warnings.contains(code.trim())) {
                        warnings.add(code.trim());
                    }
                }
            }
            emailSupport().auditSenderListChange(id, version, newVersion, change.added(), change.removed(),
                    EmailSenderLists.cleanReason(request.reason()), change.allowlistEmptied());
            var result = new EmailSenderLists.UpdateResult(version, newVersion, true, change.allow(), change.block(), change.added(),
                    change.removed(), change.unchanged(), warnings);
            Response.ResponseBuilder answer = Response.ok(result).type(MediaType.APPLICATION_JSON);
            if (saved.getLocation() != null) {
                answer.location(saved.getLocation());
            }
            if (!warnings.isEmpty()) {
                answer.header(WARNINGS_HEADER, String.join(",", warnings));
            }
            return answer.build();
        }
    }

    private int currentVersion(String id) {
        try {
            IResourceStore.IResourceId currentId = channelStore.getCurrentResourceId(id);
            if (currentId == null || currentId.getVersion() == null) {
                throw new NotFoundException("No channel integration " + id + ".");
            }
            return currentId.getVersion();
        } catch (IResourceStore.ResourceNotFoundException e) {
            throw new NotFoundException("No channel integration " + id + ".");
        }
    }

    private ChannelIntegrationConfiguration readEmailIntegration(String id, int version) {
        ChannelIntegrationConfiguration current;
        try {
            current = channelStore.read(id, version);
        } catch (IResourceStore.ResourceNotFoundException e) {
            throw new NotFoundException("No channel integration " + id + ".");
        } catch (IResourceStore.ResourceStoreException e) {
            throw new InternalServerErrorException("The channel store could not be read.");
        }
        if (!isEmail(current)) {
            throw new NotFoundException("Channel integration " + id + " is not an email integration.");
        }
        return current;
    }

    /**
     * The suppression list belongs to the integration: whoever may edit it may read
     * and clear it. A channel that is not an email integration has none (404).
     */
    private void requireEmailIntegrationForEdit(String id) {
        restVersionInfo.requireEditAccess(id);
        ChannelIntegrationConfiguration current;
        try {
            current = channelStore.read(id, channelStore.getCurrentResourceId(id).getVersion());
        } catch (IResourceStore.ResourceNotFoundException e) {
            throw new NotFoundException("No channel integration " + id + ".");
        } catch (IResourceStore.ResourceStoreException e) {
            throw new InternalServerErrorException("The channel store could not be read.");
        }
        if (!isEmail(current)) {
            throw new NotFoundException("Channel integration " + id + " is not an email integration.");
        }
    }

    @Override
    public String getResourceURI() {
        return restVersionInfo.getResourceURI();
    }

    @Override
    public void requireCurrentVersionAccess(String id) {
        restVersionInfo.requireViewAccess(id);
    }

    @Override
    public IResourceStore.IResourceId getCurrentResourceId(String id)
            throws IResourceStore.ResourceNotFoundException {
        return channelStore.getCurrentResourceId(id);
    }

    // ─── Validation ────────────────────────────────────────────────────────────

    /**
     * {@code platformConfig} keys that hold credentials.
     * <p>
     * A channel's bot token or signing secret is stored exactly as it was written,
     * and an administrator reading
     * {@code GET /channelstore/channels/&#123;id&#125;} gets it back as stored
     * (everyone else reads a redacted copy, see {@link #readChannel}) — so a
     * plaintext value is readable by administrators, and lands in ZIP exports and
     * backups too. {@code ChannelTargetRouter} resolves {@code ${vault:...}}
     * references at send time, so the vault is available here; nothing was telling
     * operators to use it.
     */
    private static final Set<String> SECRET_PLATFORM_CONFIG_KEYS = Set.of(
            "bottoken", "signingsecret", "clientsecret", "apikey", "apisecret", "token",
            "password", "webhooksecret", "appsecret", "verificationtoken");

    /**
     * Warns — rather than rejects — when a credential is written in plaintext.
     * <p>
     * Rejecting would break every existing integration on its next update, and
     * whether a value is a secret is a guess based on its key name. A log line at
     * write time is the honest amount of certainty: it names the key, points at the
     * vault, and leaves the operator's configuration working.
     */
    private void warnOnPlaintextSecrets(ChannelIntegrationConfiguration config) {
        var platformConfig = config.getPlatformConfig();
        if (platformConfig == null || platformConfig.isEmpty()) {
            return;
        }
        for (var entry : platformConfig.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (key == null || !(value instanceof String text) || text.isBlank()) {
                continue;
            }
            if (SECRET_PLATFORM_CONFIG_KEYS.contains(key.toLowerCase(Locale.ROOT)) && !text.contains("${vault:")) {
                LOG.warnf("Channel integration '%s' stores platformConfig.%s in plaintext — it is returned verbatim by "
                        + "GET /channelstore/channels/{id} and included in backups. Store it in the secrets vault and "
                        + "reference it as ${vault:<key>} instead; the router resolves that at send time.",
                        sanitize(config.getName()), sanitize(key));
            }
        }
    }

    void validateConfiguration(ChannelIntegrationConfiguration config) {
        // A document read through the redacted view must not be stored with the
        // marker in place of the credential — see RedactionMarkerGuard.
        RedactionMarkerGuard.requireNone(config);
        if (config.getName() == null || config.getName().isBlank()) {
            throw new BadRequestException("Channel integration name is required.");
        }
        // '|' separates the integration name from the subject and card id in a Slack
        // approval button value (<integration>|<subject>|<cardId>). A name containing
        // it is split at the wrong place, so every decision on that integration's
        // cards would be resolved against another integration name and refused. A
        // configuration stored before this rule still loads; its approval cards are
        // posted without buttons (SlackHitlSupport.isBindableIntegrationName).
        if (config.getName().contains("|")) {
            throw new BadRequestException("Channel integration name must not contain '|'.");
        }

        warnOnPlaintextSecrets(config);

        // Channel type must be a registered adapter
        String channelType = config.getChannelType();
        if (channelType == null || channelType.isBlank()) {
            throw new BadRequestException("Channel type is required.");
        }
        ChannelTypeDescriptor descriptor = channelTypes.find(channelType)
                .orElseThrow(() -> new BadRequestException(
                        "Unknown channel type: '" + channelType + "'. Registered types: "
                                + channelTypes.ids()));

        // At least one target
        List<ChannelTarget> targets = config.getTargets();
        if (targets == null || targets.isEmpty()) {
            throw new BadRequestException("At least one target is required.");
        }

        // The type's own rules (webhook, email) and, for a connection-backed type,
        // the allow-list of non-secret keys and the named connection.
        List<String> platformErrors = ChannelTypeRegistry.validatePlatformConfig(descriptor, config.getPlatformConfig());
        if (!platformErrors.isEmpty()) {
            channelTypes.recordRejected(descriptor.id(), "platform_config");
            throw new BadRequestException(String.join(" ", platformErrors));
        }
        if (WebhookChannelSettings.CHANNEL_TYPE.equals(descriptor.id())) {
            requireUsableWebhookSecret(config.getPlatformConfig());
        }
        if (channelCredentials != null) {
            List<String> connectionErrors = channelCredentials.validateForSave(descriptor, config.getPlatformConfig());
            if (!connectionErrors.isEmpty()) {
                throw new BadRequestException(String.join(" ", connectionErrors));
            }
        }

        // Default target must reference an existing target
        String defaultName = config.getDefaultTargetName();
        if (defaultName == null || defaultName.isBlank()) {
            throw new BadRequestException("Default target name is required.");
        }
        boolean defaultFound = targets.stream()
                .anyMatch(t -> t.getName() != null
                        && t.getName().equalsIgnoreCase(defaultName));
        if (!defaultFound) {
            throw new BadRequestException(
                    "Default target '" + defaultName
                            + "' does not match any target name.");
        }

        // No duplicate target names or trigger keywords across targets
        Set<String> usedNames = new HashSet<>();
        Set<String> allTriggers = new HashSet<>();
        for (ChannelTarget target : targets) {
            if (target.getName() == null || target.getName().isBlank()) {
                throw new BadRequestException("Every target must have a name.");
            }
            if (!usedNames.add(target.getName().toLowerCase(Locale.ROOT))) {
                throw new BadRequestException(
                        "Duplicate target name: '" + target.getName()
                                + "'. Each target must have a unique name.");
            }
            if (target.getTargetId() == null || target.getTargetId().isBlank()) {
                throw new BadRequestException(
                        "Target '" + target.getName() + "' must have a targetId.");
            }
            if (!descriptor.supportsObserveMode() && target.isObserveMode()) {
                // An observer watches chat traffic it was not addressed in. A mailbox or
                // a webhook has no such traffic — every mail and event is addressed to
                // the integration — so the flag could only ever be a mistake.
                throw new BadRequestException(
                        "Target '" + target.getName() + "': observeMode is not supported for " + descriptor.id()
                                + " integrations.");
            }
            if (target.isObserveMode()) {
                // An observer answers channel traffic it was never addressed in, and
                // the guard against that becoming expensive is a per-turn cost the
                // engine can only attribute to a 1:1 conversation. A GROUP observer
                // would start a whole multi-agent discussion off unaddressed chatter
                // with its dollar ceiling unenforceable — refuse it here rather than
                // ship a control that silently does not apply.
                if (target.getType() != ChannelTarget.TargetType.AGENT) {
                    throw new BadRequestException(
                            "Target '" + target.getName()
                                    + "': observeMode is only supported for AGENT targets.");
                }
                // Defaulted rather than rejected: `observeMode: true` with no config
                // would mean no cooldown and no caps, which is the one shape an
                // observer must never be saved in. The defaults are the documented
                // ones on ObserveConfig.
                if (target.getObserveConfig() == null) {
                    target.setObserveConfig(new ObserveConfig());
                }
                // An observer watches traffic it was not part of. Naming it the
                // default makes it the answer to "the bot was mentioned and no
                // trigger matched" as well, so one target would answer both
                // addressed and unaddressed messages under different limits —
                // and the observer's cooldown and caps would not apply to half
                // of what it said.
                if (target.getName().equalsIgnoreCase(config.getDefaultTargetName())) {
                    throw new BadRequestException(
                            "Target '" + target.getName()
                                    + "': an observeMode target cannot also be the default target.");
                }
            }
            if (target.getObserveConfig() != null) {
                var oc = target.getObserveConfig();
                if (oc.getCooldownSeconds() < 0) {
                    throw new BadRequestException(
                            "Target '" + target.getName() + "': cooldownSeconds must be >= 0.");
                }
                // Zero is a valid, meaningful setting on both caps — an observer
                // that is configured but deliberately silent — so only a negative
                // value is a mistake.
                if (oc.getMaxDailyResponses() < 0) {
                    throw new BadRequestException(
                            "Target '" + target.getName() + "': maxDailyResponses must be >= 0.");
                }
                if (oc.getMaxCostPerDay() < 0) {
                    throw new BadRequestException(
                            "Target '" + target.getName() + "': maxCostPerDay must be >= 0.");
                }
                for (String keyword : oc.getTriggerKeywords() == null ? List.<String>of() : oc.getTriggerKeywords()) {
                    if (keyword == null || keyword.isBlank()) {
                        throw new BadRequestException(
                                "Target '" + target.getName()
                                        + "': observeConfig.triggerKeywords contains a null or blank keyword.");
                    }
                }
                for (String mimeType : oc.getTriggerMimeTypes() == null ? List.<String>of() : oc.getTriggerMimeTypes()) {
                    if (mimeType == null || mimeType.isBlank()) {
                        throw new BadRequestException(
                                "Target '" + target.getName()
                                        + "': observeConfig.triggerMimeTypes contains a null or blank type.");
                    }
                }
            }
            if (target.getTriggers() != null) {
                for (String trigger : target.getTriggers()) {
                    if (trigger == null || trigger.isBlank()) {
                        throw new BadRequestException(
                                "Target '" + target.getName()
                                        + "' contains a null or blank trigger keyword.");
                    }
                    String normalized = trigger.toLowerCase(Locale.ROOT).trim();
                    if (RESERVED_TRIGGERS.contains(normalized)) {
                        throw new BadRequestException(
                                "Trigger '" + trigger
                                        + "' is a reserved keyword and cannot be used.");
                    }
                    if (!allTriggers.add(normalized)) {
                        throw new BadRequestException(
                                "Duplicate trigger keyword: '" + trigger
                                        + "'. Each trigger must be unique across all targets.");
                    }
                }
            }
        }
    }

    // ─── Routing key uniqueness ────────────────────────────────────────────────

    /**
     * Reject create/update if another non-deleted integration of the same type
     * already claims one of this integration's routing keys — the keys its
     * {@link ChannelTypeDescriptor} indexes it under in the router: the
     * {@code channelId} for Slack, an application id for a channel-less type.
     * Prevents silent overwrites in the router's index. Fails closed like
     * {@link #validateUniqueName}: a scan that cannot read the integration holding
     * the key refuses the save (503) rather than store a second route for it.
     *
     * @param excludeId
     *            the resource ID of the config being updated (null on create)
     */
    private void validateUniqueRoutingKeys(ChannelIntegrationConfiguration config, String excludeId) {
        String channelType = config.getChannelType();
        if (channelType == null)
            return;
        // Without the resource id, on both sides: a type routed by its own resource id
        // (webhook) cannot collide, and must not pay for a scan of every integration —
        // or fail its update with a 503 when that scan hiccups.
        List<String> keys = channelTypes.routingKeys(channelType, null, config.getPlatformConfig());
        if (keys.isEmpty())
            return;

        forEachOtherIntegration(excludeId, "routing key", existing -> {
            if (existing.getPlatformConfig() == null || !channelType.equalsIgnoreCase(existing.getChannelType())) {
                return;
            }
            List<String> theirs = channelTypes.routingKeys(existing.getChannelType(), null, existing.getPlatformConfig());
            for (String key : keys) {
                if (theirs.contains(key)) {
                    // The sweep is deliberately unscoped: a channelId collides with every
                    // integration in the deployment, not only the caller's own, so scoping it
                    // would let two workspaces both bind the same Slack channel and route each
                    // other's messages. What the message must NOT do is name the conflicting
                    // integration — that would turn a uniqueness check into an enumeration
                    // oracle for other people's channel integrations.
                    channelTypes.recordRejected(channelType, "routing_key_taken");
                    throw new BadRequestException(
                            "Another channel integration already uses " + ChannelTypeDescriptor.describeRoutingKey(key)
                                    + " for type '" + channelType + "'.");
                }
            }
        });
    }

    /**
     * Refuses a {@code channelId} a deployed agent's legacy
     * {@code ChannelConnector} already serves. The router lets a standalone
     * integration take precedence over a legacy connector for the same channel, so
     * saving one silently replaced that agent's routing and signing identity,
     * though its author could not edit the agent. Keeping the channel an
     * integration already had is not a takeover.
     */
    private void refuseLegacyConnectorTakeover(ChannelIntegrationConfiguration config, String previousChannelId) {
        if (channelTargetRouter == null || config.getPlatformConfig() == null) {
            return;
        }
        String channelId = config.getPlatformConfig().get("channelId");
        if (channelId == null || channelId.isBlank() || channelId.equals(previousChannelId)) {
            return;
        }
        // Legacy connectors only: validateUniqueRoutingKeys has already judged the
        // standalone integrations against the store. The router answers from the live
        // deployments, not its cache, and throws when it cannot read them.
        boolean servedByLegacyConnector;
        try {
            servedByLegacyConnector = channelTargetRouter.isServedByLegacyConnector(config.getChannelType(), channelId);
        } catch (IllegalStateException e) {
            LOG.warn("Could not check the deployed agents' legacy channel connectors - refusing save", e);
            throw new ServiceUnavailableException("Could not verify that no deployed agent's channel connector serves channelId '"
                    + channelId + "'. Please retry.");
        }
        if (servedByLegacyConnector) {
            throw new BadRequestException("channelId '" + channelId + "' is already served by an agent's legacy channel connector. "
                    + "Migrate that connector to a channel integration (or remove it) before binding the channel here.");
        }
    }

    private String storedChannelId(String id, Integer version) {
        try {
            ChannelIntegrationConfiguration stored = channelStore.read(id, restVersionInfo.validateParameters(id, version));
            return stored == null || stored.getPlatformConfig() == null ? null : stored.getPlatformConfig().get("channelId");
        } catch (Exception e) {
            return null; // unreadable: judge the channelId as new, the stricter answer
        }
    }

    // ─── Name uniqueness ───────────────────────────────────────────────────────

    /**
     * Reject create/update when another non-deleted integration of the same channel
     * type already uses this (case-insensitive) name.
     * <p>
     * Names bind HITL approval decisions to an owning integration (the approval
     * button value and the approval-record store key on the name, and
     * {@code ChannelTargetRouter.getIntegrationByName} resolves by name). If two
     * integrations shared a name, a decision could authorize against one while the
     * record belonged to the other — so the name must be unique, and an ambiguous
     * lookup already refuses fail-closed (Finding C). A scan that cannot run at all
     * refuses the save (503) rather than skipping the check; a single unreadable
     * entry is skipped, so one corrupt document cannot block every channel write.
     *
     * @param excludeId
     *            the resource id being updated (null on create)
     */
    void validateUniqueName(ChannelIntegrationConfiguration config, String excludeId) {
        String name = config.getName();
        String channelType = config.getChannelType();
        if (name == null || name.isBlank() || channelType == null) {
            return;
        }
        forEachOtherIntegration(excludeId, "name", existing -> {
            if (channelType.equalsIgnoreCase(existing.getChannelType())
                    && name.equalsIgnoreCase(existing.getName())) {
                // Do not name the conflicting integration — a uniqueness check must
                // not become an enumeration oracle for other people's integrations.
                throw new BadRequestException(
                        "Another channel integration of type '" + channelType
                                + "' already uses the name '" + name + "'.");
            }
        });
    }

    /**
     * Hands every stored channel integration except {@code excludeId} to
     * {@code check}, which throws a {@link BadRequestException} on a conflict. The
     * one error policy of the uniqueness scans:
     * <ul>
     * <li>a descriptor whose integration no longer exists is skipped: it cannot
     * hold a conflicting value;</li>
     * <li>a stored document that no longer deserialises is skipped, so one corrupt
     * entry cannot block every channel write on the instance (a duplicate name that
     * slips past it still cannot be used: the router refuses an ambiguous
     * name);</li>
     * <li>any other failure, the descriptors cannot be listed or an entry cannot be
     * read right now, leaves the check inconclusive: the unread entry may hold the
     * very value being saved, so the save is refused with a 503.</li>
     * </ul>
     * The scan and {@link #uniquenessLock} serialise saves on this node only; two
     * nodes saving the same value at the same moment can still both pass.
     *
     * @param excludeId
     *            the resource id being updated (null on create)
     * @param subject
     *            what is checked, for the log and the 503 ({@code "name"},
     *            {@code "routing key"})
     */
    private void forEachOtherIntegration(String excludeId, String subject,
                                         Consumer<ChannelIntegrationConfiguration> check) {
        List<DocumentDescriptor> descriptors;
        try {
            descriptors = documentDescriptorStore.readDescriptors(
                    "ai.labs.channel", "", 0, IDescriptorStore.NO_LIMIT, false);
        } catch (Exception e) {
            throw uniquenessUnverifiable(subject, e);
        }
        for (var descriptor : descriptors) {
            ChannelIntegrationConfiguration existing;
            try {
                var resId = RestUtilities.extractResourceId(descriptor.getResource());
                if (resId == null || resId.getId() == null || resId.getId().equals(excludeId)) {
                    continue;
                }
                existing = channelStore.read(resId.getId(), resId.getVersion());
            } catch (IResourceStore.ResourceNotFoundException e) {
                LOG.debugf("Skipping a missing channel integration during the %s uniqueness check: %s",
                        subject, sanitize(e.getMessage()));
                continue;
            } catch (Exception e) {
                if (!isUnreadableDocument(e)) {
                    throw uniquenessUnverifiable(subject, e);
                }
                LOG.warnf("Skipping an unreadable channel integration during the %s uniqueness check: %s",
                        subject, sanitize(e.getMessage()));
                continue;
            }
            if (existing != null) {
                check.accept(existing);
            }
        }
    }

    private static ServiceUnavailableException uniquenessUnverifiable(String subject, Exception cause) {
        LOG.warnf(cause, "Failed to check channel integration %s uniqueness - refusing save", subject);
        return new ServiceUnavailableException(
                "Could not verify that the channel integration " + subject + " is unique. Please retry.");
    }

    /**
     * Whether a read failure means the stored document itself is unreadable (it no
     * longer deserialises) rather than that the store could not be reached. Only
     * the former is safe to skip in the uniqueness scan.
     */
    private static boolean isUnreadableDocument(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof JsonProcessingException) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }

    /**
     * A copy name that does not collide with an existing one: "{name} (copy)", then
     * "{name} (copy 2)", "{name} (copy 3)", … until one is free. The names in use
     * are read in ONE scan; the caller's {@link #validateUniqueName} stays the
     * authoritative check.
     */
    private String deriveUniqueDuplicateName(String baseName, String channelType) {
        Set<String> used = namesInUseLowerCase(channelType);
        String candidate = baseName + " (copy)";
        for (int n = 2; used.contains(candidate.toLowerCase(Locale.ROOT)) && n < 1000; n++) {
            candidate = baseName + " (copy " + n + ")";
        }
        return candidate;
    }

    /**
     * The lower-cased names of every integration of {@code channelType}, read with
     * the error policy of {@link #validateUniqueName}, which stays the
     * authoritative check: a scan that cannot read an entry refuses the duplicate
     * (503) here rather than one step later.
     */
    private Set<String> namesInUseLowerCase(String channelType) {
        Set<String> used = new HashSet<>();
        if (channelType == null) {
            return used;
        }
        forEachOtherIntegration(null, "name", existing -> {
            if (existing.getName() != null && channelType.equalsIgnoreCase(existing.getChannelType())) {
                used.add(existing.getName().toLowerCase(Locale.ROOT));
            }
        });
        return used;
    }

    // ─── Descriptor sync ───────────────────────────────────────────────────────

    /**
     * Sync the channel config's name onto the DocumentDescriptor so that the
     * descriptors endpoint returns meaningful display information.
     */
    private void syncDescriptor(String resourceId,
                                ChannelIntegrationConfiguration config) {
        try {
            var currentResourceId = channelStore.getCurrentResourceId(resourceId);
            writeDescriptor(resourceId, currentResourceId.getVersion(), config);
        } catch (Exception e) {
            LOG.warnf(e, "Failed to sync channel descriptor for id=%s",
                    sanitizeForLog(resourceId));
        }
    }

    /**
     * Creates or updates the descriptor of {@code resourceId} at {@code version}
     * (or at {@code version - 1}, where an update's descriptor still lives until
     * the descriptor filter promotes it). Throws on failure; the caller decides
     * whether that is fatal.
     */
    private void writeDescriptor(String resourceId, int version, ChannelIntegrationConfiguration config)
            throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException {
        // Use channelType as description for quick identification in lists
        String desc = config.getChannelType() != null
                ? config.getChannelType() + " integration"
                : null;

        // Same lookup as RestAgentGroupStore.syncDescriptor. On CREATE the
        // descriptor does not exist yet — DocumentDescriptorFilter writes it after
        // this method runs — and on UPDATE it still lives at version-1 until the
        // filter promotes it. Reading only the current version failed on both paths,
        // the caller's catch swallowed that, and every channel descriptor kept an empty
        // name, so list_channel_integrations' name filter could never match.
        DocumentDescriptor descriptor = readDescriptorOrNull(resourceId, version);
        int descriptorVersion = version;
        if (descriptor == null && version > 1) {
            descriptor = readDescriptorOrNull(resourceId, version - 1);
            descriptorVersion = version - 1;
        }

        if (descriptor == null) {
            descriptor = new DocumentDescriptor();
            descriptor.setResource(RestUtilities.createURI(resourceURI, resourceId, versionQueryParam, version));
            Date now = new Date(System.currentTimeMillis());
            descriptor.setCreatedOn(now);
            descriptor.setLastModifiedOn(now);
            descriptor.setName(config.getName());
            descriptor.setDescription(desc);
            // Stamped like any newly created resource, or the channel is left unowned.
            resourceAccessGuard.stampNewDescriptor(descriptor);
            try {
                documentDescriptorStore.createDescriptor(resourceId, version, descriptor);
            } catch (IResourceStore.ResourceStoreException raced) {
                // The descriptor filter created it between our lookup and this write.
                documentDescriptorStore.setDescriptor(resourceId, version, descriptor);
            }
            return;
        }

        boolean changed = false;
        if (config.getName() != null
                && !config.getName().equals(descriptor.getName())) {
            descriptor.setName(config.getName());
            changed = true;
        }
        if (desc != null && !desc.equals(descriptor.getDescription())) {
            descriptor.setDescription(desc);
            changed = true;
        }

        if (changed) {
            descriptor.setLastModifiedOn(new Date(System.currentTimeMillis()));
            documentDescriptorStore.setDescriptor(resourceId, descriptorVersion, descriptor);
        }
    }

    /**
     * The descriptor at {@code version}, or {@code null} when there is none yet.
     */
    private DocumentDescriptor readDescriptorOrNull(String resourceId, int version) throws IResourceStore.ResourceStoreException {
        try {
            return documentDescriptorStore.readDescriptor(resourceId, version);
        } catch (IResourceStore.ResourceNotFoundException notYet) {
            return null;
        }
    }

    private static String sanitizeForLog(String value) {
        if (value == null)
            return null;
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\r' || c == '\n' || c == '\t' || c < 0x20 || c == 0x7F) {
                sb.append('_');
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
