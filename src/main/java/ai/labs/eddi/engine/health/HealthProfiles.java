/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health;

import ai.labs.eddi.engine.health.HealthProfileValidator.Violation;
import ai.labs.eddi.engine.health.model.HealthProfile;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * Reads, validates and writes the agents' health profiles, and resolves the
 * effective profile the measures and the status rules apply.
 * <p>
 * Profiles are cached per agent for {@value #CACHE_SECONDS} s (a save or delete
 * on this node clears the agent's entry at once; another node sees it within
 * that time). A save or delete leaves a {@code PROFILE_CHANGE} marker on the
 * agent's charts — thresholds moved, so bands may change without the agent
 * changing — and, when the Apdex threshold changed, marks the turn-row
 * retention of the agent dirty, so the rollup recomputes its Apdex counts under
 * the new T.
 */
@ApplicationScoped
public class HealthProfiles {

    private static final Logger LOGGER = Logger.getLogger(HealthProfiles.class);

    static final int CACHE_SECONDS = 60;

    /** A profile that failed validation; carries every violation. */
    public static class InvalidProfileException extends RuntimeException {
        private final transient List<Violation> violations;

        public InvalidProfileException(List<Violation> violations) {
            super("The health profile is invalid");
            this.violations = List.copyOf(violations);
        }

        public List<Violation> violations() {
            return violations;
        }
    }

    private final IHealthProfileStore store;
    private final HealthChangeMarkers markers;
    private final Instance<HealthDirtyDays> dirtyDays;
    private final HealthSettings settings;
    private final Clock clock;
    private final Cache<String, Optional<HealthProfile>> cache;

    @Inject
    public HealthProfiles(IHealthProfileStore store, HealthChangeMarkers markers, Instance<HealthDirtyDays> dirtyDays, HealthSettings settings) {
        this(store, markers, dirtyDays, settings, Clock.systemUTC());
    }

    HealthProfiles(IHealthProfileStore store, HealthChangeMarkers markers, Instance<HealthDirtyDays> dirtyDays, HealthSettings settings,
            Clock clock) {
        this.store = store;
        this.markers = markers;
        this.dirtyDays = dirtyDays;
        this.settings = settings;
        this.clock = clock;
        this.cache = Caffeine.newBuilder().expireAfterWrite(Duration.ofSeconds(CACHE_SECONDS)).maximumSize(10_000).build();
    }

    LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    /** The agent's stored profile, or empty. Cached. */
    public Optional<HealthProfile> stored(String agentId) {
        if (agentId == null) {
            return Optional.empty();
        }
        return cache.get(agentId, store::read);
    }

    /** The stored profiles of many agents (absent: none stored). Cached. */
    public Map<String, HealthProfile> storedAll(Collection<String> agentIds) {
        Map<String, HealthProfile> out = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();
        for (String id : agentIds) {
            Optional<HealthProfile> hit = cache.getIfPresent(id);
            if (hit == null) {
                missing.add(id);
            } else {
                hit.ifPresent(p -> out.put(id, p));
            }
        }
        if (!missing.isEmpty()) {
            Map<String, HealthProfile> read = store.readAll(missing);
            for (String id : missing) {
                HealthProfile p = read.get(id);
                cache.put(id, Optional.ofNullable(p));
                if (p != null) {
                    out.put(id, p);
                }
            }
        }
        return out;
    }

    /**
     * The profile the measures apply: the stored one over its preset, or — with
     * none stored or the store unreadable — the preset picked from traffic.
     */
    public EffectiveHealthProfile effective(String agentId, String autoPreset) {
        HealthProfile stored;
        try {
            stored = stored(agentId).orElse(null);
        } catch (RuntimeException e) {
            LOGGER.debugf("Could not read the health profile of %s; using the preset: %s", sanitize(agentId), e.getMessage());
            stored = null;
        }
        return EffectiveHealthProfile.of(stored, autoPreset, today());
    }

    /** {@code GET …/health/profile}: the effective profile, with {@code stored}. */
    public HealthProfile view(String agentId, String autoPreset) {
        HealthProfile stored = stored(agentId).orElse(null);
        return EffectiveHealthProfile.of(stored, autoPreset, today()).toResponse(agentId, stored);
    }

    /**
     * {@code PUT …/health/profile}: validates and stores the profile as sent.
     *
     * @throws InvalidProfileException
     *             with every violation
     * @return the effective profile after the save
     */
    public HealthProfile save(String agentId, HealthProfile profile, String user, String autoPreset) {
        List<Violation> violations = HealthProfileValidator.validate(agentId, profile, today());
        if (!violations.isEmpty()) {
            throw new InvalidProfileException(violations);
        }
        HealthProfile before = stored(agentId).orElse(null);
        HealthProfile toStore = profile.withMeta(agentId, null, clock.instant().toString(), user);
        store.write(toStore);
        cache.invalidate(agentId);
        changed(agentId, before, toStore);
        return view(agentId, autoPreset);
    }

    /** {@code DELETE …/health/profile}: whether one was stored. */
    public boolean delete(String agentId) {
        HealthProfile before = stored(agentId).orElse(null);
        boolean deleted = store.delete(agentId);
        cache.invalidate(agentId);
        if (deleted) {
            changed(agentId, before, null);
        }
        return deleted;
    }

    private void changed(String agentId, HealthProfile before, HealthProfile after) {
        markers.profileChanged(agentId);
        Long t0 = apdexT(before);
        Long t1 = apdexT(after);
        if (!Objects.equals(t0, t1) && dirtyDays != null && dirtyDays.isResolvable()) {
            try {
                LocalDate today = today();
                dirtyDays.get().markRange(agentId, today.minusDays(settings.factsRetentionDays()), today);
            } catch (RuntimeException e) {
                LOGGER.warnf("Could not mark the days of agent %s for a recount under its new Apdex threshold: %s", sanitize(agentId),
                        sanitize(e.getMessage()));
            }
        }
    }

    private static Long apdexT(HealthProfile profile) {
        return profile == null || profile.slo() == null ? null : profile.slo().apdexTMs();
    }

    /**
     * The Apdex threshold T the rollup counts the agent's turns under: the
     * profile's, else {@code eddi.health.apdex-t}. Never throws.
     */
    public long apdexTMs(String agentId) {
        try {
            Long t = apdexT(stored(agentId).orElse(null));
            return t != null ? t : settings.apdexTMs();
        } catch (RuntimeException e) {
            return settings.apdexTMs();
        }
    }

    /**
     * {@link #apdexTMs}, read from the store past the cache - what the rollup
     * counts under (a recount may run on another node within the cache's TTL of the
     * change). The read refreshes the cache.
     *
     * @throws HealthStoreException
     *             when the store cannot answer, so the rollup retries the day
     *             rather than counting it under a guessed T
     */
    public long apdexTMsUncached(String agentId) {
        Optional<HealthProfile> profile = store.read(agentId);
        cache.put(agentId, profile);
        Long t = apdexT(profile.orElse(null));
        return t != null ? t : settings.apdexTMs();
    }

    /** Clears the cached profile of an agent — for tests and erasure. */
    public void invalidate(String agentId) {
        cache.invalidate(agentId);
    }

    public void invalidateAll() {
        cache.invalidateAll();
    }
}
