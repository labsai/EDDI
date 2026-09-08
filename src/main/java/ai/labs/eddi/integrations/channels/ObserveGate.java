/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.channels;

import ai.labs.eddi.configs.channels.model.ChannelTarget;
import ai.labs.eddi.configs.channels.model.ObserveConfig;
import ai.labs.eddi.engine.caching.ICache;
import ai.labs.eddi.engine.caching.ICacheFactory;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Decides whether a passive observer target answers a channel message it was
 * not addressed in, and holds the counters that keep it from answering too
 * often or too expensively.
 * <p>
 * An observer is a {@link ChannelTarget} with {@code observeMode} on. Ordinary
 * targets only ever see messages that mention the bot; an observer also sees
 * plain channel traffic, which is why every one of its replies has to pass four
 * gates in this order:
 * <ol>
 * <li><b>Trigger</b> — the message matches a configured keyword, or carries a
 * file of a configured MIME type. An observer with neither configured watches
 * everything, which the caps below are what make survivable.</li>
 * <li><b>Cooldown</b> — at least {@code cooldownSeconds} since this observer
 * last replied in this channel.</li>
 * <li><b>Daily count</b> — a hard cap on replies per UTC day.</li>
 * <li><b>Daily cost</b> — a dollar ceiling per UTC day.</li>
 * </ol>
 * The order is deliberate: an observer that did not match should not consume,
 * or be reported as hitting, a rate limit.
 *
 * <h2>What the cost ceiling actually measures</h2> {@link ObserveConfig} calls
 * {@code maxCostPerDay} the primary control, and this class enforces it — but
 * on the only figure the engine tracks. {@code ToolCostTracker} accumulates
 * {@code @Tool} executions alone, so an observer that only talks to an LLM
 * accrues {@code 0.0} and is bounded by the count cap instead. That is the same
 * quantity, with the same caveat, that {@code ScheduleFireExecutor} logs per
 * fire; a second, LLM-inclusive figure would be a new subsystem, not a flag.
 * The caller supplies the number, so a future source of total spend needs no
 * change here.
 *
 * <h2>Windows are best-effort</h2> Counters live in the shared cache, which is
 * where every other piece of channel state lives. On a multi-node deployment
 * they are shared; if the cache drops an entry the window restarts, and an
 * observer gets a fresh allowance. That is the right failure direction for a
 * spam guard whose hard stop is a dollar ceiling on top of a cooldown — losing
 * the window costs at most one extra reply per node, while failing closed would
 * silence an observer over a cache eviction.
 *
 * @since 6.4.0
 */
@ApplicationScoped
public class ObserveGate {

    private static final Logger LOGGER = Logger.getLogger(ObserveGate.class);

    /**
     * Windows are keyed per UTC day and re-read on every message, so the entry only
     * has to outlive the day it describes.
     */
    private static final Duration WINDOW_TTL = Duration.ofHours(48);

    /** Bounded retries for the compare-and-set on a window. */
    private static final int CAS_ATTEMPTS = 8;

    private final ICache<String, ObserveWindow> windows;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    @Inject
    public ObserveGate(ICacheFactory cacheFactory, MeterRegistry meterRegistry) {
        this(cacheFactory, meterRegistry, Clock.systemUTC());
    }

    /** Test seam: a fixed clock makes the cooldown and day rollover assertable. */
    ObserveGate(ICacheFactory cacheFactory, MeterRegistry meterRegistry, Clock clock) {
        this.windows = cacheFactory.getCache("channel-observe-windows");
        this.meterRegistry = meterRegistry;
        this.clock = clock;
    }

    // ─── Types ─────────────────────────────────────────────────────────────────

    /**
     * Why an observer did or did not reply. Bounded, so it is safe as a metric tag.
     */
    public enum Reason {
        /** Every gate passed; the observer should reply. */
        MATCHED,
        /** The target is not an observer at all. */
        NOT_OBSERVING,
        /** Neither a keyword nor a MIME type matched. */
        NO_TRIGGER,
        /** This observer replied too recently in this channel. */
        COOLDOWN,
        /** The observer has used its replies for the day. */
        DAILY_RESPONSE_CAP,
        /** The observer has used its budget for the day. */
        DAILY_COST_CAP
    }

    /** The verdict for one observer against one message. */
    public record Verdict(boolean respond, Reason reason) {
        static Verdict no(Reason reason) {
            return new Verdict(false, reason);
        }

        static final Verdict YES = new Verdict(true, Reason.MATCHED);
    }

    /** The observer that will answer, and the verdict that let it. */
    public record Match(ChannelTarget target, Verdict verdict) {
    }

    /**
     * One observer's activity for one UTC day in one channel.
     *
     * @param dayEpoch
     *            days since the epoch, UTC — the window's identity, so a rollover
     *            is a mismatch rather than a scheduled reset
     * @param responses
     *            replies sent in that day
     * @param costUsd
     *            spend attributed to those replies
     * @param lastResponseEpochSeconds
     *            when the observer last replied, for the cooldown; carried across a
     *            day boundary so midnight does not license an immediate reply
     */
    record ObserveWindow(long dayEpoch, int responses, double costUsd, long lastResponseEpochSeconds) {
        static ObserveWindow empty(long dayEpoch, long lastResponseEpochSeconds) {
            return new ObserveWindow(dayEpoch, 0, 0.0, lastResponseEpochSeconds);
        }
    }

    // ─── Decision ──────────────────────────────────────────────────────────────

    /**
     * The first observer among {@code candidates} that should answer this message.
     * <p>
     * First match wins, in configuration order, mirroring how trigger keywords
     * resolve for an addressed message. Letting every matching observer answer
     * would turn one message into several replies, which is the failure mode a
     * passive watcher most needs to avoid.
     *
     * @param candidates
     *            observe-mode targets configured for this channel, in config order
     * @param mimeTypes
     *            MIME types of files attached to the message; may be empty
     */
    public Optional<Match> select(String channelType, String platformChannelId,
                                  List<ChannelTarget> candidates, String messageText,
                                  List<String> mimeTypes) {
        if (candidates == null || candidates.isEmpty()) {
            return Optional.empty();
        }
        for (ChannelTarget candidate : candidates) {
            Verdict verdict = evaluate(channelType, platformChannelId, candidate, messageText, mimeTypes);
            if (verdict.respond()) {
                return Optional.of(new Match(candidate, verdict));
            }
            // A rate-limited observer is a match that was suppressed, so it stops
            // the search: falling through to the next observer would let a second
            // watcher answer precisely because the first one was throttled.
            if (verdict.reason() != Reason.NO_TRIGGER && verdict.reason() != Reason.NOT_OBSERVING) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    /** Whether this one observer should answer this message, and why. */
    public Verdict evaluate(String channelType, String platformChannelId, ChannelTarget target,
                            String messageText, List<String> mimeTypes) {
        if (target == null || !target.isObserveMode()) {
            return Verdict.no(Reason.NOT_OBSERVING);
        }
        // An observer saved without a config still gets the defaults, so its
        // cooldown and caps are never silently absent. The store defaults this on
        // write; this is the belt for a document written before that did.
        ObserveConfig config = target.getObserveConfig() != null
                ? target.getObserveConfig()
                : new ObserveConfig();

        if (!triggersMatch(config, messageText, mimeTypes)) {
            return record(target, Verdict.no(Reason.NO_TRIGGER));
        }

        Instant now = clock.instant();
        ObserveWindow window = currentWindow(key(channelType, platformChannelId, target), now);

        long sinceLast = now.getEpochSecond() - window.lastResponseEpochSeconds();
        if (window.lastResponseEpochSeconds() > 0 && sinceLast < config.getCooldownSeconds()) {
            return record(target, Verdict.no(Reason.COOLDOWN));
        }
        if (window.responses() >= config.getMaxDailyResponses()) {
            return record(target, Verdict.no(Reason.DAILY_RESPONSE_CAP));
        }
        // `>=` rather than `>`: a budget already spent buys nothing more. Checked
        // before the turn runs, so the ceiling can be overshot by at most the cost
        // of the reply that crossed it — there is no way to price a turn in advance.
        if (window.costUsd() >= config.getMaxCostPerDay()) {
            return record(target, Verdict.no(Reason.DAILY_COST_CAP));
        }
        return record(target, Verdict.YES);
    }

    /**
     * Book a reply against the observer's window.
     * <p>
     * Called after the turn, with what it actually cost, so a turn that failed
     * before producing anything is not charged. The count is incremented either
     * way: a reply that was attempted consumed the observer's allowance whether or
     * not it landed, which is what stops a failing observer retrying all day.
     */
    public void recordResponse(String channelType, String platformChannelId, ChannelTarget target,
                               double costUsd) {
        double charge = Double.isFinite(costUsd) && costUsd > 0 ? costUsd : 0.0;
        mutate(channelType, platformChannelId, target, "record a response",
                (window, nowEpochSeconds) -> new ObserveWindow(window.dayEpoch(),
                        window.responses() + 1, window.costUsd() + charge, nowEpochSeconds));
    }

    /**
     * Add spend to today's window without consuming another reply.
     *
     * Split from {@link #recordResponse} because the two are known at different
     * moments: the reply is committed before the turn runs, and what it cost only
     * exists after. Folding them into one call would mean either counting the reply
     * late — letting a burst through while the first turn is still running — or
     * charging a cost nobody has measured yet.
     */
    public void addCost(String channelType, String platformChannelId, ChannelTarget target,
                        double costUsd) {
        if (!Double.isFinite(costUsd) || costUsd <= 0) {
            return;
        }
        mutate(channelType, platformChannelId, target, "add cost",
                (window, nowEpochSeconds) -> new ObserveWindow(window.dayEpoch(), window.responses(),
                        window.costUsd() + costUsd, window.lastResponseEpochSeconds()));
    }

    /** How a mutation derives the next window from today's. */
    @FunctionalInterface
    private interface WindowUpdate {
        ObserveWindow apply(ObserveWindow today, long nowEpochSeconds);
    }

    /**
     * Compare-and-set today's window.
     *
     * The cache is a {@link java.util.concurrent.ConcurrentMap}, so the update has
     * to be a CAS rather than a read-then-write: two nodes observing the same
     * channel would otherwise each write a window derived from the same stale read,
     * and one reply would go unrecorded.
     */
    private void mutate(String channelType, String platformChannelId, ChannelTarget target,
                        String what, WindowUpdate update) {
        if (target == null) {
            return;
        }
        String key = key(channelType, platformChannelId, target);
        long nowEpochSeconds = clock.instant().getEpochSecond();
        long today = dayOf(clock.instant());

        for (int attempt = 0; attempt < CAS_ATTEMPTS; attempt++) {
            ObserveWindow current = windows.get(key);
            ObserveWindow base = (current == null || current.dayEpoch() != today)
                    // A new day keeps the last-response time: the cooldown is a spam
                    // guard, not a daily allowance, and resetting it at midnight
                    // would license an immediate second reply.
                    ? ObserveWindow.empty(today, current != null ? current.lastResponseEpochSeconds() : 0L)
                    : current;
            ObserveWindow next = update.apply(base, nowEpochSeconds);

            boolean stored = current == null
                    ? windows.putIfAbsent(key, next, WINDOW_TTL.toSeconds(), TimeUnit.SECONDS) == null
                    : windows.replace(key, current, next, WINDOW_TTL.toSeconds(), TimeUnit.SECONDS);
            if (stored) {
                return;
            }
        }
        // Losing the race this many times means heavy contention on one observer in
        // one channel, which the cooldown is supposed to make impossible. Log it
        // rather than spin: the next message re-reads the window either way.
        LOGGER.warnf("[OBSERVE] Could not %s for target '%s' after %d attempts",
                what, target.getName(), CAS_ATTEMPTS);
    }

    // ─── Internals ─────────────────────────────────────────────────────────────

    /**
     * Whether the message trips this observer.
     * <p>
     * Keywords are case-insensitive substring matches, as
     * {@link ObserveConfig#getTriggerKeywords()} documents. MIME types match
     * exactly, ignoring any {@code ;charset=…} the platform appends.
     */
    static boolean triggersMatch(ObserveConfig config, String messageText, List<String> mimeTypes) {
        List<String> keywords = config.getTriggerKeywords();
        List<String> types = config.getTriggerMimeTypes();
        boolean hasKeywords = keywords != null && !keywords.isEmpty();
        boolean hasTypes = types != null && !types.isEmpty();

        // Neither configured: watch everything. Documented on the model, and the
        // reason the cooldown and both caps are not optional.
        if (!hasKeywords && !hasTypes) {
            return true;
        }
        if (hasKeywords && messageText != null) {
            String haystack = messageText.toLowerCase(Locale.ROOT);
            for (String keyword : keywords) {
                if (keyword == null || keyword.isBlank()) {
                    continue;
                }
                if (haystack.contains(keyword.toLowerCase(Locale.ROOT).trim())) {
                    return true;
                }
            }
        }
        if (hasTypes && mimeTypes != null) {
            for (String mimeType : mimeTypes) {
                if (mimeType == null || mimeType.isBlank()) {
                    continue;
                }
                String bare = mimeType.split(";")[0].trim().toLowerCase(Locale.ROOT);
                for (String configured : types) {
                    if (configured != null && bare.equals(configured.trim().toLowerCase(Locale.ROOT))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** The window for today, treating a stale day as an empty one. */
    private ObserveWindow currentWindow(String key, Instant now) {
        long today = dayOf(now);
        ObserveWindow stored = windows.get(key);
        if (stored == null) {
            return ObserveWindow.empty(today, 0L);
        }
        if (stored.dayEpoch() != today) {
            return ObserveWindow.empty(today, stored.lastResponseEpochSeconds());
        }
        return stored;
    }

    private static long dayOf(Instant now) {
        return now.atZone(ZoneOffset.UTC).toLocalDate().toEpochDay();
    }

    private static String key(String channelType, String platformChannelId, ChannelTarget target) {
        String type = channelType != null ? channelType.toLowerCase(Locale.ROOT) : "";
        return type + ':' + platformChannelId + ':' + target.getName();
    }

    private Verdict record(ChannelTarget target, Verdict verdict) {
        meterRegistry.counter("eddi_channel_observe_decisions_total",
                "reason", verdict.reason().name(),
                "type", String.valueOf(target.getType())).increment();
        if (!verdict.respond() && verdict.reason() != Reason.NO_TRIGGER) {
            LOGGER.debugf("[OBSERVE] Target '%s' suppressed: %s", target.getName(), verdict.reason());
        }
        return verdict;
    }
}
