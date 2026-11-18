/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.secrets;

import ai.labs.eddi.engine.cluster.events.IClusterEventBus;
import ai.labs.eddi.engine.cluster.events.ClusterEvent;
import ai.labs.eddi.engine.lifecycle.exceptions.AgentConfigurationRefusal;
import ai.labs.eddi.secrets.model.ReferenceScanner;
import ai.labs.eddi.secrets.model.SecretMetadata;
import ai.labs.eddi.secrets.model.SecretReference;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Resolves vault references in parameter maps and strings. Supports both the
 * <b>short form</b> ({@code ${vault:keyName}}) and <b>full form</b>
 * ({@code ${vault:tenantId/keyName}}).
 * <p>
 * This is the central integration point between the vault and the execution
 * workflow. It is called <b>after</b> Qute template processing and
 * <b>before</b> the final API call (late-binding resolution).
 * <p>
 * <b>Access model:</b> every resolution names a {@link SecretAccess}, on whose
 * behalf the value is handed out, and the secret's agent grant
 * ({@code SecretMetadata.allowedAgents}) is checked against it here, on every
 * resolution, before the cache is consulted. The deploy-time gate
 * ({@link VaultGrantGate}) still runs and still reports a misconfiguration
 * before an agent serves traffic; this check is what makes the grant hold on a
 * cluster, where a deployment can pass the gate on one node after a grant was
 * written on another, or be made with {@code autoDeploy=false} where no shared
 * record names it (#1179). Follows {@code eddi.vault.grant-enforcement}:
 * {@code enforce} refuses, {@code warn} logs once per secret and asker and
 * resolves, {@code off} does not read the grant.
 * <p>
 * Includes two Caffeine caches with the same TTL: the plaintext, and the grant.
 * Both are keyed on the secret alone. The grant is checked for the asking
 * access on every resolution, so a value cached for one agent is never handed
 * to another the grant leaves out. Both are invalidated by
 * {@link #invalidateCache(SecretReference)}, which every value and grant write
 * calls and which reaches the other nodes of a cluster. Failed resolutions
 * (not-found, provider errors) are <b>never cached</b> so that newly created
 * secrets resolve immediately.
 * <p>
 * Callers that keep a client built from resolved values in a cache of their own
 * (models, embedding stores, MCP and A2A clients, connection credentials) call
 * {@link #requireGranted} before their own lookup, for the same reason.
 *
 * @author ginccc
 * @since 6.0.0
 */
@ApplicationScoped
public class SecretResolver {

    private static final Logger LOGGER = Logger.getLogger(SecretResolver.class);
    /**
     * The linear-time finder, not {@code SecretReference.compiledPattern()}: the
     * regex's {@code find()} rescans the rest of a value at every unterminated
     * opener, and these values are editor-written parameters.
     */
    private static final ReferenceScanner VAULT_REFERENCES = SecretReference.scanner();

    private final ISecretProvider secretProvider;
    private final MeterRegistry meterRegistry;
    private final int cacheTtlMinutes;
    private final int cacheMaxSize;

    private final VaultGrantGate.Mode grantEnforcement;

    private Cache<String, String> cache;

    /**
     * The grant of each secret, canonicalised ({@code ["*"]} for open). Separate
     * from the plaintext so {@link #requireGranted} can answer a caller whose own
     * cache holds the built client, without decrypting anything.
     */
    private Cache<String, List<String>> grantCache;

    /**
     * The {@code warn}-mode log lines already written, one per secret and asker, so
     * a turn loop does not repeat the same line on every resolution.
     */
    private Cache<String, Boolean> grantWarnings;

    /**
     * Bumped by every invalidation, so a resolution that was in flight across one
     * can tell that the value it read may be stale; see {@code resolveReference}.
     */
    private final AtomicLong invalidations = new AtomicLong();

    /**
     * Listeners notified when cached secrets are invalidated. Used by downstream
     * caches (e.g., ChatModelRegistry) to evict stale model instances that were
     * built with old API keys. Receives the specific {@link SecretReference} that
     * changed, or {@code null} when all secrets are invalidated (e.g., DEK/KEK
     * rotation). Thread-safe via CopyOnWriteArrayList.
     */
    private final List<Consumer<SecretReference>> invalidationListeners = new CopyOnWriteArrayList<>();

    /**
     * Cluster mode: tells the other nodes that a secret rotated here, so they drop
     * their cached plaintext and every model built with it. A no-op on a single
     * node. Field-injected so the tests that build this class with {@code new} keep
     * working; null there.
     */
    @Inject
    IClusterEventBus clusterEvents;

    // ─── Metrics ───
    private Counter cacheHitCounter;
    private Counter cacheMissCounter;
    private Counter resolveErrorCounter;
    private Counter grantRefusalCounter;
    private Timer resolveTimer;

    @Inject
    public SecretResolver(ISecretProvider secretProvider, MeterRegistry meterRegistry,
            @ConfigProperty(name = "eddi.vault.cache-ttl-minutes", defaultValue = "5") int cacheTtlMinutes,
            @ConfigProperty(name = "eddi.vault.cache-max-size", defaultValue = "1000") int cacheMaxSize,
            @ConfigProperty(name = "eddi.vault.grant-enforcement", defaultValue = VaultGrantGate.DEFAULT_MODE_NAME) String grantEnforcement) {
        this.secretProvider = secretProvider;
        this.meterRegistry = meterRegistry;
        this.cacheTtlMinutes = cacheTtlMinutes;
        this.cacheMaxSize = cacheMaxSize;
        // Parsed here, strictly, as the deploy gate does: a typo must fail startup,
        // not quietly turn the check off.
        this.grantEnforcement = VaultGrantGate.Mode.parseStrict(grantEnforcement);
    }

    /** With the shipped grant enforcement ({@code enforce}). */
    public SecretResolver(ISecretProvider secretProvider, MeterRegistry meterRegistry, int cacheTtlMinutes, int cacheMaxSize) {
        this(secretProvider, meterRegistry, cacheTtlMinutes, cacheMaxSize, VaultGrantGate.DEFAULT_MODE_NAME);
    }

    /**
     * Builds the caches and metrics. Public so that the tests of a collaborator can
     * build a real resolver, grant checks included, with {@code new}.
     */
    @PostConstruct
    public void init() {
        this.cache = Caffeine.newBuilder().expireAfterWrite(Duration.ofMinutes(cacheTtlMinutes)).maximumSize(cacheMaxSize).build();
        this.grantCache = Caffeine.newBuilder().expireAfterWrite(Duration.ofMinutes(cacheTtlMinutes)).maximumSize(cacheMaxSize).build();
        this.grantWarnings = Caffeine.newBuilder().expireAfterWrite(Duration.ofHours(1)).maximumSize(cacheMaxSize).build();

        this.cacheHitCounter = meterRegistry.counter("eddi.vault.cache.hits");
        this.cacheMissCounter = meterRegistry.counter("eddi.vault.cache.misses");
        this.resolveErrorCounter = meterRegistry.counter("eddi.vault.resolve.errors");
        this.grantRefusalCounter = meterRegistry.counter("eddi.vault.grant.refusals");
        this.resolveTimer = meterRegistry.timer("eddi.vault.resolve.time");

        if (clusterEvents != null) {
            clusterEvents.subscribe(ClusterEvent.SECRET_CHANGED, event -> {
                if (Boolean.TRUE.equals(event.get("all"))) {
                    invalidateAllLocal();
                } else if (event.getString("tenantId") != null && event.getString("keyName") != null) {
                    invalidateLocal(new SecretReference(event.getString("tenantId"), event.getString("keyName")));
                }
            });
            clusterEvents.onResync(this::invalidateAllLocal);
        }

        if (secretProvider.isAvailable()) {
            LOGGER.infof("SecretResolver initialized (cache TTL=%dmin, maxSize=%d)", cacheTtlMinutes, cacheMaxSize);
        } else {
            LOGGER.info("SecretResolver initialized in passthrough mode (vault not configured)");
        }
    }

    /**
     * Resolve all vault references in a parameter map. Values not containing vault
     * references pass through unchanged.
     * <p>
     * If the vault is not available, all values pass through unchanged (backward
     * compatibility with plaintext configs).
     *
     * @param params
     *            the parameter map (may contain ${vault:...} values)
     * @param access
     *            on whose behalf the values are resolved; see {@link SecretAccess}
     * @return a new map with vault references replaced by plaintext values
     * @throws SecretNotGrantedException
     *             when a referenced secret's grant does not admit {@code access}
     *             (under {@code eddi.vault.grant-enforcement=enforce})
     */
    public Map<String, String> resolveSecrets(Map<String, String> params, SecretAccess access) {
        if (params == null || params.isEmpty()) {
            return params;
        }

        if (!secretProvider.isAvailable()) {
            return params; // Passthrough — vault not configured
        }

        var resolved = new HashMap<>(params);
        resolved.replaceAll((key, value) -> resolveValue(value, access));
        return resolved;
    }

    /**
     * Fail closed on a vault reference that survived resolution.
     * <p>
     * {@link #resolveSecrets} leaves a reference it cannot resolve in place (the
     * secret does not exist, the provider failed, or the vault is not configured at
     * all). For a parameter map about to configure an outbound client — an LLM,
     * embedding model or vector store — that means the literal
     * {@code ${vault:name}} is sent to the provider as the credential, and the
     * failure surfaces as the provider's "invalid API key" instead of EDDI's own,
     * actionable reason. Call this on the RESOLVED map before building the client.
     * <p>
     * The message names the parameter and the reference, never a value: a reference
     * is a name, not a secret.
     *
     * @param resolvedParams
     *            the output of {@link #resolveSecrets}
     * @param usedFor
     *            what the parameters configure, for the message (e.g.
     *            {@code "embedding model 'openai'"})
     * @return {@code resolvedParams}, for chaining
     * @throws UnresolvedSecretReferenceException
     *             when any value still carries a vault reference
     */
    public static Map<String, String> requireResolved(Map<String, String> resolvedParams, String usedFor) {
        if (resolvedParams == null) {
            return null;
        }
        for (var entry : resolvedParams.entrySet()) {
            if (SecretReference.isVaultReference(entry.getValue())) {
                ReferenceScanner.Match match = VAULT_REFERENCES.findFirst(entry.getValue());
                String reference = match != null ? match.text() : "a vault reference";
                throw new UnresolvedSecretReferenceException(String.format(
                        "Parameter '%s' of %s references %s, which could not be resolved — the secret does not exist, the vault "
                                + "failed, or the vault is not configured (EDDI_VAULT_MASTER_KEY). Refusing to send the "
                                + "unresolved reference to the provider.",
                        entry.getKey(), usedFor, SecretReference.describe(reference)));
            }
        }
        return resolvedParams;
    }

    /**
     * A vault reference is still present after resolution. See
     * {@link #requireResolved}.
     */
    public static class UnresolvedSecretReferenceException extends IllegalStateException {
        public UnresolvedSecretReferenceException(String message) {
            super(message);
        }
    }

    /**
     * Why {@link #resolveValue} left a reference in place. Only
     * {@link #PROVIDER_FAILED} can be the server's fault; the others are fixed in
     * the configuration or the vault.
     */
    public enum UnresolvedReason {
        /** No vault is configured ({@code EDDI_VAULT_MASTER_KEY} unset). */
        VAULT_NOT_CONFIGURED,
        /** The name is in an EDDI-internal namespace no configuration may read. */
        RESERVED,
        /** No secret of that name exists in the tenant the reference names. */
        NOT_FOUND,
        /** The secret exists, or may, but the vault could not be read. */
        PROVIDER_FAILED
    }

    /**
     * Why {@code reference} did not resolve, for an error message and for telling a
     * configuration error from a vault outage. Reads the secret's metadata only,
     * never its value, and only on the failure path. A secret that exists now
     * counts as {@link UnresolvedReason#PROVIDER_FAILED}: either the vault could
     * not decrypt it, or it was created since — neither is the configuration's
     * fault.
     */
    public UnresolvedReason diagnoseUnresolved(SecretReference reference) {
        if (!secretProvider.isAvailable()) {
            return UnresolvedReason.VAULT_NOT_CONFIGURED;
        }
        if (SecretReference.isReservedKeyName(reference.keyName())) {
            return UnresolvedReason.RESERVED;
        }
        try {
            secretProvider.getMetadata(reference);
            return UnresolvedReason.PROVIDER_FAILED;
        } catch (ISecretProvider.SecretNotFoundException e) {
            return UnresolvedReason.NOT_FOUND;
        } catch (ISecretProvider.SecretProviderException | RuntimeException e) {
            return UnresolvedReason.PROVIDER_FAILED;
        }
    }

    /**
     * The exception for a request that would carry an unresolved vault reference: a
     * {@link MisconfiguredVaultReferenceException} when {@code reason} says the
     * configuration (or the vault's contents) is at fault, a plain
     * {@link IllegalArgumentException} otherwise — a vault outage is not a
     * misconfiguration, and must not be reported as one. The message names the
     * reference and the tenant it was looked up in, never a value.
     *
     * @param location
     *            where the reference sits, e.g. {@code "header 'X-Api-Key'"}
     * @param reference
     *            the reference as written
     * @param reason
     *            from {@link #diagnoseUnresolved}; {@code null} when unknown
     */
    public static IllegalArgumentException unresolvedReferenceFailure(String location, String reference, UnresolvedReason reason) {
        String prefix = location + " references " + SecretReference.describe(reference) + ", which could not be resolved";
        String suffix = ". Refusing to send the unresolved reference.";
        if (reason == null) {
            return new IllegalArgumentException(prefix
                    + " — the secret does not exist, the vault failed, or the vault is not configured (EDDI_VAULT_MASTER_KEY)" + suffix);
        }
        String message = prefix + switch (reason) {
            case VAULT_NOT_CONFIGURED -> ": the vault is not configured (set EDDI_VAULT_MASTER_KEY)";
            case RESERVED -> ": the name is reserved for EDDI-internal keys and cannot be referenced from a configuration";
            case NOT_FOUND -> notFoundExplanation(reference);
            case PROVIDER_FAILED -> ": the vault could not read it (see the server log)";
        } + suffix;
        return reason == UnresolvedReason.PROVIDER_FAILED ? new IllegalArgumentException(message) : new MisconfiguredVaultReferenceException(message);
    }

    private static String notFoundExplanation(String reference) {
        ReferenceScanner.Match match = VAULT_REFERENCES.findFirst(reference);
        if (match == null) {
            return ": no such secret exists";
        }
        SecretReference ref = SecretReference.of(match);
        String explanation = ": no secret named '" + ref.keyName() + "' exists in tenant '" + ref.tenantId() + "'";
        if (match.tenant() == null) {
            explanation += ". A reference without a tenant always looks in tenant '" + SecretReference.DEFAULT_TENANT
                    + "'; for a secret stored in another tenant write ${vault:<tenant>/" + ref.keyName() + "}";
        }
        return explanation;
    }

    /**
     * A vault reference in a configuration that cannot be resolved because of the
     * configuration or the vault's contents — no such secret in the tenant looked
     * up, a reserved name, or no vault at all — rather than because the vault
     * failed. An {@link IllegalArgumentException}, as the unmarked refusal it
     * replaces was. See {@link #unresolvedReferenceFailure}.
     */
    public static class MisconfiguredVaultReferenceException extends IllegalArgumentException implements AgentConfigurationRefusal {
        public MisconfiguredVaultReferenceException(String message) {
            super(message);
        }

        public MisconfiguredVaultReferenceException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Resolve vault references in a single string value. Supports both the short
     * form ({@code ${vault:keyName}}) and full form
     * ({@code ${vault:tenantId/keyName}}), and multiple references within the same
     * string.
     * <p>
     * If the vault is not available, the value passes through unchanged.
     * <p>
     * <b>Caching strategy:</b> Only successful resolutions are cached. Failed
     * resolutions (secret not found or provider error) are <b>never cached</b>,
     * ensuring that newly created secrets resolve immediately without waiting for
     * cache expiry.
     *
     * @param value
     *            the value that may contain ${vault:...} references
     * @param access
     *            on whose behalf the value is resolved; see {@link SecretAccess}.
     *            {@code null} is read as an agent whose id is unknown, which fails
     *            closed
     * @return the value with vault references replaced by plaintext
     * @throws SecretNotGrantedException
     *             when a referenced secret's grant does not admit {@code access}
     *             (under {@code eddi.vault.grant-enforcement=enforce})
     */
    public String resolveValue(String value, SecretAccess access) {
        if (value == null || !SecretReference.isVaultReference(value)) {
            return value;
        }

        if (!secretProvider.isAvailable()) {
            return value; // Passthrough
        }

        SecretAccess asking = access != null ? access : SecretAccess.agent(null);
        return VAULT_REFERENCES.replaceAll(value, match -> resolveReference(match, asking));
    }

    /**
     * The plaintext for one reference found by {@link #resolveValue}, or the
     * reference itself when it may not or cannot be resolved.
     */
    private String resolveReference(ReferenceScanner.Match match, SecretAccess access) {
        String fullMatch = match.text();
        SecretReference ref = SecretReference.of(match);

        // Refuse EDDI-internal namespaces (e.g. agents' Ed25519 signing keys).
        // These are granted to the agent itself, so the deploy-time grant check
        // would pass a config that referenced one — but the only legitimate reader
        // goes through ISecretProvider directly, never through this resolver. Left
        // in place unresolved (never sent as a credential) and logged, so an LLM
        // parameter carrying one fails closed via requireResolved().
        if (SecretReference.isReservedKeyName(ref.keyName())) {
            LOGGER.warnf("Refusing to resolve reserved vault reference %s via a configuration/template — this namespace is "
                    + "readable only by the internal service that owns it", fullMatch);
            resolveErrorCounter.increment();
            return fullMatch;
        }

        // The grant BEFORE the cache: the cache is keyed on the secret, not on who
        // asks, so a value one agent put there must not reach another that the
        // grant leaves out (#1179).
        if (!grantAdmits(ref, access, null)) {
            // Absent secret: left in place exactly as a failed resolution is.
            LOGGER.warnf("Vault reference not found: %s — passing through unchanged", fullMatch);
            resolveErrorCounter.increment();
            return fullMatch;
        }

        String cacheKey = cacheKey(ref);

        // Check cache first — only successful resolutions are cached
        String resolved = cache.getIfPresent(cacheKey);
        if (resolved != null) {
            cacheHitCounter.increment();
            return resolved;
        }
        cacheMissCounter.increment();
        long generationBefore = invalidations.get();
        resolved = resolveFromProvider(ref, fullMatch);
        if (resolved == null) {
            // Leave the original reference in place if resolution failed
            return fullMatch;
        }
        // Only cache successful resolutions — never cache failures
        cache.put(cacheKey, resolved);
        if (invalidations.get() != generationBefore) {
            // An invalidation (a delete or rotation) ran while this value was being
            // read, so it may be the old one. Without this, the put above re-inserted
            // a deleted or rotated value that then lived for the whole TTL. The
            // invalidation bumps the counter BEFORE evicting, so either this check
            // sees it, or the eviction lands after the put.
            cache.invalidate(cacheKey);
        }
        return resolved;
    }

    /**
     * Refuse {@code access} every vault reference in {@code references} whose grant
     * does not admit it, without resolving anything.
     * <p>
     * For a caller that keeps a client built from resolved values in a cache of its
     * own: a chat or embedding model, an embedding store, an MCP or A2A client, a
     * connection's credential. Such a cache is keyed on the configuration, so a hit
     * never reaches {@link #resolveValue} and would hand one agent a client built
     * with a secret granted to another. Call this before that lookup.
     * <p>
     * A secret that does not exist is refused for an access the grant governs: it
     * has no grant to read, and a cached client may still carry its old value. A
     * reserved name is skipped; {@link #resolveValue} refuses it outright. With the
     * vault not configured, or {@code eddi.vault.grant-enforcement=off}, this does
     * nothing.
     *
     * @param references
     *            the {@code ${vault:…}} strings, e.g. from
     *            {@link VaultHostAllowlist#referencesIn}
     * @param usedFor
     *            what is being built, for the message (e.g. {@code "LLM provider
     *            'openai'"})
     * @throws SecretNotGrantedException
     *             when one is refused; {@link SecretMissingException} when one does
     *             not exist
     */
    public void requireGranted(Iterable<String> references, SecretAccess access, String usedFor) {
        if (references == null || grantEnforcement == VaultGrantGate.Mode.OFF || !secretProvider.isAvailable()) {
            return;
        }
        SecretAccess asking = access != null ? access : SecretAccess.agent(null);
        if (!asking.checksGrant()) {
            return;
        }
        for (String reference : references) {
            SecretReference ref;
            try {
                ref = SecretReference.parse(reference);
            } catch (IllegalArgumentException e) {
                continue; // Not a vault reference: nothing to check.
            }
            if (SecretReference.isReservedKeyName(ref.keyName())) {
                continue;
            }
            if (!grantAdmits(ref, asking, usedFor)) {
                // Not a grant refusal (not counted as one, refused in every mode): there
                // is nothing to admit, and a cached client may still carry the old value.
                throw new SecretMissingException((usedFor != null ? usedFor + " uses " : "") + ref.toReferenceString()
                        + ", which does not exist (it may just have been deleted), so its grant cannot be checked for "
                        + asking.describe() + " and it is refused.");
            }
        }
    }

    /**
     * {@link #requireGranted(Iterable, SecretAccess, String)} for every vault
     * reference in the values of {@code params}.
     */
    public void requireGranted(Map<String, String> params, SecretAccess access, String usedFor) {
        if (params == null || params.isEmpty()) {
            return;
        }
        requireGranted(VaultHostAllowlist.referencesIn(params.values()), access, usedFor);
    }

    /** The configured grant enforcement mode. */
    public VaultGrantGate.Mode grantEnforcement() {
        return grantEnforcement;
    }

    /**
     * Whether {@code access} may have {@code ref}, by its grant.
     *
     * @return {@code false} only when the secret does not exist (the caller decides
     *         what that means); {@code true} when admitted, when the mode or the
     *         access does not check grants, or in {@code warn} mode after logging
     * @throws SecretNotGrantedException
     *             when refused under {@code enforce}, including when the grant
     *             could not be read
     */
    private boolean grantAdmits(SecretReference ref, SecretAccess access, String usedFor) {
        if (grantEnforcement == VaultGrantGate.Mode.OFF || !access.checksGrant()) {
            return true;
        }
        List<String> grant;
        try {
            grant = grantOf(ref);
        } catch (ISecretProvider.SecretNotFoundException e) {
            return false;
        } catch (ISecretProvider.SecretProviderException | RuntimeException e) {
            // Fail closed: a grant that cannot be read is not a grant that admits.
            LOGGER.errorf("Could not read the grant of %s for %s: %s", sanitize(ref.toReferenceString()), sanitize(access.describe()),
                    sanitize(e.getMessage()));
            return refuse(ref, access, usedFor, "its grant could not be read");
        }
        if (access.permits(grant)) {
            return true;
        }
        return refuse(ref, access, usedFor, "its grant (allowedAgents) does not include it");
    }

    /** Refuses under {@code enforce}; logs once and admits under {@code warn}. */
    private boolean refuse(SecretReference ref, SecretAccess access, String usedFor, String why) {
        String message = "Vault secret " + ref.toReferenceString() + " is not resolved for " + access.describe()
                + (usedFor != null ? " (" + usedFor + ")" : "") + ": " + why + ". The grant is checked whenever a secret is resolved, "
                + "not only when an agent is deployed. " + fixFor(ref, access);
        if (grantEnforcement == VaultGrantGate.Mode.WARN) {
            String warnedKey = cacheKey(ref) + "|" + access.kind() + "|" + access.agentId() + "|" + access.purpose();
            if (grantWarnings.asMap().putIfAbsent(warnedKey, Boolean.TRUE) == null) {
                LOGGER.warn(sanitize(message) + " Resolved anyway (eddi.vault.grant-enforcement=warn); set it to 'enforce' to refuse.");
            }
            return true;
        }
        grantRefusalCounter.increment();
        LOGGER.warn(sanitize(message));
        throw new SecretNotGrantedException(message);
    }

    private static String fixFor(SecretReference ref, SecretAccess access) {
        if (access.kind() == SecretAccess.Kind.AGENT && access.agentId() != null) {
            return "Fix (eddi-admin): " + VaultGrantGate.appendEndpoint(ref, access.agentId()) + ", or remove the reference.";
        }
        return "Only a secret granted to every agent (allowedAgents [\"*\"]) may be used here: widen its grant, or use another secret.";
    }

    /** The grant of {@code ref}, from the grant cache or the provider. */
    private List<String> grantOf(SecretReference ref) throws ISecretProvider.SecretNotFoundException, ISecretProvider.SecretProviderException {
        String key = cacheKey(ref);
        List<String> grant = grantCache.getIfPresent(key);
        if (grant != null) {
            return grant;
        }
        long generationBefore = invalidations.get();
        SecretMetadata metadata = secretProvider.getMetadata(ref);
        if (metadata == null) {
            throw new ISecretProvider.SecretProviderException("No metadata returned for " + ref.toReferenceString());
        }
        grant = SecretMetadata.canonicalGrant(metadata.allowedAgents());
        grantCache.put(key, grant);
        if (invalidations.get() != generationBefore) {
            // A grant write raced this read; the same rule as resolveReference's.
            grantCache.invalidate(key);
        }
        return grant;
    }

    private static String cacheKey(SecretReference ref) {
        return ref.tenantId() + "/" + ref.keyName();
    }

    /**
     * A secret's agent grant does not admit the access it was resolved for. Never
     * carries a value: the message names the reference, the asker and the fix.
     */
    public static class SecretNotGrantedException extends IllegalStateException {
        public SecretNotGrantedException(String message) {
            super(message);
        }
    }

    /**
     * {@link #requireGranted} met a reference to a secret that does not exist. A
     * {@link SecretNotGrantedException} so that every caller fails closed on it the
     * same way, but a configuration error rather than a refusal: it is raised in
     * {@code warn} mode too, and is not counted in
     * {@code eddi.vault.grant.refusals}.
     */
    public static class SecretMissingException extends SecretNotGrantedException {
        public SecretMissingException(String message) {
            super(message);
        }
    }

    /**
     * Resolve a single secret reference from the provider, with timing and error
     * handling.
     *
     * @return the plaintext value, or null if resolution failed
     */
    private String resolveFromProvider(SecretReference ref, String fullMatch) {
        return resolveTimer.record(() -> {
            try {
                return secretProvider.resolve(ref);
            } catch (ISecretProvider.SecretNotFoundException e) {
                LOGGER.warnf("Vault reference not found: %s — passing through unchanged", SecretReference.describe(fullMatch));
                resolveErrorCounter.increment();
                return null;
            } catch (ISecretProvider.SecretProviderException e) {
                LOGGER.errorf("Failed to resolve vault reference: %s — %s", fullMatch, e.getMessage());
                resolveErrorCounter.increment();
                return null;
            }
        });
    }

    /**
     * Invalidate a specific cache entry (called on secret rotation).
     */
    public void invalidateCache(SecretReference reference) {
        invalidateLocal(reference);
        if (clusterEvents != null) {
            clusterEvents.publish(ClusterEvent.SECRET_CHANGED, Map.of("tenantId", reference.tenantId(), "keyName", reference.keyName()));
        }
    }

    /**
     * The local half of {@link #invalidateCache}: evicts this node's cache and
     * notifies the local listeners, without announcing it — what a node does when
     * ANOTHER node rotated the secret (a received event must never be
     * re-published).
     */
    public void invalidateLocal(SecretReference reference) {
        String cacheKey = cacheKey(reference);
        invalidations.incrementAndGet();
        cache.invalidate(cacheKey);
        grantCache.invalidate(cacheKey);
        LOGGER.infof("Cache invalidated for: %s", cacheKey);
        fireInvalidationListeners(reference);
    }

    /**
     * Invalidate all cached secrets (e.g., on master key rotation).
     */
    public void invalidateAll() {
        invalidateAllLocal();
        if (clusterEvents != null) {
            clusterEvents.publish(ClusterEvent.SECRET_CHANGED, Map.of("all", true));
        }
    }

    /** The local half of {@link #invalidateAll}; see {@link #invalidateLocal}. */
    public void invalidateAllLocal() {
        invalidations.incrementAndGet();
        cache.invalidateAll();
        grantCache.invalidateAll();
        LOGGER.info("All cached secrets invalidated");
        fireInvalidationListeners(null);
    }

    /**
     * Register a listener that is called whenever cached secrets are invalidated.
     * The listener receives the specific {@link SecretReference} that changed, or
     * {@code null} when all secrets are being invalidated. This allows downstream
     * caches to perform surgical eviction.
     *
     * @param listener
     *            a callback that receives the affected SecretReference (or null for
     *            all)
     */
    public void registerInvalidationListener(Consumer<SecretReference> listener) {
        invalidationListeners.add(listener);
    }

    private void fireInvalidationListeners(SecretReference reference) {
        for (Consumer<SecretReference> listener : invalidationListeners) {
            try {
                listener.accept(reference);
            } catch (Exception e) {
                LOGGER.warnf("Invalidation listener failed: %s", e.getMessage());
            }
        }
    }
}
