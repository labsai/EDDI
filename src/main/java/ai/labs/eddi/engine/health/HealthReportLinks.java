/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health;

import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.health.HealthPeriods.Window;
import ai.labs.eddi.engine.health.HealthReportService.Scope;
import ai.labs.eddi.engine.health.model.HealthReportLink;
import ai.labs.eddi.engine.health.report.HealthReports.AgentHealth;
import ai.labs.eddi.engine.health.report.HealthReports.CreatedReportLink;
import ai.labs.eddi.engine.health.report.HealthReports.ReportBrand;
import ai.labs.eddi.engine.health.report.HealthReports.ReportLinkInfo;
import ai.labs.eddi.engine.health.report.HealthReports.SharedHealthReport;
import ai.labs.eddi.configs.descriptors.model.AccessLevel;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard.UseCheck;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * Owner report links (H4b, plan decision 8): a read-only, branded view of one
 * agent's health for a business owner without Manager access.
 * <ul>
 * <li><b>Token</b>: {@value #TOKEN_BYTES} bytes from {@link SecureRandom} (256
 * bits), base64url without padding — shown once, in the creation response. Only
 * its SHA-256 is stored, and a public request is answered by looking the hash
 * up: no token is ever compared character by character, so there is no timing
 * side channel, and a database read leaks no usable token.</li>
 * <li><b>Expiry</b> 1 to {@value #MAX_EXPIRES_DAYS} days (default
 * {@value #DEFAULT_EXPIRES_DAYS}); <b>revocation</b> deletes the link. An
 * expired or revoked token answers exactly like one that never existed.</li>
 * <li><b>Sections</b> are chosen per link; {@code quality} only when the
 * creator may read the agent's ratings. The status and its reasons are judged
 * over the link's sections alone, and the view carries no free text (hints,
 * override reasons, marker ids). Never drill-down, conversation ids or user
 * data.</li>
 * <li><b>The creator's authority is re-checked on every read</b>: a link shows
 * only while the person who created it still holds {@code EDIT} on the agent —
 * ownership, grants and shares as they are now, with the team snapshot taken
 * when the link was created (see {@link #authorized}). A link whose creator
 * lost that answers 404 like a revoked one, and shows again if the access comes
 * back. What cannot be re-checked without the creator's token — their roles
 * (administrator, rating roles) and leaving a team — is the snapshot taken at
 * creation; revoke the links of a person who leaves.</li>
 * <li>At most {@value #MAX_LINKS_PER_AGENT} live links per agent, enforced
 * after the insert (see {@link #create}), so concurrent creations cannot exceed
 * it.</li>
 * </ul>
 */
@ApplicationScoped
public class HealthReportLinks {

    private static final Logger LOGGER = Logger.getLogger(HealthReportLinks.class);

    public static final int TOKEN_BYTES = 32;
    public static final int MAX_EXPIRES_DAYS = 90;
    public static final int DEFAULT_EXPIRES_DAYS = 30;
    public static final int MAX_LINKS_PER_AGENT = 20;
    /**
     * Where the Manager renders a report:
     * {@code <base>/manage/health-report/<token>}.
     */
    public static final String UI_PATH = "/manage/health-report/";
    /** The sections a link shows unless told otherwise — all but quality. */
    public static final List<String> DEFAULT_SECTIONS = List.of(HealthDefaults.RELIABILITY, HealthDefaults.EFFECTIVENESS, HealthDefaults.EFFICIENCY,
            HealthDefaults.ADOPTION, HealthDefaults.BUSINESS);

    /** A token's shape: 43 base64url characters (32 bytes, no padding). */
    static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]{43}");

    private static final SecureRandom RANDOM = new SecureRandom();

    private final IHealthReportLinkStore store;
    private final HealthReportService reports;
    private final IDocumentDescriptorStore descriptorStore;
    private final Clock clock;
    /** {@code null} only in tests built without it: every creator is authorized. */
    private ResourceAccessGuard accessGuard;
    /** {@code null} only in tests built without it. */
    private HealthAdminAudit audit;

    @Inject
    public HealthReportLinks(IHealthReportLinkStore store, HealthReportService reports, IDocumentDescriptorStore descriptorStore,
            ResourceAccessGuard accessGuard, HealthAdminAudit audit) {
        this(store, reports, descriptorStore, Clock.systemUTC());
        this.accessGuard = accessGuard;
        this.audit = audit;
    }

    HealthReportLinks(IHealthReportLinkStore store, HealthReportService reports, IDocumentDescriptorStore descriptorStore, Clock clock) {
        this.store = store;
        this.reports = reports;
        this.descriptorStore = descriptorStore;
        this.clock = clock;
    }

    /** With an access guard and an audit — for tests. */
    HealthReportLinks with(ResourceAccessGuard accessGuard, HealthAdminAudit audit) {
        this.accessGuard = accessGuard;
        this.audit = audit;
        return this;
    }

    /** Whether a token has the shape of one (checked before anything else). */
    public static boolean wellFormed(String token) {
        return token != null && TOKEN.matcher(token).matches();
    }

    /** A new random token. */
    static String newToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** Hex SHA-256 of a token — what is stored and looked up. */
    public static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    /**
     * The sections a request asked for, checked; the defaults when none.
     *
     * @throws jakarta.ws.rs.BadRequestException
     *             for an unknown or repeated section
     */
    public static List<String> sections(List<String> requested) {
        if (requested == null || requested.isEmpty()) {
            return DEFAULT_SECTIONS;
        }
        List<String> out = new ArrayList<>();
        for (String section : requested) {
            if (section == null || !HealthDefaults.SECTIONS.contains(section)) {
                throw HealthPeriods.badRequest("sections must be some of " + String.join(", ", HealthDefaults.SECTIONS) + " — was '"
                        + sanitize(String.valueOf(section)) + "'");
            }
            if (!out.contains(section)) {
                out.add(section);
            }
        }
        // Display order, whatever the request's order.
        return HealthDefaults.SECTIONS.stream().filter(out::contains).toList();
    }

    /**
     * Creates a link. The caller has checked EDIT and, for {@code quality}, the
     * ratings rule; this records the creator's teams and administrator flag, so
     * every read can re-check their EDIT without their request.
     * <p>
     * The cap is enforced <em>after</em> the insert: the new link is kept only if
     * it is among the agent's first {@value #MAX_LINKS_PER_AGENT} live links in
     * creation order (time, then id), else it is deleted again and refused. Every
     * concurrent creator sees the same order, so two that race for the last slot
     * cannot both keep theirs — a check before the insert let both through.
     *
     * @param baseUrl
     *            the deployment's public base URL, without a trailing slash
     */
    public CreatedReportLink create(String agentId, Integer expiresInDays, List<String> sections, String period, String creator, String baseUrl) {
        int days = expiresInDays == null ? DEFAULT_EXPIRES_DAYS : expiresInDays;
        if (days < 1 || days > MAX_EXPIRES_DAYS) {
            throw HealthPeriods.badRequest("expiresInDays must be between 1 and " + MAX_EXPIRES_DAYS + " — was " + days);
        }
        String p = period == null || period.isBlank() ? "30d" : period.trim();
        if (!HealthPeriods.PERIODS.contains(p)) {
            throw HealthPeriods.badRequest("period must be one of " + String.join(", ", HealthPeriods.PERIODS) + " — was '" + sanitize(p) + "'");
        }
        Instant now = clock.instant();
        long live = store.listByAgent(agentId).stream().filter(l -> l.expiresAt().isAfter(now)).count();
        if (live >= MAX_LINKS_PER_AGENT) {
            throw HealthPeriods.badRequest("an agent may have at most " + MAX_LINKS_PER_AGENT + " live report links; revoke one first");
        }
        String token = newToken();
        List<String> teams = accessGuard == null ? List.of() : accessGuard.currentTeamSubjects();
        boolean admin = accessGuard != null && accessGuard.callerIsAdmin();
        HealthReportLink link = new HealthReportLink("r-" + UUID.randomUUID(), agentId, hash(token), sections, p, now, creator,
                now.plus(Duration.ofDays(days)), teams, admin);
        store.create(link);
        if (!withinCap(link)) {
            store.delete(agentId, link.id());
            throw HealthPeriods.badRequest("an agent may have at most " + MAX_LINKS_PER_AGENT + " live report links; revoke one first");
        }
        if (audit != null) {
            audit.linkCreated(creator, agentId, link.id(), link.sections(), p, link.expiresAt());
        }
        LOGGER.infof("[HEALTH] '%s' created owner report link %s for agent %s (sections %s, expires %s)", sanitize(creator), link.id(),
                sanitize(agentId), link.sections(), link.expiresAt());
        String path = UI_PATH + token;
        return new CreatedReportLink(link.id(), (baseUrl == null ? "" : baseUrl) + path, path, link.expiresAt().toString(), link.sections(), p);
    }

    /**
     * Whether a just-inserted link is among the agent's first
     * {@link #MAX_LINKS_PER_AGENT} live links, oldest first.
     */
    private boolean withinCap(HealthReportLink link) {
        Instant now = clock.instant();
        Comparator<HealthReportLink> order = Comparator.comparing(HealthReportLink::createdAt, Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(HealthReportLink::id);
        long before = store.listByAgent(link.agentId()).stream()
                .filter(l -> !l.id().equals(link.id()) && l.expiresAt().isAfter(now) && order.compare(l, link) < 0).count();
        return before < MAX_LINKS_PER_AGENT;
    }

    /** An agent's live links, newest first — never their tokens. */
    public List<ReportLinkInfo> list(String agentId) {
        Instant now = clock.instant();
        return store.listByAgent(agentId).stream().filter(l -> l.expiresAt().isAfter(now))
                .map(l -> new ReportLinkInfo(l.id(), l.expiresAt().toString(), l.sections(), l.period(),
                        l.createdAt() == null ? null : l.createdAt().toString(), l.createdBy()))
                .toList();
    }

    /** Revokes one link; whether it existed. */
    public boolean revoke(String agentId, String id, String caller) {
        boolean deleted = store.delete(agentId, id);
        if (deleted) {
            LOGGER.infof("[HEALTH] '%s' revoked owner report link %s of agent %s", sanitize(caller), sanitize(id), sanitize(agentId));
            if (audit != null) {
                audit.linkRevoked(caller, agentId, id, 1);
            }
        }
        return deleted;
    }

    /** Revokes every link of an agent; how many there were. */
    public long revokeAll(String agentId, String caller) {
        long deleted = store.deleteByAgent(agentId);
        LOGGER.infof("[HEALTH] '%s' revoked all %d owner report links of agent %s", sanitize(caller), deleted, sanitize(agentId));
        if (audit != null && deleted > 0) {
            audit.linkRevoked(caller, agentId, null, deleted);
        }
        return deleted;
    }

    /**
     * Whether the link's creator still holds {@code EDIT} on its agent, judged now
     * from the agent's current ownership, grants and shares (with the creator's
     * team snapshot). {@link UseCheck#UNKNOWN} when the descriptor store could not
     * answer. A link without a creator (erased, or created with authentication off)
     * is {@code DENIED} wherever access is enforced.
     */
    public UseCheck authorized(HealthReportLink link) {
        if (accessGuard == null) {
            return UseCheck.ALLOWED;
        }
        return accessGuard.checkPrincipalLevel(link.agentId(), link.createdBy(), link.creatorTeams(), link.creatorAdmin(), AccessLevel.EDIT);
    }

    /**
     * The live link behind a token, or empty — for a malformed, unknown, expired or
     * revoked token alike.
     */
    public Optional<HealthReportLink> resolve(String token) {
        if (!wellFormed(token)) {
            return Optional.empty();
        }
        return resolveHash(hash(token));
    }

    /** The live link stored under a token hash, or empty. */
    public Optional<HealthReportLink> resolveHash(String tokenHash) {
        Optional<HealthReportLink> link = store.findByTokenHash(tokenHash);
        return link.filter(l -> l.expiresAt() != null && l.expiresAt().isAfter(clock.instant()));
    }

    /** GDPR export: the links a person created — ids and dates, never a token. */
    public List<HealthReportLink> createdBy(String userId) {
        return userId == null ? List.of() : store.listByCreator(userId);
    }

    /**
     * The report a link shows: the agent's health over the link's period, limited
     * to its sections, under the brand. Empty when the agent no longer exists.
     */
    public Optional<SharedHealthReport> render(HealthReportLink link, List<String> markerTenants, ReportBrand brand) {
        String name;
        try {
            DocumentDescriptor descriptor = descriptorStore.readCurrentDescriptor(link.agentId());
            name = descriptor == null ? null : descriptor.getName();
        } catch (IResourceStore.ResourceNotFoundException e) {
            return Optional.empty();
        } catch (IResourceStore.ResourceStoreException e) {
            throw new HealthStoreException("Could not read the agent of a health report", e);
        }
        Window window = HealthPeriods.window(link.period(), null, null, "30d", reports.today());
        // The status and its reasons are judged over the link's sections only, and
        // the view carries no free text (see HealthReportService.sharedReport).
        AgentHealth shared = reports.sharedReport(new Scope(link.agentId(), window, null, null, markerTenants), name, link.sections());
        return Optional.of(new SharedHealthReport(name, shared.period(), shared.baseline(), shared.status(), shared.reasons(), shared.preset(),
                shared.generatedAt(), shared.dataThrough(), shared.sections(), shared.checks(), link.expiresAt().toString(), brand));
    }

    /** Deletes the links that expired more than a day ago; never throws. */
    public long purgeExpired() {
        try {
            return store.deleteExpiredBefore(clock.instant().minus(Duration.ofDays(1)));
        } catch (RuntimeException e) {
            LOGGER.debugf("Could not delete expired health report links: %s", e.getMessage());
            return 0;
        }
    }
}
