/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.attachments;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * The one order in which the attachment stores take their locks:
 * <b>conversation → user → team → source</b>.
 * <p>
 * Every write that needs more than one lock takes them in this order, so two
 * writes can never wait on each other in a cycle. Conversation before user is
 * the order the stores have always used (an upload, then a derived write); team
 * and source come after them, so a node of an older release and one of this
 * release cannot deadlock in a rolling upgrade either. Reversing any pair
 * would.
 * <p>
 * The stores map a {@link Lock} to their own key format; this class only fixes
 * the order. A personal library's bucket locks in the user place, under an id
 * of its own ({@link LibraryStoreSupport#bucketLock}). No team lock is taken
 * yet: team buckets arrive with the team library, and their place in the order
 * is reserved here.
 *
 * @since 6.8.0
 */
public final class AttachmentLockOrder {

    private AttachmentLockOrder() {
        // utility class
    }

    /** What a lock serializes, in the order locks are taken. */
    public enum Scope {
        /** A conversation's quota bucket. */
        CONVERSATION,
        /** A user's quota bucket. */
        USER,
        /** A team library's quota bucket (not taken yet). */
        TEAM,
        /** One root's lineage (derived writes). */
        SOURCE
    }

    /**
     * One lock.
     *
     * @param scope
     *            what it serializes
     * @param id
     *            whose: the conversation id, user id, team owner key or root ref
     */
    public record Lock(Scope scope, String id) {
    }

    /**
     * The locks to take, in the fixed order. A lock without an id (a user that is
     * not known) is dropped, and so is a repeated one. Two locks of one scope (a
     * user's upload bucket and their library's) are taken in the order of their
     * ids, so two writes that need both cannot take them in opposite order.
     *
     * @param wanted
     *            the locks a write needs, in any order
     * @return them in the order they must be taken
     */
    public static List<Lock> ordered(Collection<Lock> wanted) {
        List<Lock> locks = new ArrayList<>(new LinkedHashSet<>(wanted.stream().filter(lock -> lock != null && lock.id() != null).toList()));
        locks.sort(Comparator.comparing(Lock::scope).thenComparing(Lock::id));
        return locks;
    }
}
