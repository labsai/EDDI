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
import ai.labs.eddi.engine.health.report.HealthReports.Check;
import ai.labs.eddi.engine.health.report.HealthReports.CreatedReportLink;
import ai.labs.eddi.engine.health.report.HealthReports.Reason;
import ai.labs.eddi.engine.health.report.HealthReports.ReportBrand;
import ai.labs.eddi.engine.health.report.HealthReports.ReportLinkInfo;
import ai.labs.eddi.engine.health.report.HealthReports.SharedHealthReport;
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
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * creator may read the agent's ratings. Reasons of other sections are left out.
 * Never drill-down, conversation ids or user data.</li>
 * <li>At most {@value #MAX_LINKS_PER_AGENT} live links per agent.</li>
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

    @Inject
    public HealthReportLinks(IHealthReportLinkStore store, HealthReportService reports, IDocumentDescriptorStore descriptorStore) {
        this(store, reports, descriptorStore, Clock.systemUTC());
    }

    HealthReportLinks(IHealthReportLinkStore store, HealthReportService reports, IDocumentDescriptorStore descriptorStore, Clock clock) {
        this.store = store;
        this.reports = reports;
        this.descriptorStore = descriptorStore;
        this.clock = clock;
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
     * ratings rule.
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
        HealthReportLink link = new HealthReportLink("r-" + UUID.randomUUID(), agentId, hash(token), sections, p, now, creator,
                now.plus(Duration.ofDays(days)));
        store.create(link);
        LOGGER.infof("[HEALTH] '%s' created owner report link %s for agent %s (sections %s, expires %s)", sanitize(creator), link.id(),
                sanitize(agentId), link.sections(), link.expiresAt());
        String path = UI_PATH + token;
        return new CreatedReportLink(link.id(), (baseUrl == null ? "" : baseUrl) + path, path, link.expiresAt().toString(), link.sections(), p);
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
        }
        return deleted;
    }

    /**
     * The live link behind a token, or empty — for a malformed, unknown, expired or
     * revoked token alike.
     */
    public Optional<HealthReportLink> resolve(String token) {
        if (token == null || !TOKEN.matcher(token).matches()) {
            return Optional.empty();
        }
        Optional<HealthReportLink> link = store.findByTokenHash(hash(token));
        return link.filter(l -> l.expiresAt() != null && l.expiresAt().isAfter(clock.instant()));
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
        boolean quality = link.sections().contains(HealthDefaults.QUALITY);
        AgentHealth full = reports.report(new Scope(link.agentId(), window, null, null, markerTenants), name, quality);
        Map<String, Object> sections = new LinkedHashMap<>();
        full.sections().forEach((section, content) -> {
            if (link.sections().contains(section)) {
                sections.put(section, content);
            }
        });
        List<Reason> reasons = full.reasons().stream().filter(r -> r.measureId() == null || inSections(r.measureId(), link.sections())).toList();
        List<Check> checks = link.sections().contains(HealthDefaults.RELIABILITY) ? full.checks() : List.of();
        return Optional.of(new SharedHealthReport(name, full.period(), full.baseline(), full.status(), reasons, full.preset(), full.generatedAt(),
                full.dataThrough(), sections, checks, link.expiresAt().toString(), brand));
    }

    private static boolean inSections(String measureId, List<String> sections) {
        HealthDefaults.Definition d = HealthDefaults.of(measureId);
        return d != null && sections.contains(d.section());
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
