/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.secrets;

import ai.labs.eddi.configs.apicalls.IApiCallsStore;
import ai.labs.eddi.configs.channels.IChannelIntegrationStore;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.configs.llm.ILlmStore;
import ai.labs.eddi.configs.mcpcalls.IMcpCallsStore;
import ai.labs.eddi.configs.rag.IRagStore;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.serialization.IDescriptorStore;
import ai.labs.eddi.secrets.model.SecretMetadata;
import ai.labs.eddi.secrets.model.SecretReference;
import ai.labs.eddi.utils.RestUtilities;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * Names, once per boot, every vault secret that a configuration references and
 * that may be sent to any destination.
 * <p>
 * <h3>Why</h3> H1 of the 2026-10-17 security review: the setup wizard vaulted
 * provider keys with no {@code allowedHosts}, and an editor who copied the LLM
 * configuration with their own {@code baseUrl} received the plaintext. The fix
 * (PR #1079) narrows every secret the wizard writes <em>from now on</em>; a
 * secret vaulted before it keeps an open destination until an admin narrows it.
 * Nothing migrates it automatically on purpose — only the admin knows which
 * host a key belongs to, and guessing wrong breaks a working agent — so this
 * check makes the residual visible instead: one {@code [SECURITY]} WARN that
 * lists the secrets and the call that narrows them, and the same list on
 * {@code GET /secretstore/secrets/{tenantId}} ({@code unrestrictedDestination},
 * {@code referencedBy}).
 * <p>
 * <h3>What it reads</h3> The current version of every LLM, MCP-calls, RAG,
 * httpcalls and channel configuration, scanned for {@code ${vault:…}}
 * references, then the metadata of each referenced secret. It runs on a virtual
 * thread after the vault has started, so a large deployment does not slow the
 * boot, and it changes nothing. A reference reached only through
 * {@code ${vars:…}} or {@code ${connection:…}} is not followed, and an old
 * configuration version that is still deployed is not read.
 */
@ApplicationScoped
public class UnrestrictedSecretsCheck {

    private static final Logger LOGGER = Logger.getLogger(UnrestrictedSecretsCheck.class);

    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    /** Pages read per configuration type before the scan gives up on it. */
    private static final int MAX_PAGES = 10_000;

    /**
     * A secret with no destination allow-list and the configurations that reference
     * it.
     */
    public record UnrestrictedSecret(SecretReference secret, List<String> referencedBy) {
    }

    private record ScannedType(String descriptorType, IResourceStore<?> store) {
    }

    private final ISecretProvider secretProvider;
    private final IDocumentDescriptorStore descriptorStore;
    private final List<ScannedType> scannedTypes;

    /** The last scan's result; {@code null} until one has completed. */
    private volatile Map<SecretReference, List<String>> lastScan;

    @Inject
    public UnrestrictedSecretsCheck(ISecretProvider secretProvider, IDocumentDescriptorStore descriptorStore, ILlmStore llmStore,
            IMcpCallsStore mcpCallsStore, IRagStore ragStore, IApiCallsStore apiCallsStore, IChannelIntegrationStore channelStore) {
        this.secretProvider = secretProvider;
        this.descriptorStore = descriptorStore;
        this.scannedTypes = List.of(new ScannedType("ai.labs.llm", llmStore), new ScannedType("ai.labs.mcpcalls", mcpCallsStore),
                new ScannedType("ai.labs.rag", ragStore), new ScannedType("ai.labs.apicalls", apiCallsStore),
                new ScannedType("ai.labs.channel", channelStore));
    }

    /**
     * Default observer priority, which runs after {@code VaultSecretProvider}
     * settles {@link ISecretProvider#isAvailable()}.
     */
    void onStartup(@Observes StartupEvent event) {
        if (!secretProvider.isAvailable()) {
            return;
        }
        Thread.ofVirtual().name("vault-unrestricted-secrets-check").start(this::scanAndWarn);
    }

    /** Scans and logs the one WARN; never throws. */
    void scanAndWarn() {
        try {
            List<UnrestrictedSecret> found = scan();
            if (!found.isEmpty()) {
                LOGGER.warn(warning(found));
            }
        } catch (RuntimeException e) {
            LOGGER.warnf("[SECURITY] Could not check the vault for secrets without a destination allow-list: %s", sanitize(e.getMessage()));
        }
    }

    /**
     * Every referenced secret without {@code allowedHosts}, in the order first
     * referenced. Records the result for {@link #referencedBy}.
     */
    public List<UnrestrictedSecret> scan() {
        Map<SecretReference, Set<String>> references = new LinkedHashMap<>();
        for (ScannedType type : scannedTypes) {
            collectReferences(type, references);
        }
        Map<SecretReference, List<String>> scanned = new LinkedHashMap<>();
        List<UnrestrictedSecret> unrestricted = new ArrayList<>();
        for (var entry : references.entrySet()) {
            List<String> configs = List.copyOf(entry.getValue());
            scanned.put(entry.getKey(), configs);
            SecretMetadata metadata;
            try {
                metadata = secretProvider.getMetadata(entry.getKey());
            } catch (ISecretProvider.SecretNotFoundException e) {
                // A dangling reference fails on its own when it is used; not this check's
                // business.
                continue;
            } catch (ISecretProvider.SecretProviderException e) {
                LOGGER.debugf("Could not read metadata of %s: %s", sanitize(entry.getKey().toReferenceString()), sanitize(e.getMessage()));
                continue;
            }
            if (metadata != null && !SecretMetadata.restrictsHosts(metadata.allowedHosts())) {
                unrestricted.add(new UnrestrictedSecret(entry.getKey(), configs));
            }
        }
        lastScan = scanned;
        return unrestricted;
    }

    /**
     * The configurations the last scan found referencing {@code secret}, or empty
     * when no scan has completed yet. An empty list after a scan means none.
     */
    public Optional<List<String>> referencedBy(SecretReference secret) {
        Map<SecretReference, List<String>> scan = lastScan;
        if (scan == null) {
            return Optional.empty();
        }
        return Optional.of(scan.getOrDefault(secret, List.of()));
    }

    private void collectReferences(ScannedType type, Map<SecretReference, Set<String>> sink) {
        for (int page = 0; page < MAX_PAGES; page++) {
            List<DocumentDescriptor> descriptors;
            try {
                descriptors = descriptorStore.readDescriptors(type.descriptorType(), "", page, IDescriptorStore.NO_LIMIT, false);
            } catch (IResourceStore.ResourceStoreException | IResourceStore.ResourceNotFoundException e) {
                LOGGER.debugf("Could not list %s configurations: %s", type.descriptorType(), sanitize(e.getMessage()));
                return;
            }
            if (descriptors == null || descriptors.isEmpty()) {
                return;
            }
            for (DocumentDescriptor descriptor : descriptors) {
                scanOne(type, descriptor, sink);
            }
        }
    }

    private void scanOne(ScannedType type, DocumentDescriptor descriptor, Map<SecretReference, Set<String>> sink) {
        if (descriptor == null || descriptor.getResource() == null) {
            return;
        }
        String resource = descriptor.getResource().toString();
        try {
            IResourceStore.IResourceId id = RestUtilities.extractResourceId(descriptor.getResource());
            if (id == null || id.getId() == null) {
                return;
            }
            Object config = type.store().read(id.getId(), id.getVersion());
            if (config == null) {
                return;
            }
            Matcher matcher = SecretReference.compiledPattern().matcher(MAPPER.writeValueAsString(config));
            while (matcher.find()) {
                SecretReference reference = SecretReference.parse(matcher.group());
                if (reference != null) {
                    sink.computeIfAbsent(reference, r -> new LinkedHashSet<>()).add(resource);
                }
            }
        } catch (Exception e) {
            LOGGER.debugf("Could not read %s while checking vault destinations: %s", sanitize(resource), sanitize(e.getMessage()));
        }
    }

    /**
     * The one WARN. Names each secret (a key name is configuration, not a secret)
     * and the configurations that reference it, and the call that narrows it.
     */
    static String warning(List<UnrestrictedSecret> found) {
        var text = new StringBuilder("[SECURITY] ").append(found.size())
                .append(" vault secret(s) referenced by configurations have no destination allow-list (allowedHosts), so the vault "
                        + "will send them to whatever host a configuration names — an editor who saves a copy of the "
                        + "configuration pointing at their own server receives the plaintext. Secrets vaulted before allowedHosts "
                        + "was set by the setup wizard keep an open destination until narrowed:");
        for (UnrestrictedSecret secret : found) {
            text.append("\n  - ").append(sanitize(secret.secret().tenantId() + "/" + secret.secret().keyName())).append(" used by ")
                    .append(sanitize(String.join(", ", secret.referencedBy())));
        }
        text.append("\nNarrow each with PUT /secretstore/secrets/{tenantId}/{keyName}/grant and a body such as "
                + "{\"allowedAgents\": [\"*\"], \"allowedHosts\": [\"api.openai.com\"]} (allowedAgents is required and replaces the "
                + "grant; send the secret's current list). GET /secretstore/secrets/{tenantId} marks these entries "
                + "unrestrictedDestination=true with referencedBy.");
        return text.toString();
    }
}
