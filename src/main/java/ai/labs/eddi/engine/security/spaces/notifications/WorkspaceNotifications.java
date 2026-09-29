/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.security.spaces.notifications;

import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.descriptors.model.AccessLevel;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.datastore.IResourceStore.ResourceNotFoundException;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import ai.labs.eddi.engine.security.spaces.SpaceContext;
import ai.labs.eddi.engine.security.spaces.Subjects;
import ai.labs.eddi.engine.security.spaces.directory.UserDirectory;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.context.ManagedExecutor;
import org.jboss.logging.Logger;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * Tells people when something is shared with them, and lets them ask for access
 * to something they cannot open.
 *
 * <h3>Why sharing needs a notification at all</h3> A share used to change a
 * grant and nothing else. The recipient found out only if the sharer told them
 * some other way, and then had to hunt for the resource among their own. Now
 * the share lands in their inbox with a link.
 *
 * <h3>Why an access request reveals nothing</h3> Whoever asks cannot see the
 * resource — that is why they are asking. So the answer never says whether the
 * resource exists, who owns it or what it is called: a request for an id that
 * matches nothing is answered exactly like one that was delivered. Otherwise
 * the endpoint would be an oracle for probing ids and learning owners.
 *
 * <h3>Bounded</h3> A requester may send at most {@link #MAX_REQUESTS_PER_DAY}
 * requests a day, a repeat of an unanswered request is not delivered twice, and
 * each inbox keeps its newest {@link #INBOX_SIZE} entries for at most
 * {@link #RETENTION}. Every write is best effort: a notification that cannot be
 * stored is logged, and never fails the share that caused it.
 */
@ApplicationScoped
public class WorkspaceNotifications {

    private static final Logger LOGGER = Logger.getLogger(WorkspaceNotifications.class);

    /** Newest notifications kept per recipient. */
    public static final int INBOX_SIZE = 200;

    /** How long a notification is kept at most. */
    public static final Duration RETENTION = Duration.ofDays(90);

    /** Access requests one person may send per rolling day. */
    public static final int MAX_REQUESTS_PER_DAY = 20;

    /** Longest note a requester may attach. */
    public static final int MAX_MESSAGE_LENGTH = 500;

    /** Most members of a team notified about one share. */
    static final int MAX_TEAM_FAN_OUT = 200;

    private final IWorkspaceNotificationStore store;
    private final IDocumentDescriptorStore descriptorStore;
    private final ResourceAccessGuard accessGuard;
    private final SpaceContext spaceContext;
    private final UserDirectory directory;
    private final ManagedExecutor executor;

    @Inject
    public WorkspaceNotifications(IWorkspaceNotificationStore store, IDocumentDescriptorStore descriptorStore, ResourceAccessGuard accessGuard,
            SpaceContext spaceContext, UserDirectory directory, ManagedExecutor executor) {
        this.store = store;
        this.descriptorStore = descriptorStore;
        this.accessGuard = accessGuard;
        this.spaceContext = spaceContext;
        this.directory = directory;
        this.executor = executor;
    }

    // --- Sharing -------------------------------------------------------------

    /**
     * Notifies whoever a share reached: the person, or every recorded member of the
     * team — except the person who shared.
     * <p>
     * Only the root is announced. A cascade over forty rule sets is one share from
     * the recipient's point of view.
     */
    public void onShared(String resourceId, String resourceUri, String resourceName, String subject, AccessLevel level) {
        String actor = spaceContext.currentPrincipal();
        if (actor == null || subject == null || level == null) {
            return;
        }
        Set<String> recipients = recipientsOf(subject);
        recipients.remove(actor);
        if (recipients.isEmpty()) {
            return;
        }
        String actorLabel = directory.labelFor(actor);
        Instant now = Instant.now();
        try {
            executor.execute(() -> {
                for (String recipient : recipients) {
                    deliver(new WorkspaceNotification(UUID.randomUUID().toString(), recipient, WorkspaceNotification.Type.SHARED_WITH_YOU,
                            resourceId, resourceUri, resourceName, actor, actorLabel, level.name(), null, now, null));
                }
            });
        } catch (RuntimeException e) {
            LOGGER.warnf("Could not schedule share notifications for %s: %s", sanitize(resourceId), e.getMessage());
        }
    }

    private Set<String> recipientsOf(String subject) {
        Set<String> recipients = new LinkedHashSet<>();
        if (subject.startsWith(Subjects.USER_PREFIX)) {
            recipients.add(Subjects.decode(subject.substring(Subjects.USER_PREFIX.length())));
        } else if (subject.startsWith(Subjects.TEAM_PREFIX)) {
            recipients.addAll(directory.teamMembers(subject, MAX_TEAM_FAN_OUT));
        }
        return recipients;
    }

    // --- Access requests -----------------------------------------------------

    /** What happened to an access request, as far as the requester may know. */
    public enum RequestOutcome {
        /**
         * Delivered — or, indistinguishably, addressed to nothing (a resource that does
         * not exist or has no owner), or a repeat of a request still waiting for the
         * owner. A distinct answer for the repeat would say that the id names an owned
         * resource, which is the one thing a request must not reveal.
         */
        SENT,
        /** The requester already holds the level they asked for. */
        ALREADY_HAS_ACCESS,
        /** The requester has sent too many requests today. */
        RATE_LIMITED
    }

    /**
     * Asks the owner of {@code resourceId} for {@code level}.
     *
     * @param message
     *            an optional note, trimmed and cut to {@link #MAX_MESSAGE_LENGTH}
     */
    public RequestOutcome requestAccess(String resourceId, AccessLevel level, String message) {
        String actor = spaceContext.currentPrincipal();
        if (actor == null || resourceId == null || resourceId.isBlank()) {
            return RequestOutcome.SENT;
        }
        Instant now = Instant.now();
        // Attempts count, not deliveries: counting only what reached an owner let a
        // caller probe any number of ids that match nothing without ever reaching the
        // limit. The stored count stays as the floor across nodes and restarts.
        if (store.countByActorSince(actor, WorkspaceNotification.Type.ACCESS_REQUESTED, now.minus(Duration.ofDays(1))) >= MAX_REQUESTS_PER_DAY
                || !recordAttempt(actor, now)) {
            return RequestOutcome.RATE_LIMITED;
        }

        DocumentDescriptor descriptor;
        try {
            descriptor = descriptorStore.readCurrentDescriptor(resourceId);
        } catch (ResourceNotFoundException e) {
            return RequestOutcome.SENT;
        } catch (Exception e) {
            LOGGER.warnf("Could not load %s for an access request: %s", sanitize(resourceId), e.getMessage());
            return RequestOutcome.SENT;
        }
        if (descriptor == null) {
            return RequestOutcome.SENT;
        }
        AccessLevel held = accessGuard.effectiveLevel(descriptor);
        if (held != null && held.includes(level)) {
            // Safe to say: the caller can already reach the resource, so its existence
            // is no secret from them.
            return RequestOutcome.ALREADY_HAS_ACCESS;
        }
        String owner = descriptor.getOwnerId();
        if (owner == null || owner.isBlank() || owner.equals(actor)) {
            LOGGER.infof("Access request for %s has no owner to deliver to", sanitize(resourceId));
            return RequestOutcome.SENT;
        }
        if (store.hasUnread(owner, actor, resourceId, WorkspaceNotification.Type.ACCESS_REQUESTED)) {
            // Not delivered twice — and answered exactly like a delivery, see SENT.
            return RequestOutcome.SENT;
        }
        deliver(new WorkspaceNotification(UUID.randomUUID().toString(), owner, WorkspaceNotification.Type.ACCESS_REQUESTED, resourceId,
                resourceUri(descriptor), descriptor.getName(), actor, directory.labelFor(actor), level.name(), cleanMessage(message), now, null));
        return RequestOutcome.SENT;
    }

    /**
     * This node's record of each requester's attempts in the last day — whatever
     * they asked for, existing or not. Per node: across a cluster a requester gets
     * up to the limit on each node, still bounded, and the stored count of
     * delivered requests applies everywhere.
     */
    private final Cache<String, Deque<Instant>> attempts = Caffeine.newBuilder().expireAfterAccess(Duration.ofDays(1)).maximumSize(100_000)
            .build();

    /** Records an attempt, or answers false when the requester is at the limit. */
    boolean recordAttempt(String actor, Instant now) {
        Deque<Instant> recent = attempts.get(actor, key -> new ArrayDeque<>());
        synchronized (recent) {
            Instant cutoff = now.minus(Duration.ofDays(1));
            while (!recent.isEmpty() && recent.peekFirst().isBefore(cutoff)) {
                recent.pollFirst();
            }
            if (recent.size() >= MAX_REQUESTS_PER_DAY) {
                return false;
            }
            recent.addLast(now);
            return true;
        }
    }

    /** Plain text, single spaces, bounded — it is shown to the owner verbatim. */
    static String cleanMessage(String message) {
        if (message == null) {
            return null;
        }
        String cleaned = message.replaceAll("\\p{Cntrl}", " ").replaceAll("\\s+", " ").trim();
        if (cleaned.isEmpty()) {
            return null;
        }
        return cleaned.length() > MAX_MESSAGE_LENGTH ? cleaned.substring(0, MAX_MESSAGE_LENGTH) : cleaned;
    }

    // --- The inbox -----------------------------------------------------------

    /** The caller's notifications, newest first. Empty for an anonymous caller. */
    public List<WorkspaceNotification> inbox(boolean unreadOnly, int limit) {
        String principal = spaceContext.currentPrincipal();
        if (principal == null) {
            return List.of();
        }
        return store.list(principal, unreadOnly, Math.max(1, Math.min(limit, INBOX_SIZE)));
    }

    /** How many of the caller's notifications are unread. */
    public long unreadCount() {
        String principal = spaceContext.currentPrincipal();
        return principal == null ? 0 : store.countUnread(principal);
    }

    /**
     * Marks the caller's notifications read — only the caller's: an id belonging to
     * somebody else's inbox matches nothing.
     *
     * @param ids
     *            the notifications, or null for all
     */
    public long markRead(Collection<String> ids) {
        String principal = spaceContext.currentPrincipal();
        if (principal == null) {
            return 0;
        }
        return store.markRead(principal, ids == null ? null : List.copyOf(ids), Instant.now());
    }

    // --- GDPR ----------------------------------------------------------------

    public long erase(String principal) {
        return store.deleteInvolving(principal);
    }

    public List<WorkspaceNotification> export(String principal) {
        return store.listInvolving(principal, 10_000);
    }

    private void deliver(WorkspaceNotification notification) {
        try {
            store.add(notification);
            store.prune(notification.recipient(), INBOX_SIZE, notification.createdAt().minus(RETENTION));
        } catch (RuntimeException e) {
            LOGGER.warnf("Could not deliver a %s notification to '%s': %s", notification.type(), sanitize(notification.recipient()),
                    e.getMessage());
        }
    }

    private static String resourceUri(DocumentDescriptor descriptor) {
        URI resource = descriptor.getResource();
        return resource == null ? null : resource.toString();
    }
}
