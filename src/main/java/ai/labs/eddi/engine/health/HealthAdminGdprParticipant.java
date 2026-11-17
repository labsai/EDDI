/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health;

import ai.labs.eddi.engine.gdpr.IGdprParticipant;
import ai.labs.eddi.engine.health.model.HealthProfile;
import ai.labs.eddi.engine.health.model.HealthReportLink;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * GDPR erasure and export of what Agent Health's administration keeps about a
 * person: the name of whoever last saved a health profile ({@code updatedBy})
 * and of whoever created an owner report link ({@code createdBy}).
 * <p>
 * <b>Erasure</b> blanks both; the profile stays, it belongs to the agent. A
 * link whose creator is blanked has nobody's authority behind it any more, so
 * it answers 404 wherever access is enforced (its creator's {@code EDIT} is
 * re-checked on every read) — revoke it to remove the row as well.
 * <p>
 * <b>Export</b> lists, like the alert rules' authors, the profiles the person
 * saved last (agent and time, not the thresholds — the agent's configuration,
 * not theirs) and the links they created (id, agent, sections, period, created
 * and expiry) — <b>never a token</b> or its hash. {@code null} when there is
 * neither.
 */
@ApplicationScoped
public class HealthAdminGdprParticipant implements IGdprParticipant {

    /** An export carries at most this many profiles, and this many links. */
    static final int MAX_EXPORTED = 10_000;

    /** A profile the person saved last. */
    public record ExportedProfile(String agentId, String updatedAt) {
    }

    /** A link the person created — never its token. */
    public record ExportedLink(String id, String agentId, List<String> sections, String period, String createdAt, String expiresAt) {
    }

    private final IHealthProfileStore profiles;
    private final IHealthReportLinkStore links;
    private final HealthProfiles profileCache;

    @Inject
    public HealthAdminGdprParticipant(IHealthProfileStore profiles, IHealthReportLinkStore links, HealthProfiles profileCache) {
        this.profiles = profiles;
        this.links = links;
        this.profileCache = profileCache;
    }

    @Override
    public String name() {
        return "agentHealthAdministration";
    }

    @Override
    public long erase(String userId) {
        long changed = profiles.clearAuthor(userId) + links.clearCreator(userId);
        if (changed > 0 && profileCache != null) {
            profileCache.invalidateAll();
        }
        return changed;
    }

    @Override
    public Object export(String userId) {
        if (userId == null) {
            return null;
        }
        List<ExportedProfile> saved = profiles.listByAuthor(userId).stream().limit(MAX_EXPORTED)
                .map((HealthProfile p) -> new ExportedProfile(p.agentId(), p.updatedAt())).toList();
        List<ExportedLink> created = links.listByCreator(userId).stream().limit(MAX_EXPORTED)
                .map((HealthReportLink l) -> new ExportedLink(l.id(), l.agentId(), l.sections(), l.period(),
                        l.createdAt() == null ? null : l.createdAt().toString(), l.expiresAt() == null ? null : l.expiresAt().toString()))
                .toList();
        if (saved.isEmpty() && created.isEmpty()) {
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("healthProfiles", saved);
        out.put("reportLinks", created);
        return out;
    }
}
